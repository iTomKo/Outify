use std::{sync::Mutex, time::Duration};

use jni::{
    JNIEnv,
    objects::{GlobalRef, JClass, JObject, JObjectArray, JString},
    sys::{jboolean, jint, jlong, jobjectArray, jstring},
};
use librespot_connect::{LoadContextOptions, LoadRequestOptions, PlayingTrack};
use librespot_core::SpotifyUri;
use librespot_playback::config::Bitrate;

use crate::{
    outifyuri::OutifyUri,
    spirc::{SpircError, with_spirc},
};

pub static BUFFER_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);
pub static DEVICE_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_spirc_Spirc_initializeSpirc(
    mut env: JNIEnv,
    _this: JClass,
    callback: JObject,
    gapless: jboolean,
    normalisation: jboolean,
    bitrate: jint,
    device_name: JString,
) -> jboolean {
    info!("initializing spirc");

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(rt) => rt,
        None => {
            error!("tokio runtime not available for initialize_spirc");
            return 0;
        }
    };

    let handle = rt.handle().clone();

    let global_callback = match env.new_global_ref(callback) {
        Ok(g) => g,
        Err(e) => {
            error!("jni new_global_ref failed for spirc callback: {e}");
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

    let jvm = crate::JVM.get().unwrap();

    let bitrate = match bitrate {
        320 => Bitrate::Bitrate320,
        160 => Bitrate::Bitrate160,
        96 => Bitrate::Bitrate96,
        _ => Bitrate::Bitrate320,
    };

    handle.spawn(async move {
        let result =
            crate::spirc::initialize_spirc(name, gapless != 0, normalisation != 0, bitrate).await;

        let mut env = match jvm.attach_current_thread() {
            Ok(env) => env,
            Err(e) => {
                error!("jvm attach_current_thread failed for spirc init: {e}");
                return;
            }
        };

        match result {
            Ok(_) => {
                env.call_method(global_callback.as_obj(), "initialized", "()V", &[])
                    .ok();
            }
            Err(_) => {
                env.call_method(global_callback.as_obj(), "failed", "()V", &[])
                    .ok();
            }
        }
    });

    1
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_shutdown")]
pub extern "system" fn shutdown(_env: JNIEnv, _this: JClass) {
    crate::spirc::shutdown()
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_unregisterBufferCallback")]
pub extern "system" fn unregister_buffer_callback(_env: JNIEnv, _this: JClass) {
    let mut lock = BUFFER_CALLBACK.lock().unwrap();
    if let Some(global) = lock.take() {
        drop(global);
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_unregisterDeviceCallback")]
pub extern "system" fn unregister_device_callback(_env: JNIEnv, _this: JClass) {
    let mut lock = DEVICE_CALLBACK.lock().unwrap();
    if let Some(global) = lock.take() {
        drop(global);
    }
}

// Sets the buffer callback, so we can notify UI of spirc buferring
#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_bufferCallback")]
pub extern "system" fn set_buffer_callback(
    env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    let global_callback = match env.new_global_ref(callback) {
        Ok(g) => g,
        Err(e) => {
            error!("jni new_global_ref failed for buffer callback: {e}");
            return 0;
        }
    };

    {
        let mut lock = BUFFER_CALLBACK.lock().unwrap();
        *lock = Some(global_callback);
    }

    1
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_deviceCallback")]
pub extern "system" fn set_device_callback(
    env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    let global_callback = match env.new_global_ref(callback) {
        Ok(g) => g,
        Err(e) => {
            error!("jni new_global_ref failed for device callback: {e}");
            return 0;
        }
    };

    {
        let mut lock = DEVICE_CALLBACK.lock().unwrap();
        *lock = Some(global_callback);
    }

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
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_spirc_Spirc_shuffleLoad")]
pub extern "system" fn shuffle_load(mut env: JNIEnv, _this: JClass, juri: JString) -> jboolean {
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
    let uri_string = outify_uri.to_uri();

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
        let uri_string = outify_uri.to_uri();
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
                Some(PlayingTrack::Uri(outify_uri.to_uri()))
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
pub extern "system" fn repeat_spirc(_env: JNIEnv, _this: JClass, repeat: jboolean, repeat_track: jboolean) -> jboolean {
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
    let uris: Vec<String> = tracks_raw.into_iter().map(|track| track.uri).collect();

    let json = match serde_json::to_string(&uris) {
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

    let uris: Vec<String> = tracks_raw.into_iter().map(|track| track.uri).collect();

    let json = match serde_json::to_string(&uris) {
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

// Resolves passed in JString or fallbacks to users collection
fn resolve_uri_or_collection(env: &mut JNIEnv, juri: JString) -> Result<String, ()> {
    if juri.is_null() {
        let outify_uri = OutifyUri::Liked;
        Ok(outify_uri.to_uri())
    } else {
        match env.get_string(&juri) {
            Ok(js) => {
                let uri: String = js.into();
                let outify_uri = OutifyUri::from_uri(&uri);
                Ok(outify_uri.to_uri())
            }
            Err(e) => {
                warn!("jni get_string failed for resolve_uri: {e}");
                Err(())
            }
        }
    }
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
        Err(e) => match e {
            SpircError::NotInitialized | SpircError::NotCreated => {
                debug!("auto initializing spirc on load failure");
                let rt = match crate::TOKIO_RUNTIME.get() {
                    Some(rt) => rt,
                    None => {
                        error!("tokio runtime not available for auto_init on load: {e}");
                        return 0;
                    }
                };
                rt.spawn(async move {
                    if let Err(e) = crate::spirc::auto_initialize_spirc().await {
                        error!("auto_initialize_spirc failed: {e}");
                    }
                });
                0
            }
            _ => {
                error!("with_spirc session error for load: {e}");
                0
            }
        },
    }
}
