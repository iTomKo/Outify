use std::{
    sync::{
        Arc, Mutex, OnceLock, RwLock,
        atomic::{AtomicBool, AtomicU8, AtomicU32, Ordering},
    },
    time::Duration,
};

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

/// Lifecycle of the Connect runtime, mirrored into Kotlin so that callers can
/// tell "not ready yet" apart from "broken".
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SpircState {
    Stopped,
    Starting,
    Ready,
    Rebuilding,
    Failed,
}

impl SpircState {
    fn from_u8(value: u8) -> Self {
        match value {
            1 => Self::Starting,
            2 => Self::Ready,
            3 => Self::Rebuilding,
            4 => Self::Failed,
            _ => Self::Stopped,
        }
    }

    fn as_u8(self) -> u8 {
        match self {
            Self::Stopped => 0,
            Self::Starting => 1,
            Self::Ready => 2,
            Self::Rebuilding => 3,
            Self::Failed => 4,
        }
    }
}

/// `SpircState::Stopped.as_u8()`, usable in a `static` initializer.
const STOPPED: u8 = 0;

static SPIRC_RUNTIME: OnceCell<RwLock<Option<SpircRuntime>>> = OnceCell::new();
static CURRENT_TRACK: OnceCell<Mutex<Option<String>>> = OnceCell::new();
pub static BITRATE: OnceCell<Mutex<Bitrate>> = OnceCell::new();
pub static DEVICE_NAME: OnceCell<Mutex<String>> = OnceCell::new();

pub static NORMALISE_AUDIO: AtomicBool = AtomicBool::new(false);
pub static GAPLESS: AtomicBool = AtomicBool::new(false);
pub static CROSSFADE: AtomicU32 = AtomicU32::new(0);
static AUTO_TRANSFER: AtomicBool = AtomicBool::new(true);
static CURRENT_CONTEXT: OnceCell<Mutex<Option<CurrentContext>>> = OnceCell::new();
static IS_PLAYING: AtomicBool = AtomicBool::new(false);
static IS_SHUFFLING: AtomicBool = AtomicBool::new(false);
static REPEAT_MODE: AtomicU8 = AtomicU8::new(RepeatMode::Off as u8);
static LAST_POSITION: AtomicU32 = AtomicU32::new(0);
static IS_DEVICE_ACTIVE: AtomicBool = AtomicBool::new(false);

static STATE: AtomicU8 = AtomicU8::new(STOPPED);

static RESTART_LOCK: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();

static RESTART_PENDING: AtomicBool = AtomicBool::new(false);

/// Restart telemetry, surfaced through [`diagnostics`].
///
/// `RESTART_REQUESTS` counts what Kotlin asked for, `REBUILDS` counts what the
/// supervisor actually ran. The gap between them is how much coalescing did,
/// which is the only way to tell a working rebuild apart from a restart loop.
static RESTART_REQUESTS: AtomicU32 = AtomicU32::new(0);
static REBUILDS: AtomicU32 = AtomicU32::new(0);
static LAST_REBUILD_ATTEMPTS: AtomicU32 = AtomicU32::new(0);

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
    RESTART_LOCK.get_or_init(|| tokio::sync::Mutex::new(()));
}

/// Publishes a lifecycle transition. Kotlin mirrors this in its own state
/// machine; the native value is the authoritative one for internal decisions.
pub fn set_state(state: SpircState) {
    let previous = STATE.swap(state.as_u8(), Ordering::AcqRel);
    if previous != state.as_u8() {
        debug!(
            "spirc state: {:?} -> {:?}",
            SpircState::from_u8(previous),
            state
        );
    }
}

