use std::{
    pin::Pin,
    sync::{Mutex, RwLock},
    time::Duration,
};

use crate::{CACHE_DIR, FILES_DIR, TOKIO_RUNTIME};
use librespot_core::{Session, SessionConfig, cache::Cache, config::KEYMASTER_CLIENT_ID};
use once_cell::sync::OnceCell;

pub static SESSION: OnceCell<RwLock<Option<Session>>> = OnceCell::new();

/// Canonical username of the authenticated account.
///
/// `librespot_core::Session` only learns its username while connecting, and a
/// restart replaces the session. The username itself does not change for a
/// given account, so we keep the last known value around. That lets URI
/// resolution keep working while no session is published, instead of failing
/// (or panicking) during the restart window.
static USERNAME: OnceCell<Mutex<Option<String>>> = OnceCell::new();

/// How often a rebuild is attempted before giving up.
const MAX_REBUILD_ATTEMPTS: u32 = 3;

pub fn init_session_container() {
    SESSION.get_or_init(|| RwLock::new(None));
    USERNAME.get_or_init(|| Mutex::new(None));
}

// Initializes the session work further usage
pub async fn initialize_session() -> Result<(), librespot_core::Error> {
    let container = SESSION.get().ok_or_else(|| {
        error!("session container not initialized, call libInit first");
        librespot_core::Error::internal("session container not initialized")
    })?;

    {
        let guard = container.read().unwrap();
        if guard.is_some() {
            warn!("session container already has a session");
        }
    }

    let rt = match TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            warn!("tokio runtime not available for session init");
            return Err(librespot_core::Error::internal(
                "tokio runtime not available",
            ));
        }
    };

    // Geting session cache dir
    let os_cache_dir = match CACHE_DIR.get() {
        Some(dir) => dir.to_path_buf(),
        None => {
            error!("cache dir not set, call libInit first");
            return Err(librespot_core::Error::internal("cache dir not set"));
        }
    };
    let os_files_dir = match FILES_DIR.get() {
        Some(dir) => dir.to_path_buf(),
        None => {
            error!("files dir not set, call libInit first");
            return Err(librespot_core::Error::internal("files dir not set"));
        }
    };
    let cache = match Cache::new(Some(&os_files_dir), None, Some(&os_cache_dir), None) {
        Ok(c) => c,
        Err(e) => {
            error!("cache init failed: {e}");
            return Err(librespot_core::Error::internal(format!(
                "cache init failed: {e}"
            )));
        }
    };
    trace!("cache initialized");

    let handle = rt.handle().clone();
    let session_config = SessionConfig {
        client_id: KEYMASTER_CLIENT_ID.to_owned(),
        ..Default::default()
    };
    let session = Session::with_handle(session_config, Some(cache), handle);

    let mut guard = container.write().unwrap();
    *guard = Some(session.clone());

    start_shutdown_listener(&session);
    debug!("session initialized");
    Ok(())
}

// Connects the already initialized session
pub async fn connect() -> Result<Session, librespot_core::Error> {
    let session = match with_session(|s| s.clone()) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to clone session for connect: {e}");
            return Err(librespot_core::Error::internal(
                "failed to clone session for connect",
            ));
        }
    };

    let credentials = session
        .cache()
        .and_then(|cache| cache.credentials())
        .ok_or_else(|| {
            warn!("no cached credentials for connect");
            librespot_core::Error::unauthenticated("No cached credentials available".to_string())
        })?;

    session.connect(credentials, false).await.map_err(|e| {
        error!("session connect failed: {e}");
        e
    })?;

    debug!("session connected");
    Ok(session.clone())
}

// Listens for session shutdowns
fn start_shutdown_listener(session: &Session) {
    let rt = match TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            warn!("tokio runtime not available for shutdown listener");
            return;
        }
    };

    let session_id = session.session_id();
    let mut shutdown_rx = session.subscribe_shutdown();

    rt.handle().spawn(async move {
        // `changed` fails once the sender is gone, which also means this session
        // is finished. Either way it has to be rebuilt.
        if shutdown_rx.changed().await.is_err() {
            debug!("session shutdown channel closed");
        }

        // Listeners of sessions that a previous rebuild already replaced must not
        // tear down the current session.
        if !is_current_session_id(&session_id) {
            debug!("ignoring shutdown of a stale session");
            return;
        }

        notify_callback("onShutdown");

        if let Err(e) = crate::spirc::request_restart("session lost").await {
            error!("rebuild after session loss gave up: {e}");
        }
    });
}

