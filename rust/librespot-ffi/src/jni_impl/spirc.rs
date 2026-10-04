use std::{
    sync::{Arc, Mutex},
    time::Duration,
};

use jni::{
    JNIEnv,
    objects::{JClass, JObject, JObjectArray, JString},
    sys::{jboolean, jint, jlong, jobjectArray, jstring},
};
use librespot_connect::{LoadContextOptions, LoadRequestOptions, PlayingTrack};
use librespot_core::SpotifyUri;
use librespot_playback::config::Bitrate;
use once_cell::sync::OnceCell;
use serde::Serialize;

use crate::{
    jni_utils::{
        guard,
        jni_bridge::{JavaCallback, dispatch},
    },
    outifyuri::{OutifyUri, UsernameCache, resolve_uri},
    spirc::{SpircError, with_spirc},
};

// BufferCallback: void started(), void stopped()
pub static BUFFER_CB: OnceCell<Mutex<Option<Arc<JavaCallback>>>> = OnceCell::new();
// DeviceCallback: void becameActive(), void becameInactive(), void volumeChanged(int)
pub static DEVICE_CB: OnceCell<Mutex<Option<Arc<JavaCallback>>>> = OnceCell::new();

pub const METHOD_BUFFER_STARTED: usize = 0;
pub const METHOD_BUFFER_STOPPED: usize = 1;

pub const METHOD_DEVICE_ACTIVE: usize = 0;
pub const METHOD_DEVICE_INACTIVE: usize = 1;
pub const METHOD_DEVICE_VOLUME: usize = 2;

pub fn buffer_cb() -> Option<Arc<JavaCallback>> {
    let m = BUFFER_CB.get()?;
    m.lock().ok()?.clone()
}

pub fn device_cb() -> Option<Arc<JavaCallback>> {
    let m = DEVICE_CB.get()?;
    m.lock().ok()?.clone()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_initializeSpirc(
    mut env: JNIEnv,
    _this: JClass,
    callback: JObject,
    gapless: jboolean,
    normalisation: jboolean,
    bitrate: jint,
    crossfade: jint,
    device_name: JString,
) -> jboolean {
    guard("Spirc.initializeSpirc", 0, || {
        info!("initializing spirc");

        let rt = match crate::TOKIO_RUNTIME.get() {
            Some(rt) => rt,
            None => {
                error!("tokio runtime not available for initialize_spirc");
                return 0;
            }
        };

        let handle = rt.handle().clone();

        let global_callback = match JavaCallback::register(
            &mut env,
            callback,
            &[("initialized", "()V"), ("failed", "()V")],
        ) {
            Ok(c) => c,
            Err(e) => {
                error!("jni register failed for spirc callback: {e}");
                return 0;
            }
        };

        let name: String = match env.get_string(&device_name) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("jni get_string failed for device_name: {e}");
                return 0;
            }
        };

        let bitrate = match bitrate {
            320 => Bitrate::Bitrate320,
            160 => Bitrate::Bitrate160,
            96 => Bitrate::Bitrate96,
            _ => Bitrate::Bitrate320,
        };

        let crossfade = Duration::from_millis(crossfade as u64);

        crate::spirc::store_settings(name, gapless != 0, normalisation != 0, bitrate, crossfade);

        handle.spawn(async move {
            // Startup path only. Later restarts go through `request_restart`.
            match crate::spirc::start_runtime().await {
                Ok(()) => {
                    // `initialized` lets Kotlin register its playback callbacks
                    // before it publishes readiness, so no command is accepted
                    // before the callbacks are in place.
                    crate::spirc::set_state(crate::spirc::SpircState::Ready);
                    dispatch(global_callback, 0, vec![])
                }
                Err(e) => {
                    error!("spirc startup failed: {e}");
                    crate::spirc::set_state(crate::spirc::SpircState::Failed);
                    dispatch(global_callback, 1, vec![])
                }
            }
        });

        1
    })
}

