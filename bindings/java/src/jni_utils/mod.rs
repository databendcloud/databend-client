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

pub(crate) mod executor;

use jni::objects::JString;

use crate::Result;
use jni::JNIEnv;

/// # Safety
///
/// The caller must guarantee that the Object passed in is an instance
/// of `java.lang.String`, passing in anything else will lead to undefined behavior.
pub(crate) fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Result<String> {
    let res = unsafe { env.get_string_unchecked(s)? };
    Ok(res.into())
}
