//! # jni-macro
//!
//! JNI initialization macro system for distributed JNI_OnLoad registration.
//!
//! ## Features
//!
//! - **Distributed initialization**: Register JNI init functions across multiple modules
//! - **Priority-based ordering**: Control execution order with numeric priorities (higher = first)
//! - **Zero boilerplate**: Automatic collection via `inventory` crate
//!
//! ## Example
//!
//! ```rust
//! use jni_macro::jni_onload;
//! use jni::{JNIEnv, JavaVM};
//!
//! #[jni_onload(100)]  // High priority - runs first
//! fn init_driver(env: &mut JNIEnv, vm: &JavaVM) {
//!     // Initialize driver
//! }
//!
//! #[jni_onload(50)]   // Lower priority - runs after
//! fn init_logging(env: &mut JNIEnv, vm: &JavaVM) {
//!     // Initialize logging
//! }
//! ```

// Re-export core types and functions
pub use jni_macro_core::{JniInitFn, JniInitializer, JniMethodRegistration, register_all_jni_methods};

// Re-export the procedural macros
pub use jni_macro_derive::{jni_onload, jni_method};

// Re-export inventory for macro expansion
#[doc(hidden)]
pub use inventory;
