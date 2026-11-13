//! Playback-authenticated requests without replaying ambiguous network failures.
use crate::collection_session::{CollectionSession, ensure_current};
use bytes::Bytes;
use http::{Method, Request};
use librespot_core::{Error, Session, spclient::CLIENT_TOKEN};

pub async fn request(
    session: &Session,
    expected: &CollectionSession,
    method: Method,
    endpoint: &str,
    content_type: &str,
    body: &[u8],
) -> Result<Vec<u8>, Error> {
    request_guarded(
        session,
        expected,
        method,
        endpoint,
        content_type,
        body,
        false,
        || Ok(()),
    )
    .await
}

/// The guard runs after pacing/authentication, immediately before the HTTP attempt.
pub async fn request_guarded(
    session: &Session,
    expected: &CollectionSession,
    method: Method,
    endpoint: &str,
    content_type: &str,
    body: &[u8],
    single_attempt: bool,
    guard: impl Fn() -> Result<(), Error>,
) -> Result<Vec<u8>, Error> {
    crate::sync_request::paced(|| async {
        ensure_current(expected)?;
        let url = if endpoint.starts_with('/') {
            format!("{}{endpoint}", session.spclient().base_url().await?)
        } else if endpoint == "https://api-partner.spotify.com/pathfinder/v2/query" {
            endpoint.to_owned()
        } else {
            return Err(Error::invalid_argument("unsupported native API host"));
        };
        let token = session.login5().auth_token().await?;
        let client_token = session.spclient().client_token().await?;
        let request = Request::builder()
            .method(method.clone())
            .uri(url)
            .header("accept", content_type)
            .header("content-type", content_type)
            .header(
                "authorization",
                format!("{} {}", token.token_type, token.access_token),
            )
            .header(CLIENT_TOKEN, client_token)
            .header("app-platform", "WebPlayer")
            .body(Bytes::copy_from_slice(body))?;
        ensure_current(expected)?;
        // HttpClient retries explicit 429s, but not transport failures after a mutation.
        guard()?;
        let response = tokio::time::timeout(std::time::Duration::from_secs(30), async {
            if single_attempt {
                session.http_client().request_body_once(request).await
            } else {
                session.http_client().request_body(request).await
            }
        })
        .await
        .map_err(|_| Error::deadline_exceeded("native API request timed out"))??;
        ensure_current(expected)?;
        Ok(response.to_vec())
    })
    .await
}

pub fn component(value: &str) -> String {
    use std::fmt::Write;
    let mut encoded = String::new();
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                encoded.push(byte as char)
            }
            _ => {
                let _ = write!(encoded, "%{byte:02X}");
            }
        }
    }
    encoded
}
