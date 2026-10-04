use std::sync::{Arc, Mutex};

use jni::{
    JNIEnv,
    objects::{JClass, JObject},
    sys::jboolean,
};
use once_cell::sync::OnceCell;

use crate::{
    jni_utils::{guard, jni_bridge::JavaCallback},
    session::with_session,
};

// SessionCallback method indices. Must stay in sync with `SessionCallback` in Kotlin.
pub const METHOD_INITIALIZED: usize = 0;
pub const METHOD_SHUTDOWN: usize = 1;
pub const METHOD_RESTARTING: usize = 2;
pub const METHOD_RESTARTED: usize = 3;
pub const METHOD_FAILED: usize = 4;

static SESSION_CALLBACK: OnceCell<Mutex<Option<Arc<JavaCallback>>>> = OnceCell::new();

/// Methods of `SessionCallback`, resolved once at registration time.
pub const CALLBACK_METHODS: [(&str, &str); 5] = [
    ("onInitialized", "()V"),
    ("onShutdown", "()V"),
    ("onRestarting", "()V"),
    ("onRestarted", "()V"),
    ("onFailed", "(Ljava/lang/String;)V"),
];

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
    guard("Session.initializeSession", 0, || {
        let rt = match crate::TOKIO_RUNTIME.get() {
            Some(r) => r,
            None => return 0,
        };

        let global_callback = match JavaCallback::register(&mut env, callback, &CALLBACK_METHODS) {
            Ok(c) => c,
            Err(e) => {
                error!("jni register failed for session callback: {e}");
                return 0;
            }
        };

        crate::jni_impl::session::set_session_callback(global_callback.clone());

        let handle = rt.handle().clone();

        handle.spawn(async move {
            crate::spirc::set_state(crate::spirc::SpircState::Starting);

            match crate::session::initialize_session().await {
                Ok(()) => crate::jni_utils::jni_bridge::dispatch(
                    global_callback,
                    METHOD_INITIALIZED,
                    vec![],
                ),
                Err(e) => {
                    error!("initial session setup failed: {e}");
                    crate::spirc::set_state(crate::spirc::SpircState::Failed);
                    crate::jni_utils::jni_bridge::dispatch(
                        global_callback,
                        METHOD_FAILED,
                        vec![crate::jni_utils::jni_bridge::BridgeArg::Str(e.to_string())],
                    );
                }
            }
        });

        1
    })
}

/// Tears the whole stack down.
///
/// Prefer `Spirc.requestRestart`, which also rebuilds everything but reports
/// when the rebuild finished instead of leaving callers to guess.
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_Session_shutdown(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    guard("Session.shutdown", 0, || {
        crate::spirc::set_state(crate::spirc::SpircState::Stopped);
        let _ = with_session(|session| session.shutdown());
        1
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_Session_unregisterSessionCallback(
    _env: JNIEnv,
    _this: JClass,
) {
    guard("Session.unregisterSessionCallback", (), || {
        crate::jni_impl::session::unregister_session_callback();
    })
}
