// Copyright 2023 Datafuse Labs.
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

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize, Debug)]
pub struct SessionConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub database: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub settings: Option<BTreeMap<String, String>>,
}

#[derive(Serialize, Debug)]
pub struct QueryRequest<'a> {
    #[serde(skip_serializing_if = "Option::is_none")]
    session: Option<SessionConfig>,
    sql: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pagination: Option<PaginationConfig>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stage_attachment: Option<StageAttachmentConfig>,
}

#[derive(Serialize, Debug)]
pub struct PaginationConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wait_time_secs: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_rows_in_buffer: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_rows_per_page: Option<i64>,
}

#[derive(Serialize, Debug)]
pub struct StageAttachmentConfig {
    pub location: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file_format_options: Option<BTreeMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub copy_options: Option<BTreeMap<String, String>>,
}

impl QueryRequest<'_> {
    pub fn new<'a>(sql: &'a str) -> QueryRequest<'a> {
        QueryRequest {
            session: None,
            sql,
            pagination: None,
            stage_attachment: None,
        }
    }

    pub fn with_session(mut self, session: Option<SessionConfig>) -> Self {
        self.session = session;
        self
    }

    pub fn with_pagination(mut self, pagination: Option<PaginationConfig>) -> Self {
        self.pagination = pagination;
        self
    }

    pub fn with_stage_attachment(
        mut self,
        stage_attachment: Option<StageAttachmentConfig>,
    ) -> Self {
        self.stage_attachment = stage_attachment;
        self
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use anyhow::Result;

    #[test]
    fn build_request() -> Result<()> {
        let req = QueryRequest::new("select 1")
            .with_session(Some(SessionConfig {
                database: Some("default".to_string()),
                settings: Some(BTreeMap::new()),
            }))
            .with_pagination(Some(PaginationConfig {
                wait_time_secs: Some(1),
                max_rows_in_buffer: Some(1),
                max_rows_per_page: Some(1),
            }))
            .with_stage_attachment(Some(StageAttachmentConfig {
                location: "@~/my_location".into(),
                file_format_options: None,
                copy_options: None,
            }));
        assert_eq!(
            serde_json::to_string(&req)?,
            r#"{"session":{"database":"default","settings":{}},"sql":"select 1","pagination":{"wait_time_secs":1,"max_rows_in_buffer":1,"max_rows_per_page":1},"stage_attachment":{"location":"@~/my_location"}}"#
        );
        Ok(())
    }
}