pub fn set_auto_transfer(enabled: bool) {
    AUTO_TRANSFER.store(enabled, Ordering::Relaxed);
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
        crossfade: Duration,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let player_config = PlayerConfig {
            // TODO: Make configurable from app
            position_update_interval: Some(std::time::Duration::from_millis(5_000)),
            crossfade,
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

        GAPLESS.store(gapless, Ordering::Relaxed);
        NORMALISE_AUDIO.store(normalisation, Ordering::Relaxed);
        CROSSFADE.store(crossfade.as_millis() as u32, Ordering::Relaxed);

        if let Some(bitrate_mutex) = BITRATE.get()
            && let Ok(mut guard) = bitrate_mutex.lock()
        {
            *guard = bitrate;
        }

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
    ) -> Result<Vec<(librespot_protocol::player::ProvidedTrack, bool)>, librespot_core::Error> {
        self.spirc
            .prev_tracks()
            .await
            .ok_or_else(|| librespot_core::Error::internal("Spirc task not available"))
    }

    pub async fn next_tracks(
        &self,
    ) -> Result<Vec<(librespot_protocol::player::ProvidedTrack, bool)>, librespot_core::Error> {
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
}

// Handles each player event accordingly
fn handle_event(event: PlayerEvent) {
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
            notify_buffer_state("started");
        }
        PlayerEvent::BufferStop {} => {
            notify_buffer_state("stopped");
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

/// Playback-affecting settings currently applied to the runtime.
struct RuntimeSettings {
    device_name: String,
    gapless: bool,
    normalisation: bool,
    bitrate: Bitrate,
    crossfade: Duration,
}

fn current_settings() -> RuntimeSettings {
    let bitrate = BITRATE
        .get()
        .and_then(|m| m.lock().ok())
        .map(|b| *b)
        .unwrap_or(Bitrate::Bitrate320);

    RuntimeSettings {
        device_name: DEVICE_NAME
            .get()
            .and_then(|m| m.lock().ok())
            .map(|n| n.clone())
            .unwrap_or_else(|| "Outify".to_string()),
        gapless: GAPLESS.load(Ordering::Relaxed),
        normalisation: NORMALISE_AUDIO.load(Ordering::Relaxed),
        bitrate,
        crossfade: Duration::from_millis(CROSSFADE.load(Ordering::Relaxed) as u64),
    }
}

/// Records the requested playback settings so [`start_runtime`] can apply them.
pub fn store_settings(
    device_name: String,
    gapless: bool,
    normalisation: bool,
    bitrate: Bitrate,
    crossfade: Duration,
) {
    GAPLESS.store(gapless, Ordering::Relaxed);
    NORMALISE_AUDIO.store(normalisation, Ordering::Relaxed);
    CROSSFADE.store(crossfade.as_millis() as u32, Ordering::Relaxed);

    if let Some(m) = BITRATE.get()
        && let Ok(mut guard) = m.lock()
    {
        *guard = bitrate;
    }
    if let Some(m) = DEVICE_NAME.get()
        && let Ok(mut guard) = m.lock()
    {
        *guard = device_name;
    }
}

/// Builds the Connect runtime from the currently stored settings.
pub async fn start_runtime() -> Result<(), SpircError> {
    let settings = current_settings();

    let lock = SPIRC_RUNTIME.get_or_init(|| RwLock::new(None));
    if lock.read().map(|g| g.is_some()).unwrap_or(true) {
        return Err(SpircError::Other(
            "spirc runtime slot is occupied".to_string(),
        ));
    }

    let session = with_session(|s| s.clone()).map_err(|e| {
        error!("failed to clone session for spirc init: {e}");
        SpircError::Other(format!("failed to clone session for spirc init: {e}"))
    })?;

    if session.cache().is_none() {
        return Err(SpircError::Other(
            "session cache missing for spirc init".to_string(),
        ));
    }

    let credentials = session
        .cache()
        .and_then(|cache| cache.credentials())
        .ok_or_else(|| {
            SpircError::Other("cached credentials missing for spirc init".to_string())
        })?;

    let runtime = SpircRuntime::new(
        &session,
        credentials,
        settings.device_name,
        settings.gapless,
        settings.normalisation,
        settings.bitrate,
        settings.crossfade,
    )
    .await
    .map_err(|e| SpircError::Other(e.to_string()))?;

    let mut guard = lock
        .write()
        .map_err(|_| SpircError::Other("spirc lock poisoned".into()))?;
    *guard = Some(runtime);
    drop(guard);

    debug!("spirc runtime initialized");
    Ok(())
}

/// Tears the Connect runtime down without touching the core session.
pub fn teardown_runtime(reason: &str) {
    let lock = SPIRC_RUNTIME.get_or_init(|| RwLock::new(None));

    let taken = match lock.write() {
        Ok(mut guard) => guard.take(),
        Err(_) => {
            error!("spirc lock poisoned, cannot tear down runtime");
            return;
        }
    };

    if let Some(runtime) = taken {
        info!("tearing down spirc runtime ({reason})");
        runtime.shutdown();
    }
}

/// Requests a rebuild of the session and the Connect runtime.
pub async fn request_restart(reason: &str) -> Result<(), String> {
    RESTART_REQUESTS.fetch_add(1, Ordering::Relaxed);
    RESTART_PENDING.store(true, Ordering::Release);

    let lock = RESTART_LOCK.get_or_init(|| tokio::sync::Mutex::new(()));
    let _guard = lock.lock().await;

    let mut runs = 0;
    let mut last_error = None;

    while RESTART_PENDING.swap(false, Ordering::AcqRel) {
        runs += 1;
        REBUILDS.fetch_add(1, Ordering::Relaxed);
        let outcome = crate::session::rebuild_all(reason).await;
        LAST_REBUILD_ATTEMPTS.store(crate::session::last_rebuild_attempts(), Ordering::Relaxed);
        if let Err(e) = outcome {
            last_error = Some(e);
        }
    }

    if runs > 1 {
        info!("coalesced restart requests into {runs} rebuilds");
    }

    match last_error {
        Some(e) => Err(e),
        None => Ok(()),
    }
}

/// Snapshot of the restart lifecycle, for the debug screen.
///
/// Read-only and lock-bite: every field is an atomic or a best-effort read, so
/// this is safe to call at any time, including mid-rebuild.
pub fn diagnostics() -> Vec<(&'static str, String)> {
    let state = SpircState::from_u8(STATE.load(Ordering::Acquire));
    let settings = current_settings();

    let requests = RESTART_REQUESTS.load(Ordering::Relaxed);
    let rebuilds = REBUILDS.load(Ordering::Relaxed);

    vec![
        ("state", format!("{state:?}")),
        ("session", presence(crate::session::session_present())),
        ("runtime", presence(runtime_present())),
        (
            "username",
            match crate::session::get_username() {
                Ok(u) => u,
                Err(_) => "unavailable".to_string(),
            },
        ),
        (
            "restart pending",
            RESTART_PENDING.load(Ordering::Acquire).to_string(),
        ),
        ("restart requests", requests.to_string()),
        ("rebuilds", rebuilds.to_string()),
        (
            "coalesced requests",
            requests.saturating_sub(rebuilds).to_string(),
        ),
        (
            "last rebuild attempts",
            LAST_REBUILD_ATTEMPTS.load(Ordering::Relaxed).to_string(),
        ),
        (
            "auto transfer",
            AUTO_TRANSFER.load(Ordering::Relaxed).to_string(),
        ),
        ("applied gapless", settings.gapless.to_string()),
        ("applied normalise", settings.normalisation.to_string()),
        ("applied bitrate", format!("{:?}", settings.bitrate)),
        (
            "applied crossfade",
            format!("{}ms", settings.crossfade.as_millis()),
        ),
        ("applied device name", settings.device_name),
    ]
}

fn presence(present: bool) -> String {
    if present {
        "present".to_string()
    } else {
        "absent".to_string()
    }
}

fn runtime_present() -> bool {
    SPIRC_RUNTIME
        .get()
        .and_then(|c| c.read().ok())
        .map(|guard| guard.is_some())
        .unwrap_or(false)
}

/// Re-establishes the Connect session and playback after a (re)build.
pub fn reestablish_after_restart() {
    let transferred = with_spirc(|spirc| {
        let _ = spirc.activate();

        if AUTO_TRANSFER.load(Ordering::Relaxed) {
            match spirc.transfer() {
                Ok(()) => {
                    info!("transferred session after restart");
                    true
                }
                Err(e) => {
                    warn!("transfer after restart failed: {e}");
                    false
                }
            }
        } else {
            info!("skipping transfer after restart, auto transfer disabled");
            false
        }
    });

    match transferred {
        Ok(true) => {
            let _ = with_spirc(|spirc| spirc.resume_playback());
        }
        Ok(false) => {
            // Another device stayed active, so the server drives our state and
            // reloading the queue here would fight it.
            debug!("not restoring playback, session stayed with another device");
        }
        Err(e) => warn!("cannot inspect spirc runtime after restart: {e}"),
    }
}

// Notifies UI of buffer state with given method
fn notify_buffer_state(method: &str) {
    let cb = match crate::jni_impl::spirc::buffer_cb() {
        Some(c) => c,
        None => return,
    };

    let idx = match method {
        "started" => crate::jni_impl::spirc::METHOD_BUFFER_STARTED,
        "stopped" => crate::jni_impl::spirc::METHOD_BUFFER_STOPPED,
        _ => return,
    };

    crate::jni_utils::jni_bridge::dispatch(cb, idx, vec![]);
}

pub fn notify_device_state(is_active: bool) {
    let cb = match crate::jni_impl::spirc::device_cb() {
        Some(c) => c,
        None => return,
    };

    let idx = if is_active {
        crate::jni_impl::spirc::METHOD_DEVICE_ACTIVE
    } else {
        crate::jni_impl::spirc::METHOD_DEVICE_INACTIVE
    };

    crate::jni_utils::jni_bridge::dispatch(cb, idx, vec![]);
}

pub fn notify_device_volume(volume: u16) {
    let cb = match crate::jni_impl::spirc::device_cb() {
        Some(c) => c,
        None => return,
    };

    crate::jni_utils::jni_bridge::dispatch(
        cb,
        crate::jni_impl::spirc::METHOD_DEVICE_VOLUME,
        vec![crate::jni_utils::jni_bridge::BridgeArg::Int(volume as i32)],
    );
}

pub fn current_track() -> Option<String> {
    if let Some(uri) = CURRENT_TRACK.get() {
        return uri.lock().unwrap().clone();
    }
    None
}

pub fn shutdown() {
    teardown_runtime("shutdown");
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_state_round_trips_through_its_encoding() {
        let states = [
            SpircState::Stopped,
            SpircState::Starting,
            SpircState::Ready,
            SpircState::Rebuilding,
            SpircState::Failed,
        ];

        for state in states {
            assert_eq!(SpircState::from_u8(state.as_u8()), state);
        }
    }

    #[test]
    fn unknown_state_decodes_to_stopped() {
        assert_eq!(SpircState::from_u8(200), SpircState::Stopped);
    }

    #[test]
    fn diagnostics_are_readable_without_a_session() {
        // The debug screen must render rather than crash before login, so every
        // lookup has to degrade instead of asserting.
        let entries = diagnostics();
        let keys: Vec<&str> = entries.iter().map(|(k, _)| *k).collect();

        for expected in [
            "state",
            "session",
            "runtime",
            "username",
            "restart pending",
            "restart requests",
            "rebuilds",
            "coalesced requests",
            "last rebuild attempts",
            "applied gapless",
            "applied bitrate",
        ] {
            assert!(
                keys.contains(&expected),
                "missing diagnostics key: {expected}"
            );
        }
    }

    #[test]
    fn diagnostics_report_state_and_missing_runtime() {
        let entries = diagnostics();
        let get = |key: &str| {
            entries
                .iter()
                .find(|(k, _)| *k == key)
                .map(|(_, v)| v.clone())
                .unwrap_or_else(|| panic!("missing key: {key}"))
        };

        assert_eq!(get("state"), format!("{:?}", SpircState::from_u8(0)));
        // No runtime is published in a unit test.
        assert_eq!(get("runtime"), "absent");
    }

    #[test]
    fn coalescing_gap_is_never_negative() {
        // A test that somehow rebuilt more than it requested must not report a
        // nonsensical negative gap.
        let entries = diagnostics();
        let coalesced = entries
            .iter()
            .find(|(k, _)| *k == "coalesced requests")
            .map(|(_, v)| v.clone())
            .expect("missing coalesced requests");
        let coalesced: u32 = coalesced.parse().expect("coalesced is numeric");
        assert!(coalesced <= RESTART_REQUESTS.load(Ordering::Relaxed));
    }
}
