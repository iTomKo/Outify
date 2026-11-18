use once_cell::sync::OnceCell;
use reqwest::Client;
use std::{sync::Mutex, time::Duration};

mod library;
mod player;
mod playlist;
mod user;

use super::error::SpotifyApiError;
pub use library::{EpisodeDetails, SavedItemType};

static SPOTIFY_CLIENT: OnceCell<SpotifyClient> = OnceCell::new();

pub struct SpotifyClient {
    pub(crate) client_id: Mutex<String>,
    pub(crate) client_secret: Mutex<String>,
}

impl SpotifyClient {
    pub fn new(client_id: String, client_secret: String) -> Self {
        Self {
            client_id: Mutex::new(client_id),
            client_secret: Mutex::new(client_secret),
        }
    }

    pub fn update_credentials(&self, client_id: String, client_secret: String) {
        *self.client_id.lock().unwrap() = client_id;
        *self.client_secret.lock().unwrap() = client_secret;
    }
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