/// Records new playback settings and rebuilds the session and Connect runtime.
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_requestRestart(
    mut env: JNIEnv,
    _this: JClass,
    reason: JString,
    gapless: jboolean,
    normalisation: jboolean,
    bitrate: jint,
    crossfade: jint,
    device_name: JString,
    auto_transfer: jboolean,
) -> jboolean {
    guard("Spirc.requestRestart", 0, || {
        let reason: String = if reason.is_null() {
            "unspecified".to_string()
        } else {
            match env.get_string(&reason) {
                Ok(s) => s.into(),
                Err(e) => {
                    warn!("jni get_string failed for restart reason: {e}");
                    "unspecified".to_string()
                }
            }
        };

        let name: String = match env.get_string(&device_name) {
            Ok(s) => s.into(),
            Err(e) => {
                warn!("jni get_string failed for restart device name: {e}");
                "Outify".to_string()
            }
        };

        let bitrate = match bitrate {
            320 => Bitrate::Bitrate320,
            160 => Bitrate::Bitrate160,
            96 => Bitrate::Bitrate96,
            _ => Bitrate::Bitrate320,
        };

        // Settings are recorded before the rebuild starts, so the new runtime is
        // built with exactly what the user just asked for.
        crate::spirc::store_settings(
            name,
            gapless != 0,
            normalisation != 0,
            bitrate,
            Duration::from_millis(crossfade.max(0) as u64),
        );
        crate::spirc::set_auto_transfer(auto_transfer != 0);

        let Some(rt) = crate::TOKIO_RUNTIME.get() else {
            error!("tokio runtime not available for restart");
            return 0;
        };

        rt.handle().spawn(async move {
            // `rebuild_all` publishes every state transition itself.
            if let Err(e) = crate::spirc::request_restart(&reason).await {
                error!("restart request failed: {e}");
            }
        });

        1
    })
}

