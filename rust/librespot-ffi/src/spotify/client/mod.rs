use once_cell::sync::OnceCell;
use reqwest::Client;
use std::{
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::sync::RwLock;

use crate::spotify::{error::SpotifyApiError, token::WebApiToken};

mod auth;
mod library;
mod player;
mod playlist;
mod user;

pub use library::SavedItemType;

const SPOTIFY_API_URL: &str = "https://api.spotify.com";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const SPOTIFY_OAUTH_CALLBACK_URI: &str = "http://127.0.0.1:5588/account/login";
const SPOTIFY_OAUTH_SCOPES: &[&str] = &[
    "streaming",
    "user-read-private",
    "user-read-email",
    "user-top-read",
    "user-library-modify",
    "user-library-read",
    "user-follow-modify",
    "user-read-playback-state",
    "playlist-modify-private",
    "playlist-modify-public",
];

static SPOTIFY_CLIENT: OnceCell<SpotifyClient> = OnceCell::new();

/// OAuth state for SpotifyClient's user authentication flow
pub struct OAuthState {
    pub oauth_client: librespot_oauth::OAuthClient,
    pub pkce_verifier: Option<oauth2::PkceCodeVerifier>,
    pub created_at: Instant,
}

pub struct SpotifyClient {
    pub(crate) client_id: Mutex<String>,
    pub(crate) client_secret: Mutex<String>,
    pub(crate) client: Client,
    pub(crate) token: Arc<RwLock<Option<WebApiToken>>>,
    pub(crate) oauth_state: Arc<RwLock<Option<OAuthState>>>,
}

impl SpotifyClient {
    pub fn new(client_id: String, client_secret: String) -> Self {
        Self {
            client_id: Mutex::new(client_id),
            client_secret: Mutex::new(client_secret),
            client: Client::builder()
                .pool_idle_timeout(Duration::from_secs(90))
                .build()
                .expect("failed to build client"),
            token: Arc::new(RwLock::new(None)),
            oauth_state: Arc::new(RwLock::new(None)),
        }
    }

    pub fn update_credentials(&self, client_id: String, client_secret: String) {
        *self.client_id.lock().unwrap() = client_id;
        *self.client_secret.lock().unwrap() = client_secret;
    }
}

pub(crate) async fn check_response_json<T: serde::de::DeserializeOwned>(
    method: &str,
    res: reqwest::Response,
) -> Result<T, SpotifyApiError> {
    if !res.status().is_success() {
        let status = res.status().as_str().to_string();
        let body = res.text().await.unwrap_or_default();
        return Err(SpotifyApiError::Generic(format!(
            "{method} failed with status {status}: {body}"
        )));
    }
    let text = res.text().await?;
    let data = serde_json::from_str(&text)?;
    Ok(data)
}

pub fn init_client(client_id: String, client_secret: String) {
    let client = SpotifyClient::new(client_id, client_secret);
    let _ = SPOTIFY_CLIENT.set(client);
}

pub fn get_client() -> &'static SpotifyClient {
    SPOTIFY_CLIENT
        .get()
        .expect("SpotifyClient not initialized!")
}

pub fn update_client(client_id: String, client_secret: String) {
    if let Some(client) = SPOTIFY_CLIENT.get() {
        client.update_credentials(client_id, client_secret);
        info!("spotify client credentials updated");
    }
}

pub fn reset_client() {
    if let Some(client) = SPOTIFY_CLIENT.get() {
        let rt = match crate::TOKIO_RUNTIME.get() {
            Some(r) => r,
            None => {
                warn!("tokio runtime not available for spclient reset");
                return;
            }
        };

        rt.block_on(async {
            let mut oauth_state_guard = client.oauth_state.write().await;
            *oauth_state_guard = None;
        });

        info!("spotify client oauth state reset");
    }
}
