# jni-macro

A Rust macro system for distributed JNI initialization registration.

## Features

- 🎯 **Distributed initialization**: Define JNI init functions anywhere in your codebase
- 🔢 **Priority-based ordering**: Control execution order with numeric priorities (higher = first)
- 🚀 **Zero boilerplate**: Automatic collection via `inventory` crate
- 📦 **Single dependency**: Users only need to depend on `jni-macro`
- ⚡ **Automatic method registration**: Use `#[jni_method]` to register native methods without manual `register_native_methods!` calls

## Installation

Add to your `Cargo.toml`:

```toml
[dependencies]
jni-macro = { path = "path/to/jni-macro/jni-facade" }
```

## Usage

### 1. Define initialization functions with `#[jni_onload]`

You can define initialization functions anywhere in your codebase:

```rust
use jni_macro::jni_onload;
use jni::{JNIEnv, JavaVM};

// High priority - executes first
#[jni_onload(100)]
fn init_logger(env: &mut JNIEnv, vm: &JavaVM) {
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("MyApp")
    );
}

// Lower priority - executes after logger
#[jni_onload(90)]
fn init_driver(env: &mut JNIEnv, vm: &JavaVM) {
    register_native_methods!(env, "com/example/MyClass", [
        "nativeMethod" => "()V" => my_native_method,
    ]);
}
```

### 2. Collect and execute in `JNI_OnLoad`

```rust
use jni::sys::{jint, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use std::ffi::c_void;

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
    let mut env = vm.get_env().expect("Cannot get JNIEnv");

    // Collect all registered initializers
    let mut initializers: Vec<_> = jni_macro::inventory::iter::<jni_macro::JniInitializer>()
        .collect();

    // Sort by priority (descending - higher priority first)
    initializers.sort_by_key(|init| std::cmp::Reverse(init.priority));

    // Execute all initializers in order
    for init in initializers {
        println!("Executing: {} (priority: {})", init.name, init.priority);
        (init.init_fn)(&mut env, &vm);
    }

    JNI_VERSION_1_6
}
```

### 3. Register native methods with `#[jni_method]` (Alternative Approach)

Instead of manually calling `register_native_methods!`, you can use `#[jni_method]` to register methods declaratively:

```rust
use jni_macro::jni_method;
use jni::{JNIEnv, objects::JObject, sys::jboolean};

// Each method registers itself with priority
#[jni_method(90, "moe/fuqiuluo/mamu/driver/WuwaDriver", "nativeIsLoaded", "()Z")]
pub fn jni_is_loaded(mut env: JNIEnv, obj: JObject) -> jboolean {
    1 // true
}

#[jni_method(90, "moe/fuqiuluo/mamu/driver/WuwaDriver", "nativeBindProcess", "(I)Z")]
pub fn jni_bind_proc(mut env: JNIEnv, obj: JObject, pid: jint) -> jboolean {
    // implementation
    1
}

#[jni_method(80, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeSearch", "(Ljava/lang/String;)V")]
pub fn jni_search(mut env: JNIEnv, obj: JObject, query: JString) {
    // implementation
}
```

Then use the automatic registration function in `JNI_OnLoad`:

```rust
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
    let mut env = vm.get_env().expect("Cannot get JNIEnv");

    // Initialize logger first
    let mut initializers: Vec<_> = jni_macro::inventory::iter::<jni_macro::JniInitializer>()
        .collect();
    initializers.sort_by_key(|init| std::cmp::Reverse(init.priority));
    for init in initializers {
        (init.init_fn)(&mut env, &vm);
    }

    // Automatically register all methods marked with #[jni_method]
    // Methods are grouped by priority and class
    jni_macro::register_all_jni_methods(&mut env);

    JNI_VERSION_1_6
}
```

**Benefits of `#[jni_method]`:**
- ✅ Each method declares its own registration information
- ✅ No need to maintain a central registration list
- ✅ Priority-based registration order (higher = registered first)
- ✅ Methods are automatically grouped by class for efficient registration

## Architecture

This crate is composed of three sub-crates:

- **jni-macro-core**: Runtime types (`JniInitializer`, etc.)
- **jni-macro-derive**: Procedural macro implementation (`#[jni_onload]`)
- **jni-macro** (facade): Unified interface that re-exports everything

Users only need to depend on `jni-macro`.

## How It Works

1. `#[jni_onload(priority)]` macro wraps your function and submits it to `inventory`
2. `inventory` collects all submissions at link time
3. At runtime, `JNI_OnLoad` iterates through collected functions and executes them

## Benefits

- ✅ No need to modify `JNI_OnLoad` when adding new modules
- ✅ Each module manages its own initialization logic
- ✅ Clear priority-based execution order
- ✅ Type-safe and compile-time checked
- ✅ Zero runtime overhead after initialization

## Example Project Structure

```
src/
├── lib.rs           # JNI_OnLoad + global init
├── driver/
│   └── mod.rs       # #[jni_onload(70)] fn init_driver()
├── logger/
│   └── mod.rs       # #[jni_onload(100)] fn init_logger()
└── search/
    └── mod.rs       # #[jni_onload(60)] fn init_search()
```

## Credits

Built with:
- [inventory](https://github.com/dtolnay/inventory) for compile-time registration
- [syn](https://github.com/dtolnay/syn), [quote](https://github.com/dtolnay/quote) for proc-macro implementation