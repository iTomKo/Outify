use once_cell::sync::OnceCell;
use reqwest::Client;
use std::{
    sync::Mutex,
    time::Duration,
};

mod library;
mod player;
mod playlist;
mod user;

pub use library::{SavedItemType, EpisodeDetails};
use super::error::SpotifyApiError;

static SPOTIFY_CLIENT: OnceCell<SpotifyClient> = OnceCell::new();

pub struct SpotifyClient {
    pub(crate) client_id: Mutex<String>,
    pub(crate) client_secret: Mutex<String>,
    pub(crate) client: Client,
    /// Serializes token refreshes (no longer used, kept for compatibility)
    pub(crate) refresh_lock: tokio::sync::Mutex<()>,
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
            refresh_lock: tokio::sync::Mutex::new(()),
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
