use librespot_core::{SpotifyId, SpotifyUri, spclient::SpClientResult};
use librespot_protocol::context::Context;
use librespot_protocol::context_page::ContextPage;
use std::collections::{HashSet, VecDeque};

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

/// A collection resync must finish every page before replacing local membership.
pub async fn get_collection_uris(query: Option<&str>) -> Result<Vec<String>, librespot_core::Error> {
    let session = with_session(|session| session.clone())?;
    let uri = format!(
        "spotify:user:{}:collection{}",
        session.username(),
        query.unwrap_or_default(),
    );
    let client = session.spclient();
    let context = client.get_context(&uri).await?;
    collect_collection_pages(context, |url| {
        let client = session.spclient();
        async move {
            let body = client.get_next_page(&url).await?;
            let body = std::str::from_utf8(&body)?;
            Ok(protobuf_json_mapping::parse_from_str::<ContextPage>(body)?)
        }
    })
    .await
}

async fn collect_collection_pages<F, Fut>(
    context: Context,
    mut fetch: F,
) -> Result<Vec<String>, librespot_core::Error>
where
    F: FnMut(String) -> Fut,
    Fut: Future<Output = Result<ContextPage, librespot_core::Error>>,
{
    if context.loading() {
        return Err(librespot_core::Error::unavailable(
            "Collection is still loading",
        ));
    }
    if context.uri().is_empty() && context.pages.is_empty() {
        return Err(librespot_core::Error::data_loss("Collection response is empty"));
    }
    let mut pages = VecDeque::from(context.pages);
    let mut fetched = HashSet::new();
    let mut tracks = HashSet::new();
    let mut uris = Vec::new();
    while let Some(page) = pages.pop_front() {
        if page.tracks.is_empty() && !page.page_url().is_empty() {
            let url = page.page_url().to_owned();
            if fetched.insert(url.clone()) {
                pages.push_front(fetch(url).await?);
                continue;
            }
        }
        if page.loading() {
            return Err(librespot_core::Error::unavailable(
                "Collection page is still loading",
            ));
        }
        for track in &page.tracks {
            let uri = track.uri();
            if uri.is_empty() {
                return Err(librespot_core::Error::data_loss(
                    "Collection track has no URI",
                ));
            }
            if tracks.insert(uri.to_owned()) {
                uris.push(uri.to_owned());
            }
        }
        let next = page.next_page_url();
        if !next.is_empty() && fetched.insert(next.to_owned()) {
            pages.push_back(fetch(next.to_owned()).await?);
        }
    }
    Ok(uris)
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
