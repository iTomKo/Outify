use std::sync::{
    Arc, Mutex, RwLock,
    atomic::{AtomicBool, AtomicU8, AtomicU32},
};

use jni::objects::JValue;
use librespot_connect::{
    ConnectConfig, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack,
    Spirc,
};
use librespot_core::{Session, SpotifyUri, authentication::Credentials, spclient::TransferRequest};
use librespot_playback::{
    config::{AudioFormat, Bitrate, PlayerConfig},
    mixer::{self, MixerConfig},
    player::{Player, PlayerEvent},
};
use once_cell::sync::OnceCell;
use thiserror::Error;
use tokio::sync::mpsc;

use crate::session::with_session;

#[derive(Error, Debug)]
pub enum SpircError {
    #[error("Spirc not initialized")]
    NotInitialized,

    #[error("Spirc not created")]
    NotCreated,

    #[error("Librespot error: {0}")]
    Librespot(#[from] librespot_core::Error),

    #[error("{0}")]
    Other(String),
}

static SPIRC_RUNTIME: OnceCell<RwLock<Option<SpircRuntime>>> = OnceCell::new();
static CURRENT_TRACK: OnceCell<Mutex<Option<String>>> = OnceCell::new();
pub static BITRATE: OnceCell<Mutex<Bitrate>> = OnceCell::new();
pub static DEVICE_NAME: OnceCell<Mutex<String>> = OnceCell::new();

pub static NORMALISE_AUDIO: AtomicBool = AtomicBool::new(false);
pub static GAPLESS: AtomicBool = AtomicBool::new(false);
static CURRENT_CONTEXT: OnceCell<Mutex<Option<CurrentContext>>> = OnceCell::new();
static IS_PLAYING: AtomicBool = AtomicBool::new(false);
static IS_SHUFFLING: AtomicBool = AtomicBool::new(false);
static REPEAT_MODE: AtomicU8 = AtomicU8::new(RepeatMode::Off as u8);
static LAST_POSITION: AtomicU32 = AtomicU32::new(0);
static IS_DEVICE_ACTIVE: AtomicBool = AtomicBool::new(false);

#[derive(Clone)]
struct CurrentContext {
    uri: String, // Context uri
    options: LoadRequestOptions,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
enum RepeatMode {
    Off = 0,
    All = 1,
    Track = 2,
}

impl RepeatMode {
    fn from_u8(value: u8) -> Self {
        match value {
            1 => Self::All,
            2 => Self::Track,
            _ => Self::Off,
        }
    }

    fn from_states(repeat: bool, repeat_track: bool) -> Self {
        match (repeat, repeat_track) {
            (_, true) => Self::Track,
            (true, false) => Self::All,
            (false, false) => Self::Off,
        }
    }
}

pub fn init_spirc_container() {
    SPIRC_RUNTIME.get_or_init(|| RwLock::new(None));
    CURRENT_TRACK.get_or_init(|| Mutex::new(None));
    CURRENT_CONTEXT.get_or_init(|| Mutex::new(None));
    BITRATE.get_or_init(|| Mutex::new(Bitrate::Bitrate320));
    DEVICE_NAME.get_or_init(|| Mutex::new("Outify".to_string()));
}

pub struct SpircRuntime {
    spirc: Arc<Spirc>,
}

impl SpircRuntime {
    pub async fn new(
        session: &Session,
        credentials: Credentials,
        device_name: String,
        gapless: bool,
        normalisation: bool,
        bitrate: Bitrate,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let player_config = PlayerConfig {
            // TODO: Make configurable from app
            position_update_interval: Some(std::time::Duration::from_millis(5_000)),
            bitrate,
            gapless,
            normalisation,
            ..Default::default()
        };
        let audio_format = AudioFormat::S16;
        let mixer_config = MixerConfig {
            volume_ctrl: librespot_playback::config::VolumeCtrl::Linear,
            ..Default::default()
        };

        let sink_builder =
            librespot_playback::audio_backend::find(None).ok_or("no audio backend available")?;
        let mixer_builder = mixer::find(None).ok_or("no mixer builder available")?;

        let mixer_impl = mixer_builder(mixer_config)?;

        let player = Player::new(
            player_config,
            session.clone(),
            mixer_impl.get_soft_volume(),
            move || sink_builder(None, audio_format),
        );

        let connect_config = ConnectConfig {
            name: device_name,
            ..Default::default()
        };

        let (event_tx, event_rx) = mpsc::channel::<PlayerEvent>(64);

        let (spirc, spirc_future) = Spirc::new(
            connect_config,
            session.clone(),
            credentials,
            player,
            mixer_impl,
            Some(event_tx.clone()),
        )
        .await?;

        let _ = tokio::spawn(spirc_future);

        // Handling received Player Events
        tokio::spawn(async move {
            let mut rx = event_rx;
            while let Some(ev) = rx.recv().await {
                handle_event(ev);
            }
            info!("spirc runtime event receiver closed");
        });

        GAPLESS.store(gapless, std::sync::atomic::Ordering::Relaxed);
        NORMALISE_AUDIO.store(normalisation, std::sync::atomic::Ordering::Relaxed);

        let bitrate_mutex = BITRATE.get().expect("BITRATE not initialized");
        *bitrate_mutex.lock().unwrap() = bitrate;

        info!(
            "spirc runtime initialized with bitrate {}, gapless {}, normalisation {}",
            bitrate as u32, gapless, normalisation
        );

        Ok(Self {
            spirc: Arc::new(spirc),
        })
    }

