use crate::{
    spotify::{error::SpotifyApiError, search::extract_all_uris},
    types::responses::{ArtistsOrTracksPage, CurrentUserResponse},
};

use super::{SPOTIFY_API_URL, SpotifyClient, check_response_json};

impl SpotifyClient {
    pub async fn search(
        &self,
        query: &str,
        types: &str,
        limit: Option<i32>,
        offset: Option<i32>,
    ) -> Result<Vec<String>, SpotifyApiError> {
        let mut params = vec![("q", query.to_string()), ("type", types.to_string())];

        if let Some(l) = limit {
            params.push(("limit", l.to_string()));
        }

        if let Some(o) = offset {
            params.push(("offset", o.to_string()));
        }

        let res = self
            .authorized_get(&format!("{}/v1/search", SPOTIFY_API_URL), &params)
            .await?;

        if !res.status().is_success() {
            let status = res.status();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyApiError::Http(status.as_u16(), body));
        }

        let text = res.text().await?;

        let parsed: crate::spotify::search::SearchResponse = serde_json::from_str(&text)?;

        let uris = extract_all_uris(parsed);

        Ok(uris)
    }

    pub async fn get_current_user(&self) -> Result<CurrentUserResponse, SpotifyApiError> {
        let res = self
            .authorized_get(&format!("{}/v1/me", SPOTIFY_API_URL), &[])
            .await?;

        check_response_json::<CurrentUserResponse>("get_current_user", res).await
    }

    pub async fn get_top(
        &self,
        request_type: Option<String>,
        time_range: String,
    ) -> Result<ArtistsOrTracksPage, SpotifyApiError> {
        let request_type = request_type.unwrap_or_else(|| "artists".to_string());

        let url = format!(
            "{}/v1/me/top/{}?time_range={}",
            SPOTIFY_API_URL, request_type, time_range
        );

        let res = self.authorized_get(&url, &[]).await?;

        check_response_json::<ArtistsOrTracksPage>("get_top", res).await
    }
}
