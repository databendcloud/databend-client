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
use std::path::Path;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use async_trait::async_trait;
use log::info;
use tokio::fs::File;
use tokio_stream::Stream;

use databend_client::presign::PresignedResponse;
use databend_client::response::QueryResponse;
use databend_client::APIClient;
use databend_sql::error::{Error, Result};
use databend_sql::rows::{Row, RowIterator, RowStatsIterator, RowWithStats, ServerStats};
use databend_sql::schema::{Schema, SchemaRef};

use crate::conn::{Connection, ConnectionInfo, Reader};

#[derive(Clone)]
pub struct RestAPIConnection {
    client: APIClient,
}

#[async_trait]
impl Connection for RestAPIConnection {
    async fn info(&self) -> ConnectionInfo {
        ConnectionInfo {
            handler: "RestAPI".to_string(),
            host: self.client.host.clone(),
            port: self.client.port,
            user: self.client.user.clone(),
            database: self.client.current_database().await,
            warehouse: self.client.current_warehouse().await,
        }
    }

    async fn exec(&self, sql: &str) -> Result<i64> {
        info!("exec: {}", sql);
        let mut resp = self.client.start_query(sql).await?;
        while let Some(next_uri) = resp.next_uri {
            resp = self.client.query_page(&resp.id, &next_uri).await?;
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
        let (schema, rows) = RestAPIRows::from_response(self.client.clone(), resp)?;
        Ok(RowStatsIterator::new(Arc::new(schema), Box::pin(rows)))
    }

    async fn query_row(&self, sql: &str) -> Result<Option<Row>> {
        info!("query row: {}", sql);
        let resp = self.client.start_query(sql).await?;
        let resp = self.wait_for_data(resp).await?;
        match resp.kill_uri {
            Some(uri) => self
                .client
                .kill_query(&resp.id, &uri)
                .await
                .map_err(|e| e.into()),
            None => Err(Error::InvalidResponse("kill_uri is empty".to_string())),
        }?;
        let schema = resp.schema.try_into()?;
        if resp.data.is_empty() {
            Ok(None)
        } else {
            let row = Row::try_from((Arc::new(schema), &resp.data[0]))?;
            Ok(Some(row))
        }
    }

    async fn get_presigned_url(&self, operation: &str, stage: &str) -> Result<PresignedResponse> {
        info!("get presigned url: {} {}", operation, stage);
        let sql = format!("PRESIGN {} {}", operation, stage);
        let row = self.query_row(&sql).await?.ok_or(Error::InvalidResponse(
            "Empty response from server for presigned request".to_string(),
        ))?;
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
        self.upload_to_stage(&stage, data, size).await?;
        let file_format_options =
            file_format_options.unwrap_or_else(Self::default_file_format_options);
        let copy_options = copy_options.unwrap_or_else(Self::default_copy_options);
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
        mut format_options: BTreeMap<&str, &str>,
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
        if !format_options.contains_key("type") {
            let file_type = fp
                .extension()
                .ok_or(Error::BadArgument("file type not specified".to_string()))?
                .to_str()
                .ok_or(Error::BadArgument("file type empty".to_string()))?;
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
    pub async fn try_create(dsn: &str) -> Result<Self> {
        let client = APIClient::from_dsn(dsn).await?;
        Ok(Self { client })
    }

    async fn wait_for_data(&self, pre: QueryResponse) -> Result<QueryResponse> {
        if !pre.data.is_empty() {
            return Ok(pre);
        }
        let mut result = pre;
        // preserve schema since it is no included in the final response
        let schema = result.schema;
        while let Some(next_uri) = result.next_uri {
            result = self.client.query_page(&result.id, &next_uri).await?;
            if !result.data.is_empty() {
                break;
            }
        }
        result.schema = schema;
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
}

type PageFut = Pin<Box<dyn Future<Output = Result<QueryResponse>> + Send>>;

pub struct RestAPIRows {
    client: APIClient,
    schema: SchemaRef,
    data: VecDeque<Vec<String>>,
    query_id: String,
    next_uri: Option<String>,
    next_page: Option<PageFut>,
}

impl RestAPIRows {
    fn from_response(client: APIClient, resp: QueryResponse) -> Result<(Schema, Self)> {
        let schema: Schema = resp.schema.try_into()?;
        let rows = Self {
            client,
            query_id: resp.id,
            next_uri: resp.next_uri,
            schema: Arc::new(schema.clone()),
            data: resp.data.into(),
            next_page: None,
        };
        Ok((schema, rows))
    }
}

impl Stream for RestAPIRows {
    type Item = Result<RowWithStats>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        if let Some(row) = self.data.pop_front() {
            let row = Row::try_from((self.schema.clone(), &row))?;
            return Poll::Ready(Some(Ok(RowWithStats::Row(row))));
        }
        match self.next_page {
            Some(ref mut next_page) => match Pin::new(next_page).poll(cx) {
                Poll::Ready(Ok(resp)) => {
                    self.data = resp.data.into();
                    self.query_id = resp.id;
                    self.next_uri = resp.next_uri;
                    self.next_page = None;
                    let ss = ServerStats::from(resp.stats);
                    Poll::Ready(Some(Ok(RowWithStats::Stats(ss))))
                }
                Poll::Ready(Err(e)) => {
                    self.next_page = None;
                    Poll::Ready(Some(Err(e)))
                }
                Poll::Pending => {
                    cx.waker().wake_by_ref();
                    Poll::Pending
                }
            },
            None => match self.next_uri {
                Some(ref next_uri) => {
                    let client = self.client.clone();
                    let next_uri = next_uri.clone();
                    let query_id = self.query_id.clone();
                    self.next_page = Some(Box::pin(async move {
                        client
                            .query_page(&query_id, &next_uri)
                            .await
                            .map_err(|e| e.into())
                    }));
                    self.poll_next(cx)
                }
                None => Poll::Ready(None),
            },
        }
    }
}