    pub fn play(&self) -> Result<(), librespot_core::Error> {
        self.spirc.play()
    }

    pub fn play_pause(&self) -> Result<(), librespot_core::Error> {
        self.spirc.play_pause()
    }

    pub fn pause(&self) -> Result<(), librespot_core::Error> {
        self.spirc.pause()
    }

    pub fn next(&self) -> Result<(), librespot_core::Error> {
        self.spirc.next()
    }

    pub fn prev(&self) -> Result<(), librespot_core::Error> {
        self.spirc.prev()
    }

    pub fn load(
        &self,
        uri: String,
        options: LoadRequestOptions,
    ) -> Result<(), librespot_core::Error> {
        let shuffle = IS_SHUFFLING.load(std::sync::atomic::Ordering::Relaxed);
        let repeat_mode =
            RepeatMode::from_u8(REPEAT_MODE.load(std::sync::atomic::Ordering::Relaxed));

        let repeat = repeat_mode.eq(&RepeatMode::All);
        let repeat_track = repeat_mode.eq(&RepeatMode::Track);

        let context_options = LoadContextOptions::Options(Options {
            shuffle,
            repeat,
            repeat_track,
        });

        let modified_options = LoadRequestOptions {
            context_options: Some(context_options),
            start_playing: options.start_playing,
            seek_to: options.seek_to,
            playing_track: options.playing_track,
        };

        let req = LoadRequest::from_context_uri(uri.clone(), modified_options.clone());

        let context = CurrentContext {
            uri,
            options: modified_options,
        };

        if let Some(mutex) = CURRENT_CONTEXT.get() {
            let mut guard = mutex.lock().unwrap();
            *guard = Some(context);
        }

        self.spirc.load(req)
    }

    pub fn add_to_queue(&self, uri: SpotifyUri) -> Result<(), librespot_core::error::Error> {
        self.spirc.add_to_queue(uri)
    }

    pub fn set_queue(
        &self,
        tracks: Vec<SpotifyUri>,
        playing_track: Option<PlayingTrack>,
    ) -> Result<(), librespot_core::Error> {
        self.spirc.set_queue(tracks, playing_track)
    }

    pub fn set_volume(&self, volume: u16) -> Result<(), librespot_core::error::Error> {
        self.spirc.set_volume(volume)
    }

    pub fn activate(&self) -> Result<(), librespot_core::Error> {
        self.spirc.activate()
    }

    pub fn transfer(&self) -> Result<(), librespot_core::Error> {
        info!("transferring session to this device");
        // TODO: Make configurable from Java?
        let options = librespot_core::dealer::protocol::TransferOptions {
            ..Default::default()
        };
        let request = TransferRequest {
            transfer_options: options,
        };
        self.spirc.transfer(Some(request))
    }

