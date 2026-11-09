//! The collection-v2 wire schema lives in protocol/proto/collection2v2.proto.
use librespot_core::{Error, Session, SpotifyUri};
use protobuf::{CodedInputStream, CodedOutputStream, rt::WireType};
use reqwest::{
    Method,
    header::{ACCEPT, CONTENT_TYPE, HeaderMap, HeaderValue},
};
use serde::Serialize;
use std::collections::{HashMap, HashSet};

const PAGING: &str = "/collection/v2/paging";
const CONTENT: &str = "application/vnd.collection-v2.spotify.proto";

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct SavedTrack {
    pub uri: String,
    pub added_at: Option<i64>, // Unix seconds, not the local cache time.
}

#[derive(Default)]
struct Page {
    items: Vec<(SavedTrack, bool)>,
    next: String,
}

pub async fn saved_tracks(session: &Session) -> Result<Vec<SavedTrack>, Error> {
    let username = session.username();
    let expected = crate::collection_session::identity(session)?;
    collect_pages(|token| {
        let username = &username;
        let expected = &expected;
        async move {
            crate::collection_session::ensure_current(expected)?;
            let body = page_request(username, &token)?;
            let mut headers = HeaderMap::new();
            headers.insert(CONTENT_TYPE, HeaderValue::from_static(CONTENT));
            headers.insert(ACCEPT, HeaderValue::from_static(CONTENT));
            let raw = crate::sync_request::paced(|| {
                session.spclient().request(
                    &Method::POST,
                    PAGING,
                    Some(headers.clone()),
                    Some(&body),
                )
            })
            .await?;
            crate::collection_session::ensure_current(expected)?;
            read_page(&raw)
        }
    })
    .await
}

async fn collect_pages<F, R>(mut fetch: F) -> Result<Vec<SavedTrack>, Error>
where
    F: FnMut(String) -> R,
    R: Future<Output = Result<Page, Error>>,
{
    let mut token = String::new();
    let mut seen = HashSet::new();
    let mut order = Vec::new();
    let mut ordered = HashSet::new();
    let mut tracks = HashMap::new();
    loop {
        if !seen.insert(token.clone()) {
            return Err(Error::data_loss("collection pagination token repeated"));
        }
        let page = fetch(token).await?;
        for (track, removed) in page.items {
            if !track.uri.starts_with("spotify:track:") {
                continue;
            }
            if !matches!(
                SpotifyUri::from_uri(&track.uri),
                Ok(SpotifyUri::Track { .. })
            ) {
                return Err(Error::data_loss("collection contains an invalid track URI"));
            }
            if removed {
                tracks.remove(&track.uri);
            } else {
                if ordered.insert(track.uri.clone()) {
                    order.push(track.uri.clone());
                }
                tracks.insert(track.uri.clone(), track);
            }
        }
        token = page.next;
        if token.is_empty() {
            break;
        }
    }
    let mut result: Vec<_> = order
        .into_iter()
        .filter_map(|uri| tracks.remove(&uri))
        .collect();
    // Paging order is not a playback-context order. Keep newest likes first;
    // stable sorting preserves server order when dates are equal or absent.
    result.sort_by_key(|track| std::cmp::Reverse(track.added_at.unwrap_or(0)));
    debug!(
        "collection: read {} saved tracks across {} pages",
        result.len(),
        seen.len()
    );
    Ok(result)
}

fn page_request(username: &str, token: &str) -> Result<Vec<u8>, Error> {
    let mut bytes = Vec::new();
    {
        let mut out = CodedOutputStream::vec(&mut bytes);
        out.write_string(1, username)?;
        out.write_string(2, "collection")?;
        out.write_string(3, token)?;
        out.write_int32(4, 300)?;
        out.flush()?;
    }
    Ok(bytes)
}

fn skip(input: &mut CodedInputStream<'_>, tag: u32) -> Result<(), Error> {
    let wire =
        WireType::new(tag & 7).ok_or_else(|| Error::data_loss("invalid collection wire type"))?;
    input.skip_field(wire)?;
    Ok(())
}

fn read_page(bytes: &[u8]) -> Result<Page, Error> {
    let mut input = CodedInputStream::from_bytes(bytes);
    let mut page = Page::default();
    while let Some(tag) = input.read_raw_tag_or_eof()? {
        match tag {
            10 => page.items.push(read_item(&input.read_bytes()?)?),
            18 => page.next = input.read_string()?,
            _ => skip(&mut input, tag)?,
        }
    }
    Ok(page)
}

