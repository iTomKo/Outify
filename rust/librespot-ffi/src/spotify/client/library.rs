use std::str::FromStr;

use reqwest::StatusCode;

use serde::{Deserialize, Serialize};

use crate::{
    collection_api::{self, CollectionType},
    spotify::error::SpotifyApiError,
    types::responses::library::{EpisodeUri, SavedItem, SavedItemsResponse, Uri},
};

use super::{REQUEST_TIMEOUT, SPOTIFY_API_URL, SpotifyClient};

impl SpotifyClient {
    pub async fn save_items(&self, uris: Vec<String>) -> Result<StatusCode, SpotifyApiError> {
        self.update_items(uris, true).await
    }

    pub async fn delete_items(&self, uris: Vec<String>) -> Result<StatusCode, SpotifyApiError> {
        self.update_items(uris, false).await
    }

    /// Collection-v2 uses playback auth. Only other item types need Web API auth;
    /// native failures must never fall back to a possibly different OAuth account.
    async fn update_items(
        &self,
        uris: Vec<String>,
        saved: bool,
    ) -> Result<StatusCode, SpotifyApiError> {
        let (native, web) = partition_items(uris)?;
        // Check required OAuth credentials before applying a mixed batch.
        let token = if web.is_empty() {
            None
        } else {
            Some(self.load_token().await?.ok_or(SpotifyApiError::NoToken)?)
        };
        if !native.is_empty() {
            let session = crate::session::with_session(Clone::clone)?;
            collection_api::set_saved(&session, &native, saved).await?;
        }
        let Some(token) = token else {
            return Ok(StatusCode::OK);
        };

        let res = self
            .client
            .request(
                if saved {
                    reqwest::Method::PUT
                } else {
                    reqwest::Method::DELETE
                },
                format!("{}/v1/me/library", SPOTIFY_API_URL),
            )
            .query(&[("uris", web.join(","))])
            .header(reqwest::header::CONTENT_LENGTH, "0")
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_str().to_string();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyApiError::Generic(format!(
                "update_items failed with status {status}: {body}"
            )));
        }

        Ok(res.status())
    }

    /// Tracks, albums and artists are read through the playback session.
    pub async fn get_saved(
        &self,
        item: SavedItemType,
    ) -> Result<SavedItemsResponse<Uri>, SpotifyApiError> {
        if let Some(kind) = item.collection_type() {
            let session = crate::session::with_session(Clone::clone)?;
            let items = collection_api::saved_items(&session, kind).await?;
            return Ok(SavedItemsResponse {
                items: items
                    .into_iter()
                    .map(|item| SavedItem {
                        item: Uri { uri: item.uri },
                    })
                    .collect(),
            });
        }
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

    /// Get episode details (show URI, resume point, etc.) via the Spotify Web API
    pub async fn get_episode_details(
        &self,
        episode_id: &str,
    ) -> Result<EpisodeDetails, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        #[derive(Deserialize)]
        struct EpisodeResponse {
            show: ShowRef,
            resume_point: Option<ResumePoint>,
        }

        #[derive(Deserialize)]
        struct ShowRef {
            uri: String,
        }

        #[derive(Deserialize)]
        struct ResumePoint {
            fully_played: bool,
            resume_position_ms: i64,
        }

        let resp = self
            .client
            .get(format!("{}/v1/episodes/{}", SPOTIFY_API_URL, episode_id))
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            error!("get_episode_details status {status}: {body}");
            return Err(SpotifyApiError::Http(status.as_u16(), body));
        }

        let res = resp.json::<EpisodeResponse>().await;

        match res {
            Ok(ep) => Ok(EpisodeDetails {
                show_uri: ep.show.uri,
                fully_played: ep
                    .resume_point
                    .as_ref()
                    .map(|r| r.fully_played)
                    .unwrap_or(false),
                resume_position_ms: ep
                    .resume_point
                    .as_ref()
                    .map(|r| r.resume_position_ms)
                    .unwrap_or(0),
            }),
            Err(e) => {
                error!("get_episode_details deserialization failed: {e}");
                Err(SpotifyApiError::Generic(format!(
                    "get_episode_details failed: {e}"
                )))
            }
        }
    }
}

fn partition_items(uris: Vec<String>) -> Result<(Vec<String>, Vec<String>), SpotifyApiError> {
    let mut native = Vec::new();
    let mut web = Vec::new();
    for uri in uris {
        if CollectionType::from_uri(&uri)?.is_some() {
            native.push(uri);
        } else {
            web.push(uri);
        }
    }
    Ok((native, web))
}

#[derive(Serialize)]
pub struct EpisodeDetails {
    pub show_uri: String,
    pub fully_played: bool,
    pub resume_position_ms: i64,
}

pub enum SavedItemType {
    Tracks,
    Albums,
    Artists,
    Episodes,
    Shows,
}

impl SavedItemType {
    fn collection_type(&self) -> Option<CollectionType> {
        match self {
            Self::Tracks => Some(CollectionType::Tracks),
            Self::Albums => Some(CollectionType::Albums),
            Self::Artists => Some(CollectionType::Artists),
            Self::Episodes | Self::Shows => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            SavedItemType::Tracks => "tracks",
            SavedItemType::Albums => "albums",
            SavedItemType::Artists => "artists",
            SavedItemType::Episodes => "episodes",
            SavedItemType::Shows => "shows",
        }
    }
}

impl FromStr for SavedItemType {
    type Err = SpotifyApiError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "tracks" => Ok(Self::Tracks),
            "albums" => Ok(Self::Albums),
            "artists" => Ok(Self::Artists),
            "episodes" => Ok(Self::Episodes),
            "shows" => Ok(Self::Shows),
            other => Err(SpotifyApiError::Generic(format!(
                "Invalid SavedItemType: {other}"
            ))),
        }
    }
}