    pub fn seek_to(&self, position: u32) -> Result<(), librespot_core::Error> {
        self.spirc.set_position_ms(position)
    }

    pub fn shutdown(&self) {
        let _ = self.spirc.shutdown();
    }

    pub fn shuffle(&self, enabled: bool) -> Result<(), librespot_core::Error> {
        IS_SHUFFLING.store(enabled, std::sync::atomic::Ordering::Relaxed);
        self.spirc.shuffle(enabled)
    }

    /// Skipping to the next track disables the repeating.
    pub fn repeat(&self, repeat: bool, repeat_track: bool) -> Result<(), librespot_core::Error> {
        REPEAT_MODE.store(
            RepeatMode::from_states(repeat, repeat_track) as u8,
            std::sync::atomic::Ordering::Relaxed,
        );
        self.spirc
            .repeat(repeat)
            .and_then(|_| self.spirc.repeat_track(repeat_track))
    }

    pub async fn prev_tracks(
        &self,
    ) -> Result<Vec<librespot_protocol::player::ProvidedTrack>, librespot_core::Error> {
        self.spirc
            .prev_tracks()
            .await
            .ok_or_else(|| librespot_core::Error::internal("Spirc task not available"))
    }

    pub async fn next_tracks(
        &self,
    ) -> Result<Vec<librespot_protocol::player::ProvidedTrack>, librespot_core::Error> {
        self.spirc
            .next_tracks()
            .await
            .ok_or_else(|| librespot_core::Error::internal("Spirc task not available"))
    }

    // Resumes last played context after Spirc shutdown
    pub fn resume_playback(&self) {
        let context = match CURRENT_CONTEXT.get() {
            Some(c) => match c.lock().unwrap().clone() {
                Some(c) => c,
                None => return,
            },
            None => return,
        };

        info!("resuming playback after reconnect");

        // Starting from latest recorded track
        let last_uri = match current_track() {
            Some(l) => l,
            None => {
                // Using the context default
                context.uri.clone()
            }
        };

        let start_playing =
            IS_PLAYING.load(std::sync::atomic::Ordering::Relaxed) && context.options.start_playing;
        let seek_to = LAST_POSITION.load(std::sync::atomic::Ordering::Relaxed);
        let shuffle = IS_SHUFFLING.load(std::sync::atomic::Ordering::Relaxed);
        let repeat_mode =
            RepeatMode::from_u8(REPEAT_MODE.load(std::sync::atomic::Ordering::Relaxed));

        let repeat = repeat_mode.eq(&RepeatMode::All);
        let repeat_track = repeat_mode.eq(&RepeatMode::Track);

        let context_options = LoadContextOptions::Options(Options {
            shuffle,
            repeat,
            repeat_track,
        });

        let options = LoadRequestOptions {
            context_options: Some(context_options),
            playing_track: Some(librespot_connect::PlayingTrack::Uri(last_uri)),
            start_playing,
            seek_to,
        };

        let req = LoadRequest::from_context_uri(context.uri.to_string(), options);
        if let Err(e) = self.spirc.load(req) {
            error!("resume after reconnect load failed: {e}");
        }
    }

