use std::str::FromStr;

use reqwest::StatusCode;

use serde::Deserialize;

use crate::{
    spotify::error::SpotifyApiError,
    types::responses::library::{EpisodeUri, SavedItemsResponse, Uri},
};

use super::{REQUEST_TIMEOUT, SPOTIFY_API_URL, SpotifyClient};

impl SpotifyClient {
    pub async fn save_items(&self, uris: Vec<String>) -> Result<StatusCode, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let ids = uris.join(",");

        let res = self
            .client
            .put(format!("{}/v1/me/library", SPOTIFY_API_URL))
            .query(&[("uris", ids)])
            .header(reqwest::header::CONTENT_LENGTH, "0")
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_str().to_string();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyApiError::Generic(format!(
                "save_items failed with status {status}: {body}"
            )));
        }

        Ok(res.status())
    }

    pub async fn delete_items(&self, uris: Vec<String>) -> Result<StatusCode, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let ids = uris.join(",");

        let res = self
            .client
            .delete(format!("{}/v1/me/library", SPOTIFY_API_URL))
            .query(&[("uris", ids)])
            .header(reqwest::header::CONTENT_LENGTH, "0")
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_str().to_string();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyApiError::Generic(format!(
                "delete_items failed with status {status}: {body}"
            )));
        }

        Ok(res.status())
    }

    /// Get tracks/albums/episodes saved in library
    pub async fn get_saved(
        &self,
        item: SavedItemType,
    ) -> Result<SavedItemsResponse<Uri>, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let res = self
            .client
            .get(format!("{}/v1/me/{}", SPOTIFY_API_URL, item.as_str()))
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?
            .json::<SavedItemsResponse<Uri>>()
            .await;

        match res {
            Ok(items) => Ok(items),
            Err(e) => {
                return Err(SpotifyApiError::Generic(format!(
                    "get_saved failed with error: {e}"
                )));
            }
        }
    }

    pub async fn get_saved_episode_items(
        &self,
    ) -> Result<SavedItemsResponse<EpisodeUri>, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let res = self
            .client
            .get(format!("{}/v1/me/episodes", SPOTIFY_API_URL))
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?
            .json::<SavedItemsResponse<EpisodeUri>>()
            .await;

        match res {
            Ok(items) => Ok(items),
            Err(e) => {
                return Err(SpotifyApiError::Generic(format!(
                    "get_saved_episode_items failed with error: {e}"
                )));
            }
        }
    }

    /// Get the show URI for a given episode ID via the Spotify Web API
    pub async fn get_episode_show_uri(
        &self,
        episode_id: &str,
    ) -> Result<String, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        #[derive(Deserialize)]
        struct EpisodeResponse {
            show: ShowRef,
        }

        #[derive(Deserialize)]
        struct ShowRef {
            uri: String,
        }

        let res = self
            .client
            .get(format!("{}/v1/episodes/{}", SPOTIFY_API_URL, episode_id))
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?
            .json::<EpisodeResponse>()
            .await;

        match res {
            Ok(ep) => Ok(ep.show.uri),
            Err(e) => Err(SpotifyApiError::Generic(format!(
                "get_episode_show_uri failed: {e}"
            ))),
        }
    }
}

pub enum SavedItemType {
    Tracks,
    Albums,
    Episodes,
}

impl SavedItemType {
    pub fn as_str(&self) -> &'static str {
        match self {
            SavedItemType::Tracks => "tracks",
            SavedItemType::Albums => "albums",
            SavedItemType::Episodes => "episodes",
        }
    }
}

impl FromStr for SavedItemType {
    type Err = SpotifyApiError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "tracks" => Ok(Self::Tracks),
            "albums" => Ok(Self::Albums),
            "episodes" => Ok(Self::Episodes),
            other => Err(SpotifyApiError::Generic(format!(
                "Invalid SavedItemType: {other}"
            ))),
        }
    }
}
