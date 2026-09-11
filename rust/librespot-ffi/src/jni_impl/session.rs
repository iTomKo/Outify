use std::sync::{Arc, Mutex};

use jni::{
    JNIEnv,
    objects::{JClass, JObject},
    sys::jboolean,
};
use once_cell::sync::OnceCell;

use crate::{
    jni_utils::jni_bridge::JavaCallback,
    session::with_session,
};

// SessionCallback: void onInitialized(), void onShutdown(), void onAutoRestart()
static SESSION_CALLBACK: OnceCell<Mutex<Option<Arc<JavaCallback>>>> = OnceCell::new();

pub fn session_cb() -> Option<Arc<JavaCallback>> {
    let m = SESSION_CALLBACK.get()?;
    m.lock().ok()?.clone()
}

pub fn set_session_callback(callback: Arc<JavaCallback>) {
    let m = SESSION_CALLBACK.get_or_init(|| Mutex::new(None));
    *m.lock().unwrap() = Some(callback);
}

pub fn unregister_session_callback() {
    if let Some(m) = SESSION_CALLBACK.get() {
        *m.lock().unwrap() = None;
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_Session_initializeSession(
    mut env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => return 0,
    };

    let global_callback = match JavaCallback::register(
        &mut env,
        callback,
        &[("onInitialized", "()V"), ("onShutdown", "()V"), ("onAutoRestart", "()V")],
    ) {
        Ok(c) => c,
        Err(_) => return 0,
    };

    crate::jni_impl::session::set_session_callback(global_callback.clone());

    let handle = rt.handle().clone();

    handle.spawn(async move {
        crate::session::initialize_session().await;
        crate::jni_utils::jni_bridge::dispatch(global_callback, 0, vec![]);
    });

    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_Session_shutdown(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    let _ = with_session(|session| session.shutdown());
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_Session_unregisterSessionCallback(
    _env: JNIEnv,
    _this: JClass,
) {
    crate::jni_impl::session::unregister_session_callback();
}