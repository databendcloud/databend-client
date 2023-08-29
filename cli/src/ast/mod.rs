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

mod tokenizer;

use sqlformat::{Indent, QueryParams};
pub use tokenizer::*;

use crate::session::QueryKind;

pub fn format_query(query: &str) -> String {
    let kind = QueryKind::from(query);
    if kind == QueryKind::Put || kind == QueryKind::Get {
        return query.to_owned();
    }
    let options = sqlformat::FormatOptions {
        indent: Indent::Spaces(2),
        uppercase: true,
        lines_between_queries: 1,
    };
    sqlformat::format(query, &QueryParams::None, options)
}
