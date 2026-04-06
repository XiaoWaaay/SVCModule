use jni::{JNIEnv, JavaVM};
use std::ffi::c_void;

/// JNI initialization function type
pub type JniInitFn = fn(&mut JNIEnv, &JavaVM);

/// JNI initialization function registry entry
pub struct JniInitializer {
    pub priority: u32,
    pub name: &'static str,
    pub init_fn: JniInitFn,
}

/// JNI method registration entry
pub struct JniMethodRegistration {
    pub priority: u32,
    pub class_path: &'static str,
    pub method_name: &'static str,
    pub signature: &'static str,
    pub fn_ptr: *mut c_void,
}

// Safety: fn pointers are safe to send across threads
unsafe impl Send for JniMethodRegistration {}
unsafe impl Sync for JniMethodRegistration {}

// Collect all JNI initializers using inventory
inventory::collect!(JniInitializer);

// Collect all JNI method registrations using inventory
inventory::collect!(JniMethodRegistration);

/// Helper function to register all collected JNI methods
///
/// This function collects all methods registered via `#[jni_method]`,
/// sorts them by priority (higher first), groups by class, and registers them.
///
/// # Example
/// ```
/// use jni_macro_core::register_all_jni_methods;
///
/// #[no_mangle]
/// pub extern "system" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
///     let mut env = vm.get_env().unwrap();
///     register_all_jni_methods(&mut env);
///     JNI_VERSION_1_6
/// }
/// ```
pub fn register_all_jni_methods(env: &mut JNIEnv) {
    use jni::NativeMethod;
    use log::info;
    use std::collections::HashMap;

    // Collect all method registrations
    let mut methods: Vec<_> = inventory::iter::<JniMethodRegistration>().collect();

    // Sort by priority (descending - higher priority first)
    methods.sort_by_key(|m| std::cmp::Reverse(m.priority));

    // Group methods by class path while preserving priority order
    let mut class_methods: HashMap<&str, Vec<&JniMethodRegistration>> = HashMap::new();
    for method in methods.iter() {
        class_methods
            .entry(method.class_path)
            .or_insert_with(Vec::new)
            .push(method);
    }

    // Register methods for each class
    for (class_path, methods) in class_methods.iter() {
        let class = match env.find_class(class_path) {
            Ok(c) => c,
            Err(e) => {
                log::error!("Failed to find class {}: {:?}", class_path, e);
                continue;
            },
        };

        let native_methods: Vec<NativeMethod> = methods
            .iter()
            .map(|m| NativeMethod {
                name: m.method_name.into(),
                sig: m.signature.into(),
                fn_ptr: m.fn_ptr,
            })
            .collect();

        match env.register_native_methods(&class, &native_methods) {
            Ok(_) => {
                info!(
                    "Registered {} methods for class {} (priorities: {:?})",
                    native_methods.len(),
                    class_path,
                    methods.iter().map(|m| m.priority).collect::<Vec<_>>()
                );
            },
            Err(e) => {
                log::error!("Failed to register methods for class {}: {:?}", class_path, e);
            },
        }
    }
}
