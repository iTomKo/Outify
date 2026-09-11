use std::path::PathBuf;

use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString},
    sys::{jboolean, jstring},
};
use librespot_core::authentication::Credentials;
use oauth2::url::Url;

use crate::session::with_session;

// Checks for cached credentials. Does NOT use Session
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_AuthManager_hasCachedCredentials(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    let dir = match crate::FILES_DIR.get() {
        Some(d) => d,
        None => {
            warn!("FILES_DIR not set for cached credentials check");
            return 0;
        }
    };

    dir.join("credentials.json").is_file() as jboolean
}

// Returns the auth URL for OAuth flow
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_AuthManager_getAuthorizationURL(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let rt = crate::network_rt();

    let auth_url_opt: Option<Url> = match with_session(|session| {
        rt.block_on(async move {
            match crate::oauth::setup_oauth_session(&session) {
                Some(mutex) => {
                    let guard = mutex.lock().await;
                    Some(guard.auth_url().clone())
                }
                None => None,
            }
        })
    }) {
        Ok(val) => val,
        Err(e) => {
            error!("with_session failed for get_authorization_url: {e}");
            None
        }
    };

    let auth_url = match auth_url_opt {
        Some(u) => u,
        None => {
            warn!("oauth session setup failed");
            return std::ptr::null_mut();
        }
    };

    match env.new_string(auth_url.to_string()) {
        Ok(java_str) => java_str.into_raw(),
        Err(e) => {
            warn!("jni new_string failed for auth url: {e:?}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_AuthManager_handleOAuthCode(
    mut env: JNIEnv,
    _this: JObject,
    code: JString,
    _state: JString,
) -> jstring {
    let code: String = match env.get_string(&code) {
        Ok(js) => js.into(),
        Err(e) => {
            error!("jni get_string failed for oauth code: {e}");
            return make_error_json(&env, "authentication", "Failed to read OAuth code");
        }
    };

    let rt = crate::network_rt();

    let session_mutex = match crate::oauth::OAUTH_SESSION.get() {
        Some(m) => m,
        None => {
            error!("oauth session not initialized for handle_oauth_code");
            return make_error_json(&env, "authentication", "OAuth session not initialized");
        }
    };

    let token = match rt.block_on(async {
        let mut guard = session_mutex.lock().await;
        guard.get_access_token(code).await
    }) {
        Ok(tok) => tok,
        Err(e) => {
            error!("get_access_token failed: {e}");
            let err_type = classify_oauth_error(&e);
            return make_error_json(&env, err_type, &e.to_string());
        }
    };

    if let Err(e) = rt.block_on(async {
        let guard = session_mutex.lock().await;
        if let Some(cache) = guard.session.cache() {
            let cred = Credentials::with_access_token(&token.access_token);
            cache.save_credentials(&cred);
        } else {
            warn!("session has no cache for credential persistence");
        }
        Ok::<(), ()>(())
    }) {
        warn!("credential save to cache failed: {e:?}");
    }

    make_success_json(&env)
}

fn make_error_json(env: &JNIEnv, error_type: &str, message: &str) -> jstring {
    let json = format!(
        r#"{{"error":{{"type":"{}","message":"{}"}}}}"#,
        error_type, message
    );
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn make_success_json(env: &JNIEnv) -> jstring {
    match env.new_string(r#"{"success":true}"#) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn classify_oauth_error(err: &librespot_core::Error) -> &'static str {
    let msg = err.to_string().to_lowercase();
    if msg.contains("unavailable") || msg.contains("service") {
        "service_unavailable"
    } else if msg.contains("auth")
        || msg.contains("token")
        || msg.contains("credential")
        || msg.contains("unauthorized")
    {
        "authentication_error"
    } else if msg.contains("rate") {
        "rate_limit"
    } else {
        "unknown"
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_AuthManager_logout")]
pub extern "system" fn logout(_env: JNIEnv, _class: JClass) -> jboolean {
    let mut os_files_dir: PathBuf = crate::FILES_DIR
        .get()
        .expect("Failed to get Files Dir!")
        .to_path_buf();
    os_files_dir.push("credentials.json");

    match std::fs::remove_file(os_files_dir) {
        Ok(_) => {
            info!("oauth credentials removed");
            1
        }
        Err(e) => {
            error!("oauth credential file removal failed: {e}");
            0
        }
    }
}