/// Returns the restart lifecycle snapshot as `key=value` lines.
#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_diagnostics")]
pub extern "system" fn diagnostics(_env: JNIEnv, _this: JClass) -> jstring {
    guard("Spirc.diagnostics", std::ptr::null_mut(), || {
        let text = crate::spirc::diagnostics()
            .into_iter()
            .map(|(k, v)| format!("{k}={v}"))
            .collect::<Vec<_>>()
            .join("\n");

        match _env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                error!("jni new_string failed for diagnostics: {e}");
                std::ptr::null_mut()
            }
        }
    })
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_shutdown")]
pub extern "system" fn shutdown(_env: JNIEnv, _this: JClass) {
    guard("Spirc.shutdown", (), || {
        crate::spirc::set_state(crate::spirc::SpircState::Stopped);
        crate::spirc::shutdown()
    });
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_unregisterBufferCallback")]
pub extern "system" fn unregister_buffer_callback(_env: JNIEnv, _this: JClass) {
    if let Some(m) = BUFFER_CB.get() {
        *m.lock().unwrap() = None;
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_unregisterDeviceCallback")]
pub extern "system" fn unregister_device_callback(_env: JNIEnv, _this: JClass) {
    if let Some(m) = DEVICE_CB.get() {
        *m.lock().unwrap() = None;
    }
}

// Sets the buffer callback, so we can notify UI of spirc buferring
#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_bufferCallback")]
pub extern "system" fn set_buffer_callback(
    mut env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    let cb = match JavaCallback::register(
        &mut env,
        callback,
        &[("started", "()V"), ("stopped", "()V")],
    ) {
        Ok(c) => c,
        Err(e) => {
            error!("jni register failed for buffer callback: {e}");
            return 0;
        }
    };

    let m = BUFFER_CB.get_or_init(|| Mutex::new(None));
    *m.lock().unwrap() = Some(cb);

    1
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_deviceCallback")]
pub extern "system" fn set_device_callback(
    mut env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    let cb = match JavaCallback::register(
        &mut env,
        callback,
        &[
            ("becameActive", "()V"),
            ("becameInactive", "()V"),
            ("volumeChanged", "(I)V"),
        ],
    ) {
        Ok(c) => c,
        Err(e) => {
            error!("jni register failed for device callback: {e}");
            return 0;
        }
    };

    let m = DEVICE_CB.get_or_init(|| Mutex::new(None));
    *m.lock().unwrap() = Some(cb);

    1
}

// Loads a Spotify URI specified
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_load(
    mut env: JNIEnv,
    _this: JClass,
    juri: JString,
    jplaying_track: JString,
) -> jboolean {
    guard("Spirc.load", 0, || {
        let uri = match resolve_uri_or_collection(&mut env, juri) {
            Ok(u) => u,
            Err(()) => return 0 as jboolean,
        };

        let playing_track = match jstring_to_option(&mut env, jplaying_track) {
            Ok(opt) => opt.map(PlayingTrack::Uri),
            Err(()) => return 0 as jboolean,
        };

        let options = LoadRequestOptions {
            start_playing: true,
            playing_track,
            ..Default::default()
        };

        call_spirc_load(uri, options)
    })
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_shuffleLoad")]
pub extern "system" fn shuffle_load(mut env: JNIEnv, _this: JClass, juri: JString) -> jboolean {
    guard("Spirc.shuffleLoad", 0, || {
        let uri = match resolve_uri_or_collection(&mut env, juri) {
            Ok(u) => u,
            Err(()) => return 0 as jboolean,
        };

        let options = LoadRequestOptions {
            start_playing: true,
            context_options: Some(LoadContextOptions::Options(librespot_connect::Options {
                shuffle: true,
                repeat: true,
                repeat_track: false,
            })),
            ..Default::default()
        };

        call_spirc_load(uri, options)
    })
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_localLoad")]
pub extern "system" fn local_load(_env: JNIEnv, _this: JClass, _juri: JString) -> jboolean {
    error!("Using experimental function that does not work as expected!");
    let uri = SpotifyUri::Local {
        artist: "Linkin+Park".to_string(),
        album_title: "From+Zero".to_string(),
        track_title: "Cut+the+Bridge".to_string(),
        duration: Duration::from_secs(209),
    }
    .to_uri();

    let options = LoadRequestOptions {
        start_playing: true,
        context_options: Some(LoadContextOptions::Options(librespot_connect::Options {
            shuffle: true,
            repeat: true,
            repeat_track: false,
        })),
        ..Default::default()
    };

    call_spirc_load(uri, options)
}

// Adds a Spotify URI to queue
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_addToQueue(
    mut env: JNIEnv,
    _this: JClass,
    juri: JString,
) -> jboolean {
    let uri: String = match env.get_string(&juri) {
        Ok(u) => u.into(),
        Err(e) => {
            warn!("jni get_string failed for add_to_queue uri: {e}");
            return 0;
        }
    };

    let outify_uri = OutifyUri::from_uri(&uri);
    let uri_string = match resolve_uri(&outify_uri, &UsernameCache::new()) {
        Ok(uri) => uri,
        Err(()) => return 0,
    };

    let spotify_uri = match SpotifyUri::from_uri(uri_string.as_str()) {
        Ok(uri) => uri,
        Err(e) => {
            warn!("SpotifyUri::from_uri failed for add_to_queue: {e}");
            return 0;
        }
    };

    match with_spirc(|runtime| runtime.add_to_queue(spotify_uri)) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc add_to_queue failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for add_to_queue: {e:?}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_setQueue")]
pub extern "system" fn set_queue(
    mut env: JNIEnv,
    _this: JClass,
    tracks: jobjectArray,
    playing_track: JString,
) -> jboolean {
    let tracks_array = unsafe { JObjectArray::from_raw(tracks) };

    let len = match env.get_array_length(&tracks_array) {
        Ok(l) => l,
        Err(_) => return 0,
    };

    let mut uris: Vec<SpotifyUri> = Vec::with_capacity(len as usize);

    // Resolved lazily: a queue of plain Spotify uris keeps working even while
    // the session is unavailable.
    let username = UsernameCache::new();

    for i in 0..len {
        let obj = match env.get_object_array_element(&tracks_array, i) {
            Ok(o) => o,
            Err(_) => return 0,
        };
        let jstr = JString::from(obj);
        let uri: String = match env.get_string(&jstr) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let outify_uri = OutifyUri::from_uri(&uri);
        let uri_string = match resolve_uri(&outify_uri, &username) {
            Ok(uri) => uri,
            Err(()) => return 0,
        };
        match SpotifyUri::from_uri(&uri_string) {
            Ok(s) => uris.push(s),
            Err(e) => {
                error!("SpotifyUri::from_uri failed for set_queue: {e}");
                return 0;
            }
        }
    }

    let playing_track = if playing_track.is_null() {
        None
    } else {
        match env.get_string(&playing_track) {
            Ok(j) => {
                let uri: String = j.into();
                let outify_uri = OutifyUri::from_uri(&uri);
                resolve_uri(&outify_uri, &username)
                    .ok()
                    .map(PlayingTrack::Uri)
            }
            Err(e) => {
                error!("jni get_string failed for set_queue playing_track: {e}");
                None
            }
        }
    };

    match with_spirc(|runtime| runtime.set_queue(uris, playing_track)) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc set_queue failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for set_queue: {e:?}");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_setVolume(
    _env: JNIEnv,
    _this: JClass,
    jvolume: jint,
) -> jboolean {
    let volume = jvolume as u16;
    match with_spirc(|runtime| runtime.set_volume(volume)) {
        Ok(Ok(())) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc set_volume failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for set_volume: {e:?}");
            0
        }
    }
}

// Activates the Spirc session
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_activate(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    match with_spirc(|runtime| runtime.activate()) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc activate failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for activate: {e:?}");
            0
        }
    }
}

// Transfers the Spirc session to us
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_transfer(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    info!("jni transferring session");
    match with_spirc(|runtime| runtime.transfer()) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc transfer failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for transfer: {e:?}");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_seekTo(
    _env: JNIEnv,
    _this: JClass,
    jposition: jlong,
) -> jboolean {
    match with_spirc(|runtime| runtime.seek_to(jposition as u32)) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc seek_to failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for seek_to: {e:?}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_shuffle")]
pub extern "system" fn shuffle_spirc(_env: JNIEnv, _this: JClass, enabled: jboolean) -> jboolean {
    let enabled = enabled != 0;
    match with_spirc(|runtime| runtime.shuffle(enabled)) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc shuffle failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for shuffle: {e:?}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_repeat")]
pub extern "system" fn repeat_spirc(
    _env: JNIEnv,
    _this: JClass,
    repeat: jboolean,
    repeat_track: jboolean,
) -> jboolean {
    match with_spirc(|runtime| runtime.repeat(repeat != 0, repeat_track != 0)) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc repeat failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for repeat: {e:?}");
            0
        }
    }
}

// Plays the player
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_playerPlay(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    match with_spirc(|runtime| runtime.play()) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc play failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for play: {e:?}");
            0
        }
    }
}

// Pauses the player
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_playerPause(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    match with_spirc(|runtime| runtime.pause()) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc pause failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for pause: {e:?}");
            0
        }
    }
}

