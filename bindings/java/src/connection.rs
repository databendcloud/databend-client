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

use std::path::Path;

use databend_driver::Connection;
use jni::objects::JClass;
use jni::objects::JString;
use jni::sys::jlong;
use jni::JNIEnv;

use crate::error::Error;
use crate::jni_utils::executor::executor_or_default;
use crate::jni_utils::executor::Executor;

use crate::jni_utils::jstring_to_string;
use crate::Result;
use databend_driver::rest_api::RestAPIConnection;

#[no_mangle]
pub extern "system" fn Java_com_databend_bendsql_NativeConnection_constructor(
    mut env: JNIEnv,
    _: JClass,
    executor: *const Executor,
    dsn: JString,
) -> jlong {
    intern_constructor(&mut env, executor, dsn).unwrap_or_else(|e| {
        e.throw(&mut env);
        0
    })
}

fn intern_constructor(env: &mut JNIEnv, executor: *const Executor, dsn: JString) -> Result<jlong> {
    let mut dsn = jstring_to_string(env, &dsn)?;
    if dsn.starts_with("jdbc:") {
        dsn = dsn[5..].to_string();
    }
    let result = executor_or_default(env, executor)?.block_on(async move {
        let conn = RestAPIConnection::try_create(&dsn, "jdbc".to_string()).await;
        let handle = conn
            .map(|conn| Box::into_raw(Box::new(conn)) as jlong)
            .map_err(|e| Error::from(e));
        handle
    })?;
    Ok(result)
}

// #[no_mangle]
// pub extern "system" fn Java_com_databend_bendsql_NativeConnection_execute(
//     mut env: JNIEnv,
//     _: JClass,
//     connection: *mut RestAPIConnection,
//     executor: *const Executor,
//     sql: JString,
// ) -> jlong {
//     intern_execute(&mut env, connection, executor, sql).unwrap_or_else(|e| {
//         e.throw(&mut env);
//         0
//     })
// }

// fn intern_execute(
//     env: &mut JNIEnv,
//     connection: *mut RestAPIConnection,
//     executor: *const Executor,
//     sql: JString,
// ) -> Result<jlong> {
//     let sql = jstring_to_string(env, &sql)?;
//     let connection = unsafe { &mut *connection };

//     let result = executor_or_default(env, executor)?.block_on(async move {
//         connection
//             .exec(&sql)
//             .await
//             .map(|result| Box::into_raw(Box::new(result)) as jlong)
//             .map_err(Error::from)
//     })?;
//     Ok(result)
// }

#[no_mangle]
pub extern "system" fn Java_com_databend_bendsql_NativeConnection_loadFile(
    mut env: JNIEnv,
    _: JClass,
    connection: *mut RestAPIConnection,
    executor: *const Executor,
    sql: JString,
    path: JString,
) -> jlong {
    intern_load_file(&mut env, connection, executor, sql, path).unwrap_or_else(|e| {
        e.throw(&mut env);
        0
    })
}

fn intern_load_file(
    env: &mut JNIEnv,
    connection: *mut RestAPIConnection,
    executor: *const Executor,
    sql: JString,
    path: JString,
) -> Result<jlong> {
    let sql = jstring_to_string(env, &sql)?;
    let path = jstring_to_string(env, &path)?;
    let connection = unsafe { &mut *connection };

    let result = executor_or_default(env, executor)?.block_on(async move {
        let path = Path::new(&path);
        connection
            .load_file(&sql, &path, None, None)
            .await
            .map(|result| Box::into_raw(Box::new(result)) as jlong)
            .map_err(Error::from)
    })?;
    Ok(result)
}

#[no_mangle]
pub extern "system" fn Java_com_databend_bendsql_NativeConnection_execute(
    mut env: JNIEnv,
    _: JClass,
    connection: *mut RestAPIConnection,
    executor: *const Executor,
    sql: JString,
) -> jlong {
    intern_execute(&mut env, connection, executor, sql).unwrap_or_else(|e| {
        e.throw(&mut env);
        0
    })
}

fn intern_execute(
    env: &mut JNIEnv,
    connection: *mut RestAPIConnection,
    executor: *const Executor,
    sql: JString,
) -> Result<jlong> {
    let sql = jstring_to_string(env, &sql)?;
    let connection = unsafe { &mut *connection };

    let it = executor_or_default(env, executor)?
        .block_on(async move { connection.query_row_batch(&sql).await })?;
    if it.schema().is_empty() {
        Ok(0)
    } else {
        Ok(Box::into_raw(Box::new(it)) as jlong)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_databend_bendsql_NativeConnection_disposeInternal(
    env: &mut JNIEnv,
    _class: JClass,
    handle: jlong,
    executor: *const Executor,
) {
    if handle != 0 {
        let conn = unsafe { Box::from_raw(handle as *mut RestAPIConnection) };
        executor_or_default(env, executor)
            .unwrap()
            .block_on(async move { conn.close().await })
            .ok();
    }
}
