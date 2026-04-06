//! JNI methods for MamuApplication

use crate::ext::jni::{JniResult, JniResultExt};
use jni::JNIEnv;
use jni::objects::JObject;
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean};
use jni_macro::jni_method;
use log::info;
use obfstr::obfstr as s;

#[jni_method(90, "moe/fuqiuluo/mamu/MamuApplication", "initMamuCore", "()Z")]
pub fn jni_init_core(mut env: JNIEnv, obj: JObject) -> jboolean {
    (|| -> JniResult<jboolean> {
        rayon::ThreadPoolBuilder::new().num_threads(8).build_global()?;

        let package_name = env
            .call_method(&obj, s!("getPackageName"), s!("()Ljava/lang/String;"), &[])?
            .l()?;
        let package_name = jni::objects::JString::from(package_name);
        let package_name_str: String = env.get_string(&package_name)?.into();

        // 这里做一个简单的包名验证，确保只在指定包名下初始化
        if package_name_str != s!("moe.fuqiuluo.mamu") {
            env.throw(s!("Invalid package name for Mamu core initialization"))?;
            return Ok(JNI_FALSE);
        }

        info!("{}: {}", s!("初始化Mamu核心成功，包名"), package_name_str);

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}