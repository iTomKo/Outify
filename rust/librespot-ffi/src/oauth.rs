use librespot_core::{Error, Session};
use librespot_oauth::{OAuthClient, OAuthClientBuilder, OAuthToken};
use oauth2::{AuthorizationCode, PkceCodeVerifier, url::Url};
use once_cell::sync::OnceCell;
use tokio::sync::Mutex;

pub const SPOTIFY_CALLBACK_URI: &str = "http://127.0.0.1:5588/login";
static OAUTH_SCOPES: &[&str] = &[
    "app-remote-control",
    "playlist-modify",
    "playlist-modify-private",
    "playlist-modify-public",
    "playlist-read",
    "playlist-read-collaborative",
    "playlist-read-private",
    "streaming",
    "ugc-image-upload",
    "user-follow-modify",
    "user-follow-read",
    "user-library-modify",
    "user-library-read",
    "user-modify",
    "user-modify-playback-state",
    "user-modify-private",
    "user-personalized",
    "user-read-birthdate",
    "user-read-currently-playing",
    "user-read-email",
    "user-read-play-history",
    "user-read-playback-position",
    "user-read-playback-state",
    "user-read-private",
    "user-read-recently-played",
    "user-top-read",
];

pub static OAUTH_SESSION: OnceCell<Mutex<OAuthSession>> = OnceCell::new();

pub struct OAuthSession {
    client: OAuthClient,
    pub(crate) session: Session,
    pub pkce_verifier: Option<PkceCodeVerifier>,
    pub auth_url: Url,
}

impl OAuthSession {
    pub fn new(session: &Session, redirect_uri: &str, scopes: &[&str]) -> Result<Self, Error> {
        let client_id = session.client_id();
        debug!("creating oauth session with client_id: {client_id}, redirect_uri: {redirect_uri}");
        let client = OAuthClientBuilder::new(client_id.as_str(), redirect_uri, scopes.to_vec())
            .build()
            .map_err(|e| Error::internal(format!("Unable to build OAuth client: {e}")))?;
        let (auth_url, pkce_verifier) = client.set_auth_url();
        debug!("oauth session created with auth_url: {auth_url}");

        Ok(Self {
            client,
            session: session.clone(),
            pkce_verifier: Some(pkce_verifier),
            auth_url,
        })
    }

    pub fn auth_url(&self) -> &Url {
        &self.auth_url
    }

    /// Retrieves the access blob using the OAuth code.
    pub async fn get_access_token(&mut self, code: String) -> Result<OAuthToken, Error> {
        let pkce_verifier = self
            .pkce_verifier
            .take()
            .ok_or_else(|| Error::internal("Missing Pkce Verifier".to_string()))?;

        let auth_code = AuthorizationCode::new(code);

        debug!("exchanging oauth code for token");
        let token_response = self
            .client
            .get_access_token_with_verifier_async(pkce_verifier, auth_code)
            .await
            .map_err(|e| {
                error!("oauth token exchange failed: {e}");
                Error::unavailable(format!("Unable to get OAuth token: {e}"))
            })?;
        debug!("oauth token exchange succeeded");

        // Refreshing token to provide consistent TokenResponse that contains refresh token
        let refresh_token = token_response.refresh_token.clone();
        let _refreshed = self
            .client
            .refresh_token_async(&refresh_token)
            .await
            .map_err(|e| Error::unknown(format!("Unable to refresh OAuth token: {e}")))?;

        Ok(token_response)
    }
}

pub fn setup_oauth_session(session: &Session) -> Option<&'static Mutex<OAuthSession>> {
    debug!("setting up oauth session");
    if let Some(existing) = OAUTH_SESSION.get() {
        debug!("oauth session already initialized");
        return Some(existing);
    }

    let osession = match OAuthSession::new(&session, &SPOTIFY_CALLBACK_URI, OAUTH_SCOPES) {
        Ok(s) => s,
        Err(e) => {
            error!("oauth session setup failed: {e}");
            return None;
        }
    };

    match OAUTH_SESSION.set(Mutex::new(osession)) {
        Ok(_) => debug!("oauth session stored in global"),
        Err(_) => warn!("oauth session already set by another thread"),
    }

    OAUTH_SESSION.get()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_spotify_callback_uri_is_valid() {
        assert!(SPOTIFY_CALLBACK_URI.starts_with("http://"));
        assert!(SPOTIFY_CALLBACK_URI.contains("127.0.0.1"));
    }

    #[test]
    fn test_oauth_scopes_not_empty() {
        assert!(!OAUTH_SCOPES.is_empty());
    }

    #[test]
    fn test_oauth_scopes_contains_streaming() {
        assert!(OAUTH_SCOPES.contains(&"streaming"));
    }

    #[test]
    fn test_oauth_scopes_contains_user_read_private() {
        assert!(OAUTH_SCOPES.contains(&"user-read-private"));
    }

    #[test]
    fn test_oauth_scopes_count() {
        assert_eq!(OAUTH_SCOPES.len(), 26);
    }
}
