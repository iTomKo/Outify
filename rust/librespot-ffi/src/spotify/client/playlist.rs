use reqwest::StatusCode;

use crate::{
    spotify::error::SpotifyApiError,
    types::{
        requests::{AddItemRequest, CreatePlaylistRequest, RemoveItem, RemoveItemRequest},
        responses::CreatePlaylistResponse,
    },
};

use super::{REQUEST_TIMEOUT, SPOTIFY_API_URL, SpotifyClient, check_response_json};

impl SpotifyClient {
    pub async fn add_to_playlist(
        &self,
        playlist_id: String,
        uris: Vec<String>,
    ) -> Result<StatusCode, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let body = AddItemRequest {
            uris,
            position: Some(0),
        };

        let res = self
            .client
            .post(format!(
                "{}/v1/playlists/{}/items",
                SPOTIFY_API_URL, playlist_id
            ))
            .bearer_auth(token.access_token)
            .json(&body)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_str().to_string();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyApiError::Generic(format!(
                "add_to_playlist failed with status {status}: {body}"
            )));
        }

        Ok(res.status())
    }

    pub async fn delete_from_playlist(
        &self,
        playlist_id: String,
        uris: Vec<String>,
    ) -> Result<StatusCode, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let body = RemoveItemRequest {
            items: uris.into_iter().map(|uri| RemoveItem { uri }).collect(),
        };

        let res = self
            .client
            .delete(format!(
                "{}/v1/playlists/{}/items",
                SPOTIFY_API_URL, playlist_id
            ))
            .bearer_auth(token.access_token)
            .json(&body)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_str().to_string();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyApiError::Generic(format!(
                "delete_from_playlist failed with status {status}: {body}"
            )));
        }

        Ok(res.status())
    }

    pub async fn create_playlist(
        &self,
        name: String,
        description: Option<String>,
        public: bool,
        collaborative: bool,
    ) -> Result<CreatePlaylistResponse, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let body = CreatePlaylistRequest {
            name,
            public,
            collaborative,
            description,
        };

        let url = format!("{}/v1/me/playlists", SPOTIFY_API_URL);

        let res = self
            .client
            .post(&url)
            .json(&body)
            .bearer_auth(&token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if res.status() == StatusCode::UNAUTHORIZED {
            let new_token = self.refresh_token(&token).await?;
            let res = self
                .client
                .post(&url)
                .json(&body)
                .bearer_auth(new_token.access_token)
                .timeout(REQUEST_TIMEOUT)
                .send()
                .await?;
            let data =
                check_response_json::<CreatePlaylistResponse>("create_playlist", res).await?;
            return Ok(data);
        }

        let data = check_response_json::<CreatePlaylistResponse>("create_playlist", res).await?;

        Ok(data)
    }

    pub async fn modify_playlist(
        &self,
        id: String,
        name: String,
        description: Option<String>,
        public: bool,
        collaborative: bool,
    ) -> Result<StatusCode, SpotifyApiError> {
        let token = self.load_token().await?;
        let token = token
            .ok_or_else(|| SpotifyApiError::Generic("No account token present!".to_string()))?;

        let body = CreatePlaylistRequest {
            name,
            public,
            collaborative,
            description,
        };

        let res = self
            .client
            .put(format!("{}/v1/playlists/{}", SPOTIFY_API_URL, id))
            .json(&body)
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_str().to_string();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyApiError::Generic(format!(
                "modify_playlist failed with status {status}: {body}"
            )));
        }

        Ok(res.status())
    }
}