/// Tears down and recreates the session and the Connect runtime.
pub async fn rebuild_all(reason: &str) -> Result<(), String> {
    info!("rebuilding session and spirc ({reason})");

    crate::spirc::set_state(crate::spirc::SpircState::Rebuilding);
    notify_callback("onRestarting");

    cleanup().await;

    let mut delay = Duration::from_millis(500);
    let mut last_error = "rebuild never ran".to_string();

    for attempt in 1..=MAX_REBUILD_ATTEMPTS {
        if let Err(e) = initialize_session().await {
            warn!("session init attempt {attempt} failed: {e}");
            last_error = e.to_string();
        } else if let Err(e) = crate::spirc::start_runtime().await {
            warn!("spirc init attempt {attempt} failed: {e}");
            last_error = e.to_string();
            // A half-built session is of no use to the next attempt, and its
            // dealer is already spoken for.
            cleanup().await;
        } else {
            // Restore the Connect session before announcing readiness, so that
            // Kotlin accepts commands only once they can actually take effect.
            crate::spirc::reestablish_after_restart();
            crate::spirc::set_state(crate::spirc::SpircState::Ready);
            notify_callback("onRestarted");

            info!("rebuild finished after {attempt} attempt(s)");
            return Ok(());
        }

        if attempt < MAX_REBUILD_ATTEMPTS {
            tokio::time::sleep(delay).await;
            delay = std::cmp::min(delay * 2, Duration::from_secs(8));
        }
    }

    crate::spirc::set_state(crate::spirc::SpircState::Failed);
    error!("rebuild failed after {MAX_REBUILD_ATTEMPTS} attempts: {last_error}");
    notify_callback_with_arg("onFailed", &last_error);
    Err(last_error)
}

/// Whether `session_id` identifies the session currently in the container.
fn is_current_session_id(session_id: &str) -> bool {
    SESSION
        .get()
        .and_then(|container| container.read().ok())
        .and_then(|guard| {
            guard
                .as_ref()
                .map(|session| session.session_id() == session_id)
        })
        .unwrap_or(false)
}

fn notify_callback(method: &str) {
    notify_callback_with_arg(method, "");
}

fn notify_callback_with_arg(method: &str, arg: &str) {
    let callback = match crate::jni_impl::session::session_cb() {
        Some(c) => c,
        None => {
            error!("session callback not set");
            return;
        }
    };

    let (idx, args) = match method {
        "onInitialized" => (crate::jni_impl::session::METHOD_INITIALIZED, vec![]),
        "onShutdown" => (crate::jni_impl::session::METHOD_SHUTDOWN, vec![]),
        "onRestarting" => (crate::jni_impl::session::METHOD_RESTARTING, vec![]),
        "onRestarted" => (crate::jni_impl::session::METHOD_RESTARTED, vec![]),
        "onFailed" => (
            crate::jni_impl::session::METHOD_FAILED,
            vec![crate::jni_utils::jni_bridge::BridgeArg::Str(
                arg.to_string(),
            )],
        ),
        _ => return,
    };

    crate::jni_utils::jni_bridge::dispatch(callback, idx, args);
}

async fn cleanup() {
    if let Some(lock) = SESSION.get() {
        let mut guard = lock.write().unwrap();
        guard.take();
    }

    crate::spirc::teardown_runtime("session cleanup");
}

/// Resolves the canonical username of the authenticated account.
pub fn get_username() -> Result<String, librespot_core::Error> {
    if let Some(container) = SESSION.get() {
        if let Ok(guard) = container.read() {
            if let Some(session) = guard.as_ref() {
                let username = session.username();
                if !username.is_empty() && username != "UNKNOWN" {
                    if let Some(cache) = USERNAME.get() {
                        if let Ok(mut cached) = cache.lock() {
                            *cached = Some(username.clone());
                        }
                    }
                    return Ok(username);
                }
            }
        }
    }

    cached_username().ok_or_else(|| {
        warn!("no username available, session is not authenticated");
        librespot_core::Error::internal("username unavailable")
    })
}

fn cached_username() -> Option<String> {
    USERNAME.get()?.lock().ok()?.clone()
}

// Helper function to retrieve &Session
pub fn with_session<F, R>(f: F) -> Result<R, librespot_core::Error>
where
    F: FnOnce(&Session) -> R,
{
    let container = SESSION
        .get()
        .ok_or_else(|| librespot_core::Error::internal("Session container not initialized"))?;

    let guard = container.read().unwrap();

    let session = guard
        .as_ref()
        .ok_or_else(|| librespot_core::Error::internal("Session not created"))?;

    Ok(f(session))
}

pub async fn with_session_async<F, R>(f: F) -> Result<R, librespot_core::Error>
where
    for<'s> F: FnOnce(&'s librespot_core::Session) -> Pin<Box<dyn Future<Output = R> + 's>>,
{
    let container = SESSION
        .get()
        .ok_or_else(|| librespot_core::Error::internal("Session container not initialized"))?;

    let guard = container.read().unwrap();

    let session = guard
        .as_ref()
        .ok_or_else(|| librespot_core::Error::internal("Session not created"))?;

    Ok(f(session).await)
}
