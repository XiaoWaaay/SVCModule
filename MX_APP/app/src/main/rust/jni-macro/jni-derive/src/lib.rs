use proc_macro::TokenStream;
use quote::quote;
use syn::{parse_macro_input, ItemFn, LitInt, LitStr, Token};
use syn::parse::{Parse, ParseStream};

/// Attribute macro to register JNI initialization functions
///
/// # Example
/// ```
/// use jni_macro::jni_onload;
///
/// #[jni_onload(100)]  // Priority: higher number = execute first
/// fn init_driver(env: &mut JNIEnv, vm: &JavaVM) {
///     // initialization code
/// }
/// ```
#[proc_macro_attribute]
pub fn jni_onload(args: TokenStream, input: TokenStream) -> TokenStream {
    let priority = parse_macro_input!(args as LitInt);
    let func = parse_macro_input!(input as ItemFn);

    let priority_value: u32 = priority.base10_parse()
        .expect("Priority must be a valid u32 number");

    let func_name = &func.sig.ident;
    let func_vis = &func.vis;
    let func_attrs = &func.attrs;
    let func_sig = &func.sig;
    let func_block = &func.block;

    let expanded = quote! {
        #(#func_attrs)*
        #func_vis #func_sig #func_block

        ::jni_macro::inventory::submit! {
            ::jni_macro::JniInitializer {
                priority: #priority_value,
                name: stringify!(#func_name),
                init_fn: #func_name,
            }
        }
    };

    TokenStream::from(expanded)
}

/// Parse arguments for jni_method: (priority, class_path, method_name, signature)
struct JniMethodArgs {
    priority: LitInt,
    class_path: LitStr,
    method_name: LitStr,
    signature: LitStr,
}

impl Parse for JniMethodArgs {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let priority: LitInt = input.parse()?;
        input.parse::<Token![,]>()?;
        let class_path: LitStr = input.parse()?;
        input.parse::<Token![,]>()?;
        let method_name: LitStr = input.parse()?;
        input.parse::<Token![,]>()?;
        let signature: LitStr = input.parse()?;

        Ok(JniMethodArgs {
            priority,
            class_path,
            method_name,
            signature,
        })
    }
}

/// Attribute macro to register JNI native methods
///
/// # Example
/// ```
/// use jni_macro::jni_method;
/// use jni::{JNIEnv, objects::JObject, sys::jboolean};
///
/// #[jni_method(90, "com/example/MyClass", "nativeMethod", "()Z")]
/// pub fn my_native_method(mut env: JNIEnv, obj: JObject) -> jboolean {
///     // implementation
///     1
/// }
/// ```
#[proc_macro_attribute]
pub fn jni_method(args: TokenStream, input: TokenStream) -> TokenStream {
    let args = parse_macro_input!(args as JniMethodArgs);
    let func = parse_macro_input!(input as ItemFn);

    let priority_value: u32 = args.priority.base10_parse()
        .expect("Priority must be a valid u32 number");
    let class_path = args.class_path.value();
    let method_name = args.method_name.value();
    let signature = args.signature.value();

    let func_name = &func.sig.ident;
    let func_vis = &func.vis;
    let func_attrs = &func.attrs;
    let func_sig = &func.sig;
    let func_block = &func.block;

    let expanded = quote! {
        #(#func_attrs)*
        #func_vis #func_sig #func_block

        ::jni_macro::inventory::submit! {
            ::jni_macro::JniMethodRegistration {
                priority: #priority_value,
                class_path: #class_path,
                method_name: #method_name,
                signature: #signature,
                fn_ptr: #func_name as *mut ::std::ffi::c_void,
            }
        }
    };

    TokenStream::from(expanded)
}