// Plays/Pauses the player
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_playerPlayPause(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    match with_spirc(|runtime| runtime.play_pause()) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc play_pause failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for play_pause: {e:?}");
            0
        }
    }
}

// Plays the next track
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_playerNext(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    match with_spirc(|runtime| runtime.next()) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc next failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for next: {e:?}");
            0
        }
    }
}

// Plays the previous track
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_playerPrevious(
    _env: JNIEnv,
    _this: JClass,
) -> jboolean {
    match with_spirc(|runtime| runtime.prev()) {
        Ok(Ok(_)) => 1,
        Ok(Err(e)) => {
            warn!("with_spirc prev failed: {e:?}");
            0
        }
        Err(e) => {
            warn!("with_spirc session error for prev: {e:?}");
            0
        }
    }
}

#[derive(Serialize)]
struct TrackDto {
    uri: String,
    is_queue: bool,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_previousTracks(
    env: JNIEnv,
    _this: JClass,
) -> jstring {
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for prev_tracks");
            return std::ptr::null_mut();
        }
    };

    let tracks_raw =
        match with_spirc(|runtime| rt.block_on(async move { runtime.prev_tracks().await })) {
            Ok(Ok(tracks)) => tracks, // success: outer Ok, inner Ok
            Ok(Err(e)) => {
                error!("with_spirc prev_tracks failed: {e}");
                return std::ptr::null_mut();
            }
            Err(e) => {
                error!("with_spirc session error for prev_tracks: {e:?}");
                return std::ptr::null_mut();
            }
        };

    let tracks: Vec<TrackDto> = tracks_raw
        .into_iter()
        .map(|(track, is_queue)| TrackDto {
            uri: track.uri,
            is_queue,
        })
        .collect();

    let json = match serde_json::to_string(&tracks) {
        Ok(j) => j,
        Err(e) => {
            error!("serde for prev_tracks failed: {e}");
            "[]".to_string()
        }
    };

    match env.new_string(&json) {
        Ok(jni_str) => jni_str.into_raw(),
        Err(e) => {
            error!("jni new_string failed for prev_tracks: {e}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_nextTracks(
    env: JNIEnv,
    _this: JClass,
) -> jstring {
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for next_tracks");
            return std::ptr::null_mut();
        }
    };

    let tracks_raw =
        match with_spirc(|runtime| rt.block_on(async move { runtime.next_tracks().await })) {
            Ok(Ok(tracks)) => tracks,
            Ok(Err(e)) => {
                error!("with_spirc next_tracks failed: {e}");
                return std::ptr::null_mut();
            }
            Err(e) => {
                error!("with_spirc session error for next_tracks: {e:?}");
                return std::ptr::null_mut();
            }
        };

    let tracks: Vec<TrackDto> = tracks_raw
        .into_iter()
        .map(|(track, is_queue)| TrackDto {
            uri: track.uri,
            is_queue,
        })
        .collect();

    let json = match serde_json::to_string(&tracks) {
        Ok(j) => j,
        Err(e) => {
            error!("serde for next_tracks failed: {e}");
            "[]".to_string()
        }
    };

    match env.new_string(&json) {
        Ok(jni_str) => jni_str.into_raw(),
        Err(e) => {
            error!("jni new_string failed for next_tracks: {e}");
            std::ptr::null_mut()
        }
    }
}

