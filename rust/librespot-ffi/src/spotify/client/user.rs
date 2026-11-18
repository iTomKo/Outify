use super::super::error::SpotifyApiError;
use super::SpotifyClient;
use crate::session::with_session;
use crate::types::responses::CurrentUserResponse;
use librespot_core::spclient::SpClientResult;
use serde::{Deserialize, Serialize};

impl SpotifyClient {
    pub async fn search(
        &self,
        query: &str,
        types: &str,
        limit: Option<i32>,
        offset: Option<i32>,
    ) -> Result<Vec<String>, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        Ok(crate::spotify::search::search(&session, query, types, limit, offset).await?)
    }

    pub async fn get_current_user(&self) -> Result<CurrentUserResponse, SpotifyApiError> {
        let session = with_session(Clone::clone)?;
        let username = session.username();
        let spclient = session.spclient();

        let response: SpClientResult = spclient.get_user_profile(&username, Some(0), Some(0)).await;

        let data = response.map_err(|e| SpotifyApiError::Librespot(e))?;

        let text = std::str::from_utf8(&data).map_err(|e| {
            SpotifyApiError::Generic(format!("Invalid UTF-8 in profile response: {e}"))
        })?;

        let profile: CurrentUserResponse = serde_json::from_str(text)
            .map_err(|e| SpotifyApiError::Generic(format!("Failed to parse profile: {e}")))?;

        Ok(profile)
    }
}
