// Copyright 2021 Datafuse Labs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use std::collections::{BTreeMap, VecDeque};
use std::future::Future;
use std::io::Cursor;
use std::path::Path;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use async_compression::tokio::write::ZstdEncoder;
use async_trait::async_trait;
use log::info;
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_stream::Stream;

use databend_client::PresignedResponse;
use databend_client::QueryResponse;
use databend_client::{APIClient, SchemaField};
use databend_driver_core::error::{Error, Result};
use databend_driver_core::rows::{Row, RowIterator, RowStatsIterator, RowWithStats, ServerStats};
use databend_driver_core::schema::{Schema, SchemaRef};

use crate::conn::{Connection, ConnectionInfo, Reader};

#[derive(Clone)]
pub struct RestAPIConnection {
    client: Arc<APIClient>,
}

#[async_trait]
impl Connection for RestAPIConnection {
    async fn info(&self) -> ConnectionInfo {
        ConnectionInfo {
            handler: "RestAPI".to_string(),
            host: self.client.host().to_string(),
            port: self.client.port(),
            user: self.client.username(),
            database: self.client.current_database(),
            warehouse: self.client.current_warehouse(),
        }
    }

    async fn close(&self) -> Result<()> {
        self.client.close().await;
        Ok(())
    }

    async fn exec(&self, sql: &str) -> Result<i64> {
        info!("exec: {}", sql);
        let mut resp = self.client.start_query(sql).await?;
        let node_id = resp.node_id.clone();
        while let Some(next_uri) = resp.next_uri {
            resp = self
                .client
                .query_page(&resp.id, &next_uri, &node_id)
                .await?;
        }
        Ok(resp.stats.progresses.write_progress.rows as i64)
    }

    async fn query_iter(&self, sql: &str) -> Result<RowIterator> {
        info!("query iter: {}", sql);
        let rows_with_progress = self.query_iter_ext(sql).await?;
        let rows = rows_with_progress.filter_rows().await;
        Ok(rows)
    }

    async fn query_iter_ext(&self, sql: &str) -> Result<RowStatsIterator> {
        info!("query iter ext: {}", sql);
        let resp = self.client.start_query(sql).await?;
        let resp = self.wait_for_schema(resp, true).await?;
        let (schema, rows) = RestAPIRows::from_response(self.client.clone(), resp)?;
        Ok(RowStatsIterator::new(Arc::new(schema), Box::pin(rows)))
    }

    async fn get_presigned_url(&self, operation: &str, stage: &str) -> Result<PresignedResponse> {
        info!("get presigned url: {} {}", operation, stage);
        let sql = format!("PRESIGN {} {}", operation, stage);
        let row = self.query_row(&sql).await?.ok_or_else(|| {
            Error::InvalidResponse("Empty response from server for presigned request".to_string())
        })?;
        let (method, headers, url): (String, String, String) =
            row.try_into().map_err(Error::Parsing)?;
        let headers: BTreeMap<String, String> = serde_json::from_str(&headers)?;
        Ok(PresignedResponse {
            method,
            headers,
            url,
        })
    }

    async fn upload_to_stage(&self, stage: &str, data: Reader, size: u64) -> Result<()> {
        self.client.upload_to_stage(stage, data, size).await?;
        Ok(())
    }

    async fn load_data(
        &self,
        sql: &str,
        data: Reader,
        size: u64,
        file_format_options: Option<BTreeMap<&str, &str>>,
        copy_options: Option<BTreeMap<&str, &str>>,
    ) -> Result<ServerStats> {
        info!(
            "load data: {}, size: {}, format: {:?}, copy: {:?}",
            sql, size, file_format_options, copy_options
        );
        let now = chrono::Utc::now()
            .timestamp_nanos_opt()
            .ok_or_else(|| Error::IO("Failed to get current timestamp".to_string()))?;
        let stage = format!("@~/client/load/{}", now);

        let mut file_format_options =
            file_format_options.unwrap_or_else(Self::default_file_format_options);
        let copy_options = copy_options.unwrap_or_else(Self::default_copy_options);

        let mut data = data;
        let mut size = size;

        if !file_format_options.contains_key("compression") {
            let mut buffer = Vec::new();
            let real_size = data.read_to_end(&mut buffer).await?;
            if real_size != size as usize && size != 0 {
                return Err(Error::IO(format!(
                    "Failed to read all data, expected: {}, read: {}",
                    size, real_size
                )));
            }
            let mut encoder = ZstdEncoder::new(Vec::new());
            encoder.write_all(&buffer).await?;
            encoder.shutdown().await?;
            file_format_options.insert("compression", "ZSTD");
            let output = encoder.into_inner();
            size = output.len() as u64;
            data = Box::new(Cursor::new(output))
        }

        self.upload_to_stage(&stage, data, size).await?;
        let resp = self
            .client
            .insert_with_stage(sql, &stage, file_format_options, copy_options)
            .await?;
        Ok(ServerStats::from(resp.stats))
    }

