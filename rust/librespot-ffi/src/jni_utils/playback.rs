use std::sync::Mutex;

use librespot_core::SpotifyUri;
use librespot_metadata::Metadata;
use once_cell::sync::OnceCell;

use crate::session::with_session;

use super::jni_bridge::{BridgeArg, dispatch};
use crate::jni_impl::playback::{
    METHOD_PLAYING_STATUS, METHOD_POSITION_UPDATE, METHOD_TRACK_CHANGE, player_cb,
};

static TRACK_JSON_CACHE: OnceCell<Mutex<Option<(String, String)>>> = OnceCell::new();

fn cache_track_json(uri: &str, json: &str) {
    let m = TRACK_JSON_CACHE.get_or_init(|| Mutex::new(None));
    *m.lock().unwrap() = Some((uri.to_string(), json.to_string()));
}

fn cached_track_json(uri: &str) -> Option<String> {
    let m = TRACK_JSON_CACHE.get()?;
    let guard = m.lock().ok()?;
    guard
        .as_ref()
        .filter(|(cached_uri, _)| cached_uri == uri)
        .map(|(_, json)| json.clone())
}

async fn render_track_json(session: &librespot_core::Session, audio_id: &SpotifyUri) -> Option<String> {
    match audio_id {
        SpotifyUri::Track { .. } => {
            let metadata = librespot_metadata::Track::get(session, audio_id).await.ok()?;
            let track = crate::metadata::track::TrackJson::from(&metadata);
            serde_json::to_string(&track).ok()
        }
        SpotifyUri::Episode { .. } => {
            let metadata = librespot_metadata::Episode::get(session, audio_id).await.ok()?;
            let episode = crate::metadata::podcast::EpisodeJson::from(&metadata);
            serde_json::to_string(&episode).ok()
        }
        _ => None,
    }
}

// Updates the Outify track
pub fn on_player_track_update(track_id: SpotifyUri) {
    let cb = match player_cb() {
        Some(c) => c,
        None => {
            error!("listener not set for on_player_track_update");
            return;
        }
    };

    let track_uri = track_id.to_uri();
    tokio::spawn(async move {
        let session = match with_session(|s| s.clone()) {
            Ok(s) => s,
            Err(e) => {
                error!("with_session failed for on_player_track_update: {e}");
                return;
            }
        };

        let json = match render_track_json(&session, &track_id).await {
            Some(j) => j,
            None => {
                error!("metadata render failed for on_player_track_update: {track_id}");
                return;
            }
        };

        cache_track_json(&track_uri, &json);
        dispatch(
            cb,
            METHOD_TRACK_CHANGE,
            vec![BridgeArg::Str(track_uri), BridgeArg::Str(json)],
        );
    });
}

// Updates the Outify player position
pub fn on_player_position_update(position_ms: u32, audio_id: SpotifyUri) {
    let cb = match player_cb() {
        Some(c) => c,
        None => {
            error!("listener not set for on_player_position_update");
            return;
        }
    };

    let track_uri = audio_id.to_uri();

    // Fast path: metadata already rendered for this track, no network involved
    if let Some(json) = cached_track_json(&track_uri) {
        dispatch(
            cb,
            METHOD_POSITION_UPDATE,
            vec![
                BridgeArg::Str(track_uri),
                BridgeArg::Long(position_ms as i64),
                BridgeArg::Str(json),
            ],
        );
        return;
    }

    tokio::spawn(async move {
        let session = match with_session(|s| s.clone()) {
            Ok(s) => s,
            Err(e) => {
                error!("with_session failed for on_player_position_update: {e}");
                return;
            }
        };

        let json = match render_track_json(&session, &audio_id).await {
            Some(j) => j,
            None => {
                error!("metadata render failed for on_player_position_update: {audio_id}");
                return;
            }
        };

        cache_track_json(&track_uri, &json);
        dispatch(
            cb,
            METHOD_POSITION_UPDATE,
            vec![
                BridgeArg::Str(track_uri),
                BridgeArg::Long(position_ms as i64),
                BridgeArg::Str(json),
            ],
        );
    });
}

// Updates the Outify playing status
pub fn on_player_status(playing: bool) {
    let cb = match player_cb() {
        Some(c) => c,
        None => {
            error!("listener not set for on_player_status");
            return;
        }
    };

    dispatch(cb, METHOD_PLAYING_STATUS, vec![BridgeArg::Bool(playing)]);
}
