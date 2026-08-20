use thiserror::Error;

#[derive(Error, Debug)]
pub enum SpotifyApiError {
    #[error("Token is none")]
    NoToken,

    #[error("Spotify Error: {0}")]
    ApiError(String),

    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),

    #[error("HTTP request failed: {0}")]
    Reqwest(#[from] reqwest::Error),

    #[error("Librespot error: {0}")]
    Librespot(#[from] librespot_core::Error),

    #[error("IO erorr: {0}")]
    IO(#[from] std::io::Error),

    #[error("{0}")]
    Generic(String),

    #[error("HTTP {0}: {1}")]
    Http(u16, String),
}
