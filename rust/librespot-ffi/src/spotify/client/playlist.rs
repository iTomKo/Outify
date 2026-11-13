use super::SpotifyClient;
use crate::{
    playlist_api, spotify::error::SpotifyApiError, types::responses::CreatePlaylistResponse,
};
use reqwest::StatusCode;

impl SpotifyClient {
    pub async fn add_to_playlist(
        &self,
        playlist_id: String,
        uris: Vec<String>,
    ) -> Result<StatusCode, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        playlist_api::update_items(&session, &playlist_id, &uris, true).await?;
        Ok(StatusCode::OK)
    }

    pub async fn delete_from_playlist(
        &self,
        playlist_id: String,
        uris: Vec<String>,
    ) -> Result<StatusCode, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        playlist_api::update_items(&session, &playlist_id, &uris, false).await?;
        Ok(StatusCode::OK)
    }

    pub async fn create_playlist(
        &self,
        name: String,
        description: Option<String>,
        public: bool,
        collaborative: bool,
    ) -> Result<CreatePlaylistResponse, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        let id = playlist_api::create(
            &session,
            &name,
            description.as_deref().unwrap_or_default(),
            public,
            collaborative,
        )
        .await?;
        Ok(CreatePlaylistResponse { id })
    }

    pub async fn modify_playlist(
        &self,
        id: String,
        name: String,
        description: Option<String>,
        public: bool,
        collaborative: bool,
    ) -> Result<StatusCode, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        playlist_api::modify(
            &session,
            &id,
            &name,
            description.as_deref().unwrap_or_default(),
            public,
            collaborative,
        )
        .await?;
        Ok(StatusCode::OK)
    }
}