fn read_item(bytes: &[u8]) -> Result<(SavedTrack, bool), Error> {
    let mut input = CodedInputStream::from_bytes(bytes);
    let mut uri = None;
    let mut added_at = None;
    let mut removed = false;
    while let Some(tag) = input.read_raw_tag_or_eof()? {
        match tag {
            10 => uri = Some(input.read_string()?),
            16 => added_at = Some(i64::from(input.read_int32()?)),
            21 => added_at = Some(i64::from(input.read_fixed32()? as i32)),
            24 => removed = input.read_bool()?,
            _ => skip(&mut input, tag)?,
        }
    }
    let uri = uri
        .filter(|uri| !uri.is_empty())
        .ok_or_else(|| Error::data_loss("collection item has no URI"))?;
    Ok((SavedTrack { uri, added_at }, removed))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    fn track(n: u8, date: i64) -> SavedTrack {
        SavedTrack {
            uri: format!(
                "spotify:track:{}",
                librespot_core::SpotifyId::from_raw(&[n; 16])
                    .unwrap()
                    .to_base62()
            ),
            added_at: Some(date),
        }
    }

    #[test]
    fn request_encodes_collection_set_username_token_and_page_size() {
        let raw = page_request("listener", "next-token").unwrap();
        let mut input = CodedInputStream::from_bytes(&raw);
        assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(10));
        assert_eq!(input.read_string().unwrap(), "listener");
        assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(18));
        assert_eq!(input.read_string().unwrap(), "collection");
        assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(26));
        assert_eq!(input.read_string().unwrap(), "next-token");
        assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(32));
        assert_eq!(input.read_int32().unwrap(), 300);
    }

    #[test]
    fn codec_preserves_dates_removal_and_ignores_new_fields() {
        let saved = track(1, 1_700_000_000);
        let mut item = Vec::new();
        {
            let mut out = CodedOutputStream::vec(&mut item);
            out.write_string(1, &saved.uri).unwrap();
            out.write_int32(2, 1_700_000_000).unwrap();
            out.write_bool(3, true).unwrap();
            out.write_string(99, "future-field").unwrap();
            out.flush().unwrap();
        }
        let mut response = Vec::new();
        {
            let mut out = CodedOutputStream::vec(&mut response);
            out.write_bytes(1, &item).unwrap();
            out.write_string(2, "next").unwrap();
            out.write_string(3, "sync-token").unwrap();
            out.flush().unwrap();
        }
        let page = read_page(&response).unwrap();
        assert_eq!(page.items, vec![(saved, true)]);
        assert_eq!(page.next, "next");
        assert!(read_page(&[10, 100, 0]).is_err());
        assert!(read_item(&[]).is_err());
    }

    #[tokio::test]
    async fn paging_finishes_all_pages_filters_tombstones_and_keeps_newest_first() {
        let mut pages = VecDeque::from([
            Page {
                items: vec![(track(1, 10), false), (track(2, 20), false)],
                next: "second".into(),
            },
            Page {
                items: vec![(track(1, 10), true), (track(3, 30), false)],
                next: String::new(),
            },
        ]);
        let mut requested = Vec::new();
        let result = collect_pages(|token| {
            requested.push(token);
            let page = pages.pop_front().unwrap();
            async move { Ok(page) }
        })
        .await
        .unwrap();
        assert_eq!(requested, vec!["", "second"]);
        assert_eq!(result, vec![track(3, 30), track(2, 20)]);
    }

    #[tokio::test]
    async fn a_failed_page_never_returns_a_partial_membership_snapshot() {
        let mut calls = 0;
        let result = collect_pages(|_| {
            calls += 1;
            let result = if calls == 1 {
                Ok(Page {
                    items: vec![(track(1, 10), false)],
                    next: "second".into(),
                })
            } else {
                Err(Error::unavailable("offline"))
            };
            async move { result }
        })
        .await;
        assert!(result.is_err());
        assert_eq!(calls, 2);
    }

    #[tokio::test]
    async fn repeated_page_tokens_fail_instead_of_looping_or_returning_partial_data() {
        let mut calls = 0;
        let result = collect_pages(|_| {
            calls += 1;
            async {
                Ok(Page {
                    items: vec![],
                    next: "same".into(),
                })
            }
        })
        .await;
        assert_eq!(
            result.unwrap_err().kind,
            librespot_core::error::ErrorKind::DataLoss
        );
        assert_eq!(calls, 2);
    }
}
