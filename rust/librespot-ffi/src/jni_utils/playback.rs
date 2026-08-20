use jni::{
    objects::{GlobalRef, JValue},
    sys::jboolean,
};
use librespot_core::SpotifyUri;
use librespot_metadata::Metadata;

use crate::session::with_session;

// TODO: Optimize thread attachments, JNI calls in total

// Updates the Outify track
pub fn on_player_track_update(track_id: SpotifyUri) {
    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => {
            error!("jvm not initialized for on_player_track_update");
            return;
        }
    };

    let guard = crate::jni_impl::playback::PLAYER_EVENT_LISTENER.lock().unwrap();
    let listener_ref: GlobalRef = match &*guard {
        Some(r) => r.clone(),
        None => {
            error!("listener not set for on_player_track_update");
            return;
        }
    };
    drop(guard);

    tokio::spawn({
        let session = match with_session(|s| s.clone()) {
            Ok(s) => s,
            Err(e) => {
                error!("with_session failed for on_player_track_update: {e}");
                return;
            }
        };
        async move {
            let json = match &track_id {
                SpotifyUri::Track { .. } => {
                    match librespot_metadata::Track::get(&session, &track_id).await {
                        Ok(metadata) => {
                            let track = crate::metadata::track::TrackJson::from(&metadata);
                            match serde_json::to_string(&track) {
                                Ok(s) => s,
                                Err(e) => {
                                    error!("serde for track json failed on on_player_track_update: {e}");
                                    return;
                                }
                            }
                        }
                        Err(e) => {
                            error!("track metadata fetch failed for on_player_track_update: {e}");
                            return;
                        }
                    }
                }
                SpotifyUri::Episode { .. } => {
                    match librespot_metadata::Episode::get(&session, &track_id).await {
                        Ok(metadata) => {
                            let episode = crate::metadata::podcast::EpisodeJson::from(&metadata);
                            match serde_json::to_string(&episode) {
                                Ok(s) => s,
                                Err(e) => {
                                    error!("serde for episode json failed on on_player_track_update: {e}");
                                    return;
                                }
                            }
                        }
                        Err(e) => {
                            error!("episode metadata fetch failed for on_player_track_update: {e}");
                            return;
                        }
                    }
                }
                _ => {
                    warn!("on_player_track_update: unsupported uri type for {track_id}");
                    return;
                }
            };

            let mut env = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(e) => {
                    error!("jvm attach failed for on_player_track_update: {e}");
                    return;
                }
            };

            let j_uri = match env.new_string(&track_id.to_uri()) {
                Ok(s) => s,
                Err(e) => {
                    error!("jni new_string for uri failed on on_player_track_update: {e}");
                    return;
                }
            };
            let j_json = match env.new_string(&json) {
                Ok(s) => s,
                Err(e) => {
                    error!("jni new_string for json failed on on_player_track_update: {e}");
                    return;
                }
            };

            if let Err(e) = env.call_method(
                listener_ref.as_obj(),
                "onTrackChange",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&j_uri), JValue::Object(&j_json)],
            ) {
                error!("on_track_change callback failed: {e:?}");
            }

            if let Ok(true) = env.exception_check() {
                env.exception_describe().ok();
                env.exception_clear().ok();
            }
        }
    });
}

// Updates the Outify player position
pub fn on_player_position_update(position_ms: u32, audio_id: SpotifyUri) {
    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => {
            error!("jvm not initialized for on_player_position_update");
            return;
        }
    };

    let guard = crate::jni_impl::playback::PLAYER_EVENT_LISTENER.lock().unwrap();
    let listener_ref: GlobalRef = match &*guard {
        Some(r) => r.clone(),
        None => {
            error!("listener not set for on_player_position_update");
            return;
        }
    };
    drop(guard);

    tokio::spawn({
        let session = match with_session(|s| s.clone()) {
            Ok(s) => s,
            Err(e) => {
                error!("with_session failed for on_player_position_update: {e}");
                return;
            }
        };

        async move {
            let json = match &audio_id {
                SpotifyUri::Track { .. } => {
                    match librespot_metadata::Track::get(&session, &audio_id).await {
                        Ok(metadata) => {
                            let track = crate::metadata::track::TrackJson::from(&metadata);
                            match serde_json::to_string(&track) {
                                Ok(s) => s,
                                Err(e) => {
                                    error!("serde for track json failed on on_player_position_update: {e}");
                                    return;
                                }
                            }
                        }
                        Err(e) => {
                            error!("track metadata fetch failed for on_player_position_update: {e}");
                            return;
                        }
                    }
                }
                SpotifyUri::Episode { .. } => {
                    match librespot_metadata::Episode::get(&session, &audio_id).await {
                        Ok(metadata) => {
                            let episode = crate::metadata::podcast::EpisodeJson::from(&metadata);
                            match serde_json::to_string(&episode) {
                                Ok(s) => s,
                                Err(e) => {
                                    error!("serde for episode json failed on on_player_position_update: {e}");
                                    return;
                                }
                            }
                        }
                        Err(e) => {
                            error!("episode metadata fetch failed for on_player_position_update: {e}");
                            return;
                        }
                    }
                }
                _ => {
                    warn!("on_player_position_update: unsupported uri type for {audio_id}");
                    return;
                }
            };

            let mut env = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(e) => {
                    error!("jvm attach failed for on_player_position_update: {e}");
                    return;
                }
            };

            let j_uri = match env.new_string(&audio_id.to_uri()) {
                Ok(s) => s,
                Err(e) => {
                    error!("jni new_string for uri failed on on_player_position_update: {e}");
                    return;
                }
            };
            let j_json = match env.new_string(&json) {
                Ok(s) => s,
                Err(e) => {
                    error!("jni new_string for json failed on on_player_position_update: {e}");
                    return;
                }
            };

            if let Err(e) = env.call_method(
                listener_ref.as_obj(),
                "onPositionUpdate",
                "(Ljava/lang/String;JLjava/lang/String;)V",
                &[
                    JValue::Object(&j_uri),
                    JValue::Long(position_ms as i64),
                    JValue::Object(&j_json),
                ],
            ) {
                error!("on_position_update callback failed: {e:?}");
            }

            if let Ok(true) = env.exception_check() {
                env.exception_describe().ok();
                env.exception_clear().ok();
            }
        }
    });
}

// Updates the Outify playing status
pub fn on_player_status(playing: bool) {
    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => {
            error!("jvm not initialized for on_player_status");
            return;
        }
    };

    let guard = crate::jni_impl::playback::PLAYER_EVENT_LISTENER.lock().unwrap();
    let listener_ref: GlobalRef = match &*guard {
        Some(r) => r.clone(),
        None => {
            error!("listener not set for on_player_status");
            return;
        }
    };

    drop(guard);

    tokio::spawn(async move {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                error!("jvm attach failed for on_player_status: {e}");
                return;
            }
        };

        if let Err(e) = env.call_method(
            listener_ref.as_obj(),
            "onPlayingStatus",
            "(Z)V",
            &[JValue::Bool(playing as jboolean)],
        ) {
            error!("on_player_status callback failed: {e:?}");
        }

        if let Ok(true) = env.exception_check() {
            env.exception_describe().ok();
            env.exception_clear().ok();
        }
    });
}
