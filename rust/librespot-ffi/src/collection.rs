//! Collection pushes are invalidations, not complete library snapshots.

use std::sync::Mutex;

use librespot_core::{Session, mercury::MercuryResponse};
use tokio::task::JoinHandle;

const TOPIC: &str = "hm://collection/collection/";
static LISTENER: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

pub async fn start(session: &Session) {
    stop();
    // Register before authentication starts delivering Mercury events. Resolve
    // the canonical user ID from the authenticated session when a push arrives.
    let mut updates = session.mercury().listen_for(TOPIC).await;
    let mut shutdown = session.subscribe_shutdown();
    let session = session.clone();
    let listener = tokio::spawn(async move {
        loop {
            tokio::select! {
                biased;
                _ = shutdown.changed() => break,
                update = updates.recv() => {
                    let Some(update) = update else { break };
                    if !session.is_invalid() && tracks_changed(&update, &session.username()) {
                        if let Some(callback) = crate::jni_impl::session::session_cb() {
                            crate::jni_utils::jni_bridge::dispatch(
                                callback,
                                crate::jni_impl::session::METHOD_COLLECTION_CHANGED,
                                vec![],
                            );
                        }
                    }
                }
            }
        }
    });
    *LISTENER.lock().unwrap() = Some(listener);
}

pub fn stop() {
    if let Some(listener) = LISTENER.lock().unwrap().take() {
        listener.abort();
    }
}

fn tracks_changed(update: &MercuryResponse, own_user: &str) -> bool {
    let user = update
        .uri
        .strip_prefix(TOPIC)
        .and_then(|uri| uri.strip_suffix("/json"));
    if own_user.is_empty() || user != Some(own_user) || !(200..300).contains(&update.status_code) {
        return false;
    }
    update.payload.iter().any(|payload| {
        let Ok(json) = serde_json::from_slice::<serde_json::Value>(payload) else {
            return false;
        };
        json.get("items")
            .and_then(serde_json::Value::as_array)
            .is_some_and(|items| {
                items.iter().any(|item| {
                    item.get("type").and_then(serde_json::Value::as_str) == Some("track")
                        && item
                            .get("identifier")
                            .and_then(serde_json::Value::as_str)
                            .is_some_and(|id| !id.is_empty())
                })
            })
    })
}
