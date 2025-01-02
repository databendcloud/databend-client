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

use crate::jni_utils::executor::executor_or_default;
use crate::jni_utils::executor::Executor;
use crate::Result;
use jni::objects::JClass;

use databend_driver::rest_api::RowBatch;
use jni::sys::jstring;
use jni::sys::{jlong, jobject};

use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_databend_bendsql_NativeRowBatchIterator_fetchNextRowBatch(
    mut env: JNIEnv,
    _class: JClass,
    it: *mut RowBatch,
    executor: *const Executor,
) -> jstring {
    fetch_next_row_batch(&mut env, it, executor).unwrap_or_else(|e| {
        e.throw(&mut env);
        std::ptr::null_mut()
    })
}

fn fetch_next_row_batch(
    env: &mut JNIEnv,
    it: *mut RowBatch,
    executor: *const Executor,
) -> Result<jobject> {
    let batch = unsafe { &mut *it };

    let data = executor_or_default(env, executor)?
        .block_on(async move { batch.fetch_next_page().await })?;

    if !data.is_empty() {
        let json = serde_json::to_string(&data).unwrap();
        let jstring = env.new_string(json)?;
        Ok(jstring.into_raw())
    } else {
        Ok(std::ptr::null_mut())
    }
}

#[no_mangle]
pub extern "system" fn Java_com_databend_bendsql_NativeRowBatchIterator_getSchema(
    mut env: JNIEnv,
    _class: JClass,
    it: *mut RowBatch,
) -> jstring {
    get_schema(&mut env, it).unwrap_or_else(|e| {
        e.throw(&mut env);
        std::ptr::null_mut()
    })
}

fn get_schema(env: &mut JNIEnv, it: *mut RowBatch) -> Result<jstring> {
    let batch = unsafe { &mut *it };
    let schema = batch.schema();
    let json = serde_json::to_string(&schema).unwrap();
    let jstring = env.new_string(json)?;
    Ok(jstring.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_databend_bendsql_NativeRowBatchIterator_disposeInternal(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    _executor: *const Executor,
) {
    if handle != 0 {
        let _ = unsafe { Box::from_raw(handle as *mut RowBatch) };
    }
}