/// Resolves a passed-in JString to a Spotify URI
fn resolve_uri_or_collection(env: &mut JNIEnv, juri: JString) -> Result<String, ()> {
    let outify_uri = if juri.is_null() {
        OutifyUri::Liked
    } else {
        match env.get_string(&juri) {
            Ok(js) => {
                let uri: String = js.into();
                OutifyUri::from_uri(&uri)
            }
            Err(e) => {
                warn!("jni get_string failed for resolve_uri: {e}");
                return Err(());
            }
        }
    };

    resolve_uri(&outify_uri, &UsernameCache::new())
}

// Optional JString
fn jstring_to_option(env: &mut JNIEnv, js: JString) -> Result<Option<String>, ()> {
    if js.is_null() {
        Ok(None)
    } else {
        match env.get_string(&js) {
            Ok(s) => Ok(Some(s.into())),
            Err(e) => {
                error!("jni get_string failed for jstring_to_option: {e}");
                Err(())
            }
        }
    }
}

fn call_spirc_load(uri: String, options: LoadRequestOptions) -> jboolean {
    match with_spirc(|runtime| runtime.load(uri, options)) {
        Ok(Ok(_)) => 1 as jboolean,
        Ok(Err(e)) => {
            error!("with_spirc load failed: {e}");
            0 as jboolean
        }
        Err(SpircError::NotInitialized | SpircError::NotCreated) => {
            debug!("dropping load, spirc runtime is not available");
            0 as jboolean
        }
        Err(e) => {
            error!("with_spirc error for load: {e}");
            0 as jboolean
        }
    }
}
