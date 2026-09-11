use std::sync::{Arc, Mutex};

use jni::{JNIEnv, objects::{JClass, JObject}};
use once_cell::sync::OnceCell;

use crate::jni_utils::jni_bridge::JavaCallback;

// PlayerEventCallback:
//   void onTrackChange(String uri, String json)
//   void onPositionUpdate(String uri, long position_ms, String json)
//   void onPlayingStatus(boolean playing)
static PLAYER_CB: OnceCell<Mutex<Option<Arc<JavaCallback>>>> = OnceCell::new();

pub const METHOD_TRACK_CHANGE: usize = 0;
pub const METHOD_POSITION_UPDATE: usize = 1;
pub const METHOD_PLAYING_STATUS: usize = 2;

const PLAYER_METHODS: [(&str, &str); 3] = [
    ("onTrackChange", "(Ljava/lang/String;Ljava/lang/String;)V"),
    ("onPositionUpdate", "(Ljava/lang/String;JLjava/lang/String;)V"),
    ("onPlayingStatus", "(Z)V"),
];

// Returns a cheap clone of the registered player listener
pub fn player_cb() -> Option<Arc<JavaCallback>> {
    let m = PLAYER_CB.get()?;
    m.lock().ok()?.clone()
}

// Registers the track update callback and caches its method IDs
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_playback_AudioEngine_registerPlayerEventListener(
    mut env: JNIEnv,
    _this: JClass,
    callback: JObject,
) {
    let cb = match JavaCallback::register(&mut env, callback, &PLAYER_METHODS) {
        Ok(c) => c,
        Err(e) => {
            error!("jni register failed for player event listener: {e}");
            return;
        }
    };

    let m = PLAYER_CB.get_or_init(|| Mutex::new(None));
    *m.lock().unwrap() = Some(cb);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_playback_AudioEngine_unregisterPlayerEventListener(
    _env: JNIEnv,
    _this: JClass,
) {
    if let Some(m) = PLAYER_CB.get() {
        *m.lock().unwrap() = None;
    }
}