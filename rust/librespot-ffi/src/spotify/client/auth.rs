use std::{
    collections::HashMap,
    fs::OpenOptions,
    os::unix::fs::OpenOptionsExt,
    time::{Duration, Instant},
};

use librespot_oauth::{OAuthClientBuilder, OAuthToken};
use oauth2::AuthorizationCode;

use crate::spotify::{
    error::SpotifyApiError,
    token::{TokenResponse, WebApiToken},
};

use super::{
    OAuthState, SPOTIFY_OAUTH_CALLBACK_URI, SPOTIFY_OAUTH_SCOPES, SpotifyClient,
    check_response_json,
};

impl SpotifyClient {
    pub async fn get_oauth_url(&self) -> String {
        SPOTIFY_OAUTH_CALLBACK_URI.to_string()
    }

    pub async fn start_oauth_flow(&self) -> Result<String, SpotifyApiError> {
        let client_id = self.client_id.lock().unwrap().clone();
        let oauth_client = OAuthClientBuilder::new(
            &client_id,
            SPOTIFY_OAUTH_CALLBACK_URI,
            SPOTIFY_OAUTH_SCOPES.to_vec(),
        )
        .build()
        .map_err(|e| SpotifyApiError::Generic(format!("Failed to build OAuth client: {}", e)))?;

        let (auth_url, pkce_verifier) = oauth_client.set_auth_url();

        let state = OAuthState {
            oauth_client,
            pkce_verifier: Some(pkce_verifier),
            created_at: Instant::now(),
        };

        let mut oauth_state_guard = self.oauth_state.write().await;
        *oauth_state_guard = Some(state);

        debug!("oauth flow started with url: {auth_url}");
        Ok(auth_url.to_string())
    }

    pub async fn complete_oauth_flow(&self, code: String) -> Result<WebApiToken, SpotifyApiError> {
        // Take the state out of the slot instead of borrowing it. An
        // authorization code can only be exchanged once, so a failed attempt
        // must not leave a half-consumed PKCE verifier behind: the next
        // attempt starts from a clean slate.
        let state = {
            let mut guard = self.oauth_state.write().await;
            guard.take().ok_or(SpotifyApiError::Generic(
                "OAuth flow not started. Call start_oauth_flow first.".to_string(),
            ))?
        };

        if state.created_at.elapsed() > Duration::from_secs(600) {
            error!("oauth state expired");
            return Err(SpotifyApiError::Generic(
                "OAuth state expired. Please start the flow again.".to_string(),
            ));
        }

        let pkce_verifier = state.pkce_verifier.ok_or(SpotifyApiError::Generic(
            "PKCE verifier missing. Please start the flow again.".to_string(),
        ))?;
        let oauth_client = &state.oauth_client;

        let auth_code = AuthorizationCode::new(code);
        let token_response: OAuthToken = oauth_client
            .get_access_token_with_verifier_async(pkce_verifier, auth_code)
            .await
            .map_err(|e| {
                error!("oauth token exchange failed: {e}");
                SpotifyApiError::Generic(format!("Token exchange failed: {e}"))
            })?;

        let now = Instant::now();
        let expires_in = if token_response.expires_at > now {
            token_response.expires_at.duration_since(now).as_secs()
        } else {
            0
        };

        let new_token = WebApiToken::new(
            token_response.access_token,
            token_response.refresh_token,
            expires_in,
            token_response.scopes.join(" "),
        );

        let mut token_guard = self.token.write().await;
        *token_guard = Some(new_token.clone());
        drop(token_guard);

        debug!("oauth flow completed");

        match self.save_token(&new_token).await {
            Ok(_) => debug!("oauth token saved to account.json"),
            Err(e) => {
                error!("oauth token save failed: {e}");
            }
        };

        Ok(new_token)
    }

    pub async fn save_token(&self, token: &WebApiToken) -> Result<(), SpotifyApiError> {
        let mut path = crate::FILES_DIR
            .get()
            .ok_or_else(|| SpotifyApiError::Generic("Android file path is not set!".to_string()))?
            .clone();

        path.push("account.json");

        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&path)?;

        let json = serde_json::to_string(token).map_err(|e| {
            SpotifyApiError::Generic(format!("Failed to serialize WebApiToken: {e}"))
        })?;

        std::io::Write::write_all(&mut file, json.as_bytes())?;
        Ok(())
    }

    pub fn remove_token(&self) -> Result<(), SpotifyApiError> {
        let mut path = crate::FILES_DIR
            .get()
            .ok_or_else(|| SpotifyApiError::Generic("Android file path is not set!".to_string()))?
            .clone();

        path.push("account.json");

        match std::fs::remove_file(path) {
            Ok(_) => Ok(()),
            Err(e) => {
                error!("account.json removal failed: {e}");
                Err(SpotifyApiError::IO(e))
            }
        }
    }

    /// Whether a stored token exists. Does not refresh, so a transient
    /// network failure while refreshing does not log the user out.
    /// Revoked refresh tokens remove the stored token in `refresh_token`.
    pub async fn is_oauth_authenticated(&self) -> bool {
        matches!(self.read_token_file(), Ok(Some(_)))
    }

    pub async fn get_scope(&self) -> Option<String> {
        match self.load_token().await {
            Ok(Some(t)) => Some(t.scope),
            _ => None,
        }
    }

    fn read_token_file(&self) -> Result<Option<WebApiToken>, SpotifyApiError> {
        let mut path = crate::FILES_DIR
            .get()
            .ok_or_else(|| SpotifyApiError::Generic("Android file path is not set!".to_string()))?
            .clone();

        path.push("account.json");

        match std::fs::read_to_string(&path) {
            Ok(contents) => serde_json::from_str::<WebApiToken>(&contents)
                .map(Some)
                .map_err(|e| SpotifyApiError::Generic(format!("Failed to parse token JSON: {e}"))),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(SpotifyApiError::IO(e)),
        }
    }

    pub async fn load_token(&self) -> Result<Option<WebApiToken>, SpotifyApiError> {
        let Some(token) = self.read_token_file()? else {
            return Ok(None);
        };

        if token.is_expired() {
            let refreshed = self.refresh_token(&token).await?;
            return Ok(Some(refreshed));
        }

        Ok(Some(token))
    }

    pub(crate) async fn refresh_token(
        &self,
        token: &WebApiToken,
    ) -> Result<WebApiToken, SpotifyApiError> {
        let _guard = self.refresh_lock.lock().await;

        // Another request may have refreshed while we waited for the lock;
        // reusing the old (rotated) refresh token would then fail.
        if let Some(stored) = self.read_token_file()? {
            if stored.access_token != token.access_token && !stored.is_expired() {
                return Ok(stored);
            }
        }

        let mut form = HashMap::new();
        form.insert("grant_type", "refresh_token");
        form.insert("refresh_token", &token.refresh_token);
        let client_id = self.client_id.lock().unwrap().clone();
        form.insert("client_id", &client_id);

        let response = self
            .client
            .post("https://accounts.spotify.com/api/token")
            .form(&form)
            .send()
            .await?;

        if response.status() == reqwest::StatusCode::BAD_REQUEST {
            let body = response.text().await.unwrap_or_default();
            if body.contains("invalid_grant") {
                error!("refresh token revoked, removing stored token: {body}");
                let _ = self.remove_token();
            }
            return Err(SpotifyApiError::Http(400, body));
        }

        let response = check_response_json::<TokenResponse>("refresh_token", response).await?;

        let new_token = WebApiToken::from(response, Some(&token.refresh_token));
        self.save_token(&new_token).await?;
        Ok(new_token)
    }
}
