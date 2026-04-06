use anyhow::Context;
use jni::JNIEnv;
use jni::objects::{JClass, JString};

/// 抛出 RuntimeException 异常，支持格式化字符串
#[macro_export] 
macro_rules! throw_runtime_exception {
    ($env:expr, $($arg:tt)*) => {{
        let runtime_exception_class = $env
            .find_class("java/lang/RuntimeException")
            .expect("无法找到 RuntimeException 类");
        let msg = format!($($arg)*);
        $env.throw_new(runtime_exception_class, msg)
            .expect("无法抛出异常");
    }};
}

pub type JniResult<T> = anyhow::Result<T>;

pub trait JniResultExt<T> {
    fn or_throw(self, env: &mut JNIEnv) -> T;
}

impl<T: Default> JniResultExt<T> for JniResult<T> {
    fn or_throw(self, env: &mut JNIEnv) -> T {
        self.unwrap_or_else(|e| {
            let _ = env.throw(format!("{:#}", e));
            T::default()
        })
    }
}

pub trait JNIEnvExt<'l> {
    fn find_class_safe(&mut self, name: &str) -> JniResult<JClass<'l>>;
    fn new_string_safe(&mut self, s: String) -> JniResult<JString<'l>>;
}

impl<'l> JNIEnvExt<'l> for JNIEnv<'l> {
    fn find_class_safe(&mut self, name: &str) -> JniResult<JClass<'l>> {
        self.find_class(name)
            .with_context(|| format!("Cannot find class: {}", name))
    }

    fn new_string_safe(&mut self, s: String) -> JniResult<JString<'l>> {
        self.new_string(s)
            .context("Failed to create JString")
    }
}
