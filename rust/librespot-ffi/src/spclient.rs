use librespot_core::{SpotifyId, SpotifyUri, spclient::SpClientResult};
use librespot_protocol::context::Context;

use crate::session::with_session;

pub async fn get_context(uri: &str) -> Result<Context, librespot_core::error::Error> {
    let session = match with_session(|s| s.clone()) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to clone session for get_context: {e}");
            return Err(librespot_core::Error::internal(
                "failed to clone session for get_context",
            ));
        }
    };
    let spclient = session.spclient();
    spclient.get_context(uri).await
}

pub async fn get_rootlist() -> SpClientResult {
    let session = match with_session(|s| s.clone()) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to clone session for get_rootlist: {e}");
            return Err(librespot_core::Error::internal(
                "failed to clone session for get_rootlist",
            ));
        }
    };
    let spclient = session.spclient();
    spclient.get_rootlist(0, None).await
}

pub async fn get_radio_for_track(track_uri: &SpotifyUri) -> SpClientResult {
    let session = match with_session(|s| s.clone()) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to clone session for get_radio: {e}");
            return Err(librespot_core::Error::internal(
                "failed to clone session for get_radio",
            ));
        }
    };

    let spclient = session.spclient();
    spclient.get_radio_for_track(track_uri).await
}

pub async fn get_lyrics(track_id: &SpotifyId) -> SpClientResult {
    let session = match with_session(|s| s.clone()) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to clone session for get_lyrics: {e}");
            return Err(librespot_core::Error::internal(
                "failed to clone session for get_lyrics",
            ));
        }
    };

    let spclient = session.spclient();
    spclient.get_lyrics(track_id).await
}

pub fn get_username() -> Result<String, librespot_core::error::Error> {
    match with_session(|s| s.clone()) {
        Ok(s) => Ok(s.username()),
        Err(e) => {
            error!("failed to clone session for get_username: {e}");
            Err(e)
        }
    }
}