    pub fn cleanup(&self) {
        self.shutdown();

        let lock = SPIRC_RUNTIME.get_or_init(|| RwLock::new(None));
        let mut guard = lock.write().unwrap();
        if let Some(spirc) = guard.take() {
            spirc.shutdown();
        }
    }
}

// Handles each player event accordingly
fn handle_event(event: PlayerEvent) {
    info!("handling player event: {event:#?}");

    match event {
        PlayerEvent::Playing {
            play_request_id: _,
            ref track_id,
            position_ms,
        } => {
            IS_PLAYING.store(true, std::sync::atomic::Ordering::Relaxed);
            LAST_POSITION.store(position_ms, std::sync::atomic::Ordering::Relaxed);

            update_current_track(track_id.clone());

            crate::jni_utils::playback::on_player_position_update(position_ms, track_id.clone());
            crate::jni_utils::playback::on_player_status(true);
        }

        PlayerEvent::TrackChanged { audio_item } => {
            LAST_POSITION.store(0, std::sync::atomic::Ordering::Relaxed);
            crate::jni_utils::playback::on_player_track_update(audio_item.track_id.clone());
        }

        PlayerEvent::Paused {
            play_request_id: _,
            ref track_id,
            position_ms,
        } => {
            IS_PLAYING.store(false, std::sync::atomic::Ordering::Relaxed);
            LAST_POSITION.store(position_ms, std::sync::atomic::Ordering::Relaxed);

            update_current_track(track_id.clone());

            crate::jni_utils::playback::on_player_position_update(position_ms, track_id.clone());
            crate::jni_utils::playback::on_player_status(false);
        }

        PlayerEvent::Seeked {
            play_request_id: _,
            track_id,
            position_ms,
        } => {
            LAST_POSITION.store(position_ms, std::sync::atomic::Ordering::Relaxed);

            update_current_track(track_id.clone());
            crate::jni_utils::playback::on_player_position_update(position_ms, track_id.clone());
        }

        PlayerEvent::PositionChanged {
            play_request_id: _,
            track_id,
            position_ms,
        } => {
            LAST_POSITION.store(position_ms, std::sync::atomic::Ordering::Relaxed);

            update_current_track(track_id.clone());
            crate::jni_utils::playback::on_player_position_update(position_ms, track_id.clone());
        }
        PlayerEvent::TimeToPreloadNextTrack {
            play_request_id: _,
            track_id,
        } => {
            info!("preloading track {track_id}");
        }
        PlayerEvent::AddedToQueue { track_id } => {
            info!("track queued: {track_id}");
        }
        PlayerEvent::BufferStart {} => {
            info!("buffering started");
            notify_buffer_state("started".to_string());
        }
        PlayerEvent::BufferStop {} => {
            notify_buffer_state("stopped".to_string());
        }
        PlayerEvent::SessionClientChanged {
            client_id,
            client_name,
            client_brand_name,
            client_model_name,
        } => {
            info!(
                "Session client changed: {} ({}) from {} {}",
                client_id, client_name, client_brand_name, client_model_name
            );

            let session = match with_session(|s| s.clone()) {
                Ok(s) => s,
                Err(_) => {
                    error!("session not available for device state check");
                    return;
                }
            };

            let our_device_id = session.device_id();
            let is_now_active = client_id == our_device_id || client_brand_name.is_empty();

            IS_DEVICE_ACTIVE.store(is_now_active, std::sync::atomic::Ordering::Relaxed);
            notify_device_state(is_now_active);
        }

        PlayerEvent::SessionConnected {
            connection_id: _,
            user_name: _,
        } => {
            notify_device_state(true);
        }
        PlayerEvent::SessionDisconnected {
            connection_id: _,
            user_name: _,
        } => {
            notify_device_state(false);
        }
        PlayerEvent::VolumeChanged { volume } => {
            notify_device_volume(volume);
        }
        _ => {
            // Not yet implemented
        }
    }
}

fn update_current_track(uri: SpotifyUri) {
    if let Some(mutex) = CURRENT_TRACK.get() {
        let mut guard = mutex.lock().unwrap();
        *guard = Some(uri.to_string());
    }
}

pub async fn auto_initialize_spirc() -> Result<(), SpircError> {
    let gapless = GAPLESS.load(std::sync::atomic::Ordering::Relaxed);
    let normalisation = NORMALISE_AUDIO.load(std::sync::atomic::Ordering::Relaxed);
    let bitrate_mutex = BITRATE.get().expect("BITRATE not initialized");
    let bitrate = *bitrate_mutex.lock().unwrap();
    let device_name = DEVICE_NAME
        .get()
        .map(|m| m.lock().unwrap().clone())
        .unwrap_or("Outify".to_string());

    initialize_spirc(device_name, gapless, normalisation, bitrate).await
}

pub async fn initialize_spirc(
    device_name: String,
    gapless: bool,
    normalisation: bool,
    bitrate: Bitrate,
) -> Result<(), SpircError> {
    debug!("initializing spirc runtime");

    let lock = SPIRC_RUNTIME.get_or_init(|| RwLock::new(None));

    {
        let read_guard = lock.read().unwrap();
        if read_guard.is_some() {
            warn!("spirc already initialized");
        }
    }

    let session = with_session(|s| s.clone()).map_err(|e| {
        error!("failed to clone session for spirc init: {e}");
        SpircError::Other(format!("failed to clone session for spirc init: {e}"))
    })?;

    if session.cache().is_none() {
        error!("session cache missing for spirc init");
        return Err(SpircError::Other(
            "session cache missing for spirc init".to_string(),
        ));
    }

    let cache = session.cache().unwrap();
    let credentials = cache.credentials().ok_or_else(|| {
        error!("cached credentials missing for spirc init");
        SpircError::Other("cached credentials missing for spirc init".to_string())
    })?;

    if let Some(name_mutex) = DEVICE_NAME.get() {
        *name_mutex.lock().unwrap() = device_name.clone();
    }

    let runtime = SpircRuntime::new(
        &session,
        credentials,
        device_name,
        gapless,
        normalisation,
        bitrate,
    )
    .await
    .map_err(|e| SpircError::Other(e.to_string()))?;

    let mut guard = lock.write().unwrap();
    *guard = Some(runtime);

    debug!("spirc runtime initialized");

    Ok(())
}

// Notifies UI of buffer state with given method
// TODO: Optimize threads
fn notify_buffer_state(method: String) {
    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => {
            error!("jvm not available for buffer callback");
            return;
        }
    };