    async fn load_file(
        &self,
        sql: &str,
        fp: &Path,
        format_options: Option<BTreeMap<&str, &str>>,
        copy_options: Option<BTreeMap<&str, &str>>,
    ) -> Result<ServerStats> {
        info!(
            "load file: {}, file: {:?}, format: {:?}, copy: {:?}",
            sql, fp, format_options, copy_options
        );
        let file = File::open(fp).await?;
        let metadata = file.metadata().await?;
        let data = Box::new(file);
        let size = metadata.len();
        let mut format_options = format_options.unwrap_or_else(Self::default_file_format_options);
        if !format_options.contains_key("type") {
            let file_type = fp
                .extension()
                .ok_or_else(|| Error::BadArgument("file type not specified".to_string()))?
                .to_str()
                .ok_or_else(|| Error::BadArgument("file type empty".to_string()))?;
            format_options.insert("type", file_type);
        }
        self.load_data(sql, data, size, Some(format_options), copy_options)
            .await
    }

    async fn stream_load(&self, sql: &str, data: Vec<Vec<&str>>) -> Result<ServerStats> {
        info!("stream load: {}, length: {:?}", sql, data.len());
        let mut wtr = csv::WriterBuilder::new().from_writer(vec![]);
        for row in data {
            wtr.write_record(row)
                .map_err(|e| Error::BadArgument(e.to_string()))?;
        }
        let bytes = wtr.into_inner().map_err(|e| Error::IO(e.to_string()))?;
        let size = bytes.len() as u64;
        let reader = Box::new(std::io::Cursor::new(bytes));
        let stats = self.load_data(sql, reader, size, None, None).await?;
        Ok(stats)
    }
}

impl<'o> RestAPIConnection {
    pub async fn try_create(dsn: &str, name: String) -> Result<Self> {
        let client = APIClient::new(dsn, Some(name)).await?;
        Ok(Self {
            client: Arc::new(client),
        })
    }

    async fn wait_for_schema(
        &self,
        resp: QueryResponse,
        return_on_progress: bool,
    ) -> Result<QueryResponse> {
        if !resp.data.is_empty()
            || !resp.schema.is_empty()
            || (return_on_progress && resp.stats.progresses.has_progress())
        {
            return Ok(resp);
        }
        let node_id = resp.node_id.clone();
        if let Some(node_id) = &node_id {
            self.client.set_last_node_id(node_id.clone());
        }
        let mut result = resp;
        // preserve schema since it is not included in the final response
        while let Some(next_uri) = result.next_uri {
            result = self
                .client
                .query_page(&result.id, &next_uri, &node_id)
                .await?;

            if !result.data.is_empty()
                || !result.schema.is_empty()
                || (return_on_progress && result.stats.progresses.has_progress())
            {
                break;
            }
        }
        Ok(result)
    }

    fn default_file_format_options() -> BTreeMap<&'o str, &'o str> {
        vec![
            ("type", "CSV"),
            ("field_delimiter", ","),
            ("record_delimiter", "\n"),
            ("skip_header", "0"),
        ]
        .into_iter()
        .collect()
    }

    fn default_copy_options() -> BTreeMap<&'o str, &'o str> {
        vec![("purge", "true")].into_iter().collect()
    }

    pub async fn query_row_batch(&self, sql: &str) -> Result<RowBatch> {
        let resp = self.client.start_query(sql).await?;
        let resp = self.wait_for_schema(resp, false).await?;
        Ok(RowBatch::from_response(self.client.clone(), resp)?)
    }
}

