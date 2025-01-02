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

use std::fmt::Debug;
use std::fmt::Display;
use std::fmt::Formatter;

use jni::objects::JThrowable;
use jni::objects::JValue;
use jni::JNIEnv;

pub(crate) struct Error {
    inner: databend_driver::Error,
}

impl Error {
    pub(crate) fn throw(&self, env: &mut JNIEnv) {
        if let Err(err) = self.do_throw(env) {
            match err {
                jni::errors::Error::JavaException => {
                    // other calls throws exception; safely ignored
                }
                _ => env.fatal_error(err.to_string()),
            }
        }
    }

    pub(crate) fn to_exception<'local>(
        &self,
        env: &mut JNIEnv<'local>,
    ) -> jni::errors::Result<JThrowable<'local>> {
        let class = env.find_class("java/sql/SQLException")?;
        let message = env.new_string(format!("{:?}", self.inner))?;
        let exception =
            env.new_object(class, "(Ljava/lang/String;)V", &[JValue::Object(&message)])?;
        Ok(JThrowable::from(exception))
    }

    fn do_throw(&self, env: &mut JNIEnv) -> jni::errors::Result<()> {
        let exception = self.to_exception(env)?;
        env.throw(exception)
    }
}

impl From<databend_driver::Error> for Error {
    fn from(err: databend_driver::Error) -> Self {
        Self { inner: err }
    }
}

impl From<jni::errors::Error> for Error {
    fn from(err: jni::errors::Error) -> Self {
        databend_driver::Error::Unexpected(err.to_string()).into()
    }
}

impl Debug for Error {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        Debug::fmt(&self.inner, f)
    }
}

impl Display for Error {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        Display::fmt(&self.inner, f)
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        self.inner.source()
    }
}
