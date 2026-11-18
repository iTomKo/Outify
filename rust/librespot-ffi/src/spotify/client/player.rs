use super::SpotifyClient;
use crate::{
    collection_session::CollectionSession, spotify::error::SpotifyApiError,
    types::responses::DevicesResponse,
};
use reqwest::StatusCode;

impl SpotifyClient {
    pub async fn get_devices(&self) -> Result<DevicesResponse, SpotifyApiError> {
        Ok(crate::spirc::devices().await?)
    }

    pub async fn transfer_playback(
        &self,
        device_id: String,
        expected: CollectionSession,
    ) -> Result<StatusCode, SpotifyApiError> {
        crate::spirc::transfer_device(device_id, expected).await?;
        Ok(StatusCode::NO_CONTENT)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn device_operations_require_connect_and_never_fall_back_to_oauth() {
        let client = SpotifyClient::new();
        assert!(matches!(
            client.get_devices().await,
            Err(SpotifyApiError::Librespot(_))
        ));
        let expected = CollectionSession {
            account_id: "account".into(),
            session_id: "session".into(),
        };
        assert!(matches!(
            client.transfer_playback("target".into(), expected).await,
            Err(SpotifyApiError::Librespot(_))
        ));
    }
}