type PageFut = Pin<Box<dyn Future<Output = Result<QueryResponse>> + Send>>;

pub struct RestAPIRows {
    client: Arc<APIClient>,
    schema: SchemaRef,
    data: VecDeque<Vec<Option<String>>>,
    stats: Option<ServerStats>,
    query_id: String,
    node_id: Option<String>,
    next_uri: Option<String>,
    next_page: Option<PageFut>,
}

impl RestAPIRows {
    fn from_response(client: Arc<APIClient>, resp: QueryResponse) -> Result<(Schema, Self)> {
        let schema: Schema = resp.schema.try_into()?;
        let rows = Self {
            client,
            query_id: resp.id,
            node_id: resp.node_id,
            next_uri: resp.next_uri,
            schema: Arc::new(schema.clone()),
            data: resp.data.into(),
            stats: Some(ServerStats::from(resp.stats)),
            next_page: None,
        };
        Ok((schema, rows))
    }
}

impl Stream for RestAPIRows {
    type Item = Result<RowWithStats>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        if let Some(ss) = self.stats.take() {
            return Poll::Ready(Some(Ok(RowWithStats::Stats(ss))));
        }
        // Skip to fetch next page if there is only one row left in buffer.
        // Therefore we could guarantee the `/final` called before the last row.
        if self.data.len() > 1 {
            if let Some(row) = self.data.pop_front() {
                let row = Row::try_from((self.schema.clone(), row))?;
                return Poll::Ready(Some(Ok(RowWithStats::Row(row))));
            }
        }
        match self.next_page {
            Some(ref mut next_page) => match Pin::new(next_page).poll(cx) {
                Poll::Ready(Ok(resp)) => {
                    if self.schema.fields().is_empty() {
                        self.schema = Arc::new(resp.schema.try_into()?);
                    }
                    self.next_uri = resp.next_uri;
                    self.next_page = None;
                    let mut new_data = resp.data.into();
                    self.data.append(&mut new_data);
                    let stats = ServerStats::from(resp.stats);
                    Poll::Ready(Some(Ok(RowWithStats::Stats(stats))))
                }
                Poll::Ready(Err(e)) => {
                    self.next_page = None;
                    Poll::Ready(Some(Err(e)))
                }
                Poll::Pending => Poll::Pending,
            },
            None => match self.next_uri {
                Some(ref next_uri) => {
                    let client = self.client.clone();
                    let next_uri = next_uri.clone();
                    let query_id = self.query_id.clone();
                    let node_id = self.node_id.clone();
                    self.next_page = Some(Box::pin(async move {
                        client
                            .query_page(&query_id, &next_uri, &node_id)
                            .await
                            .map_err(|e| e.into())
                    }));
                    self.poll_next(cx)
                }
                None => match self.data.pop_front() {
                    Some(row) => {
                        let row = Row::try_from((self.schema.clone(), row))?;
                        Poll::Ready(Some(Ok(RowWithStats::Row(row))))
                    }
                    None => Poll::Ready(None),
                },
            },
        }
    }
}

pub struct RowBatch {
    schema: Vec<SchemaField>,
    client: Arc<APIClient>,
    query_id: String,
    node_id: Option<String>,

    next_uri: Option<String>,
    data: Vec<Vec<Option<String>>>,
}

impl RowBatch {
    pub fn schema(&self) -> Vec<SchemaField> {
        self.schema.clone()
    }

    fn from_response(client: Arc<APIClient>, mut resp: QueryResponse) -> Result<Self> {
        Ok(Self {
            schema: std::mem::take(&mut resp.schema),
            client,
            query_id: resp.id,
            node_id: resp.node_id,
            next_uri: resp.next_uri,
            data: resp.data,
        })
    }

    pub async fn fetch_next_page(&mut self) -> Result<Vec<Vec<Option<String>>>> {
        if !self.data.is_empty() {
            return Ok(std::mem::take(&mut self.data));
        }
        while let Some(next_uri) = &self.next_uri {
            let resp = self
                .client
                .query_page(&self.query_id, &next_uri, &self.node_id)
                .await?;

            self.next_uri = resp.next_uri;
            if !resp.data.is_empty() {
                return Ok(resp.data);
            }
        }
        Ok(vec![])
    }
}