    let callback_opt = {
        let lock = crate::jni_impl::spirc::BUFFER_CALLBACK.lock().unwrap();
        lock.clone()
    };

    if let Some(callback) = callback_opt {
        let mut env = match jvm.attach_current_thread() {
            Ok(env) => env,
            Err(e) => {
                error!("thread attach for buffer callback failed: {e}");
                return;
            }
        };

        if let Err(e) = env.call_method(callback.as_obj(), method, "()V", &[]) {
            log::error!("buffer callback invocation failed: {e}");
        }
    }
}

pub fn notify_device_state(is_active: bool) {
    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => {
            error!("jvm not available for device callback");
            return;
        }
    };

    let callback_opt = {
        let lock = crate::jni_impl::spirc::DEVICE_CALLBACK.lock().unwrap();
        lock.clone()
    };

    if let Some(callback) = callback_opt {
        let method = if is_active {
            "becameActive"
        } else {
            "becameInactive"
        };

        std::thread::spawn(move || {
            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(e) => {
                    error!("jvm attach failed for device active callback: {e}");
                    return;
                }
            };

            if let Err(e) = env.call_method(callback.as_obj(), method, "()V", &[]) {
                log::error!("device callback {method} invocation failed: {e}");
            }
        });
    }
}

pub fn notify_device_volume(volume: u16) {
    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => {
            error!("jvm not available for volume callback");
            return;
        }
    };

    let callback_opt = {
        let lock = crate::jni_impl::spirc::DEVICE_CALLBACK.lock().unwrap();
        lock.clone()
    };

    if let Some(callback) = callback_opt {
        std::thread::spawn(move || {
            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(e) => {
                    error!("jvm attach failed for device volume callback: {e}");
                    return;
                }
            };

            if let Err(e) = env.call_method(
                callback.as_obj(),
                "volumeChanged",
                "(I)V",
                &[JValue::Int(volume as i32)],
            ) {
                log::error!("volume callback invocation failed: {e}");
            }
        });
    }
}

pub fn current_track() -> Option<String> {
    if let Some(uri) = CURRENT_TRACK.get() {
        return uri.lock().unwrap().clone();
    }
    None
}

pub fn shutdown() {
    let _ = with_spirc(|spirc| {
        spirc.shutdown();
    });

    let lock = SPIRC_RUNTIME.get_or_init(|| RwLock::new(None));
    let mut guard = lock.write().unwrap();
    *guard = None;

    info!("spirc runtime shut down");
}

pub fn with_spirc<F, R>(f: F) -> Result<R, SpircError>
where
    F: FnOnce(&SpircRuntime) -> R,
{
    let container = SPIRC_RUNTIME.get().ok_or(SpircError::NotInitialized)?;

    let guard = container.read().unwrap();
    let runtime = guard.as_ref().ok_or(SpircError::NotCreated)?;

    Ok(f(runtime))
}
