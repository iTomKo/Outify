use once_cell::sync::OnceCell;

mod library;
mod player;
mod playlist;
mod user;

pub use library::{EpisodeDetails, SavedItemType};

static SPOTIFY_CLIENT: OnceCell<SpotifyClient> = OnceCell::new();

pub struct SpotifyClient {
    pub(crate) client: reqwest::Client,
}

impl SpotifyClient {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::new(),
        }
    }

    pub fn get() -> &'static Self {
        SPOTIFY_CLIENT.get_or_init(Self::new)
    }

    /// Returns whether a live, non-invalidated librespot session is available.
    pub async fn is_playback_authenticated(&self) -> bool {
        crate::session::with_session(|session| !session.is_invalid()).unwrap_or(false)
    }
}
