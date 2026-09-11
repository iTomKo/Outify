#[macro_use]
extern crate log;

pub mod jni_impl;
pub mod jni_utils;
pub mod metadata;

pub mod oauth;
pub mod outifyuri;
pub mod session;
pub mod spclient;
pub mod spotify;
pub mod types;

mod playback;
mod profile;
mod spirc;

use std::path::PathBuf;

// Exposing required librespot structs
pub use librespot_core::authentication::Credentials;
pub use librespot_playback::audio_backend::android::{AndroidSink, PcmCallback};
pub use librespot_playback::config::AudioFormat;

use once_cell::sync::OnceCell;

use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{JClass, JObject, JString};
use jni::sys::jint;

use tokio::runtime::Runtime;

static TOKIO_RUNTIME: OnceCell<Runtime> = OnceCell::new();
static NETWORK_RUNTIME: OnceCell<Runtime> = OnceCell::new();
static JVM: OnceCell<JavaVM> = OnceCell::new();

static FILES_DIR: OnceCell<PathBuf> = OnceCell::new();
static CACHE_DIR: OnceCell<PathBuf> = OnceCell::new();

pub(crate) fn network_rt() -> &'static Runtime {
    NETWORK_RUNTIME.get().expect("NETWORK_RUNTIME not initialized")
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::os::raw::c_void) -> jint {
    let _ = JVM.set(vm);
    jni::sys::JNI_VERSION_1_6
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_LibrespotFfi_libInit(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
    client_id: JString,
    client_secret: JString,
) {
    let jvm = env.get_java_vm().unwrap();

    // Setup TOKIO
    TOKIO_RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("outify-core")
            .worker_threads(4)
            .max_blocking_threads(8)
            .build()
            .expect("Failed to create Tokio runtime!")
    });

    NETWORK_RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("outify-net")
            .worker_threads(2)
            .max_blocking_threads(32)
            .build()
            .expect("Failed to create network runtime!")
    });

    // Initialize logger
    crate::jni_utils::logger::AndroidLogger::init(jvm, log::LevelFilter::Debug).unwrap();

    unsafe {
        std::env::set_var("RUST_BACKTRACE", "1");
    }
    std::panic::set_hook(Box::new(|info| {
        log::error!("panic: {info}");
    }));

    let files_dir = crate::jni_utils::folders::get_android_dir(&mut env, &context, "getFilesDir");
    let cache_dir = crate::jni_utils::folders::get_android_dir(&mut env, &context, "getCacheDir");

    if FILES_DIR.set(files_dir).is_err() {
        error!("files dir already set");
    }
    if CACHE_DIR.set(cache_dir).is_err() {
        error!("cache dir already set");
    }

    session::init_session_container();
    spirc::init_spirc_container();

    // Getting Spotify credentials
    let client_id: String = match env.get_string(&client_id) {
        Ok(i) => i.into(),
        Err(e) => {
            error!("failed to read client_id from jni: {e}");
            return;
        }
    };

    let client_secret: String = match env.get_string(&client_secret) {
        Ok(i) => i.into(),
        Err(e) => {
            error!("failed to read client_secret from jni: {e}");
            return;
        }
    };

    spotify::client::init_client(client_id, client_secret);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_LibrespotFfi_updateClientCredentials(
    mut env: JNIEnv,
    _class: JClass,
    client_id: JString,
    client_secret: JString,
) {
    let client_id: String = match env.get_string(&client_id) {
        Ok(i) => i.into(),
        Err(e) => {
            error!("failed to read client_id from jni: {e}");
            return;
        }
    };

    let client_secret: String = match env.get_string(&client_secret) {
        Ok(i) => i.into(),
        Err(e) => {
            error!("failed to read client_secret from jni: {e}");
            return;
        }
    };

    spotify::client::update_client(client_id, client_secret);
}
