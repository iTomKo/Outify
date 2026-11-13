//! The collection-v2 wire schema lives in protocol/proto/collection2v2.proto.
use librespot_core::{Error, Session, SpotifyUri};
use protobuf::{CodedInputStream, CodedOutputStream, rt::WireType};
use reqwest::{
    Method,
    header::{ACCEPT, CONTENT_TYPE, HeaderMap, HeaderValue},
};
use serde::Serialize;
use std::{
    collections::{HashMap, HashSet},
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

const PAGING: &str = "/collection/v2/paging";
const WRITE: &str = "/collection/v2/write";
const CONTENT: &str = "application/vnd.collection-v2.spotify.proto";
const PAGE_SIZE: usize = 300;
static UPDATE: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CollectionType {
    Tracks,
    Albums,
    Artists,
    Episodes,
    Shows,
}

impl CollectionType {
    pub fn from_uri(uri: &str) -> Result<Option<Self>, Error> {
        let kind = match uri.split(':').nth(1) {
            Some("track") => Self::Tracks,
            Some("album") => Self::Albums,
            Some("artist") => Self::Artists,
            Some("episode") => Self::Episodes,
            Some("show") => Self::Shows,
            _ => return Ok(None),
        };
        // Reject malformed native URIs before any part of a batch is written.
        if !kind.matches(uri) {
            return Err(Error::invalid_argument("invalid collection URI"));
        }
        Ok(Some(kind))
    }

    fn set(self) -> &'static str {
        match self {
            Self::Tracks | Self::Albums => "collection",
            Self::Artists => "artist",
            Self::Episodes => "listenlater",
            Self::Shows => "show",
        }
    }

    fn matches(self, uri: &str) -> bool {
        if uri.split(':').count() != 3 {
            return false;
        }
        matches!(
            (self, SpotifyUri::from_uri(uri)),
            (Self::Tracks, Ok(SpotifyUri::Track { .. }))
                | (Self::Albums, Ok(SpotifyUri::Album { .. }))
                | (Self::Artists, Ok(SpotifyUri::Artist { .. }))
                | (Self::Episodes, Ok(SpotifyUri::Episode { .. }))
                | (Self::Shows, Ok(SpotifyUri::Show { .. }))
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct SavedItem {
    pub uri: String,
    pub added_at: Option<i64>, // Unix seconds, not the local cache time.
}

#[derive(Default)]
struct Page {
    items: Vec<(SavedItem, bool)>,
    next: String,
}

pub async fn saved_tracks(session: &Session) -> Result<Vec<SavedItem>, Error> {
    saved_items(session, CollectionType::Tracks).await
}

/// Complete membership snapshot for one type, authenticated by the playback session.
pub async fn saved_items(session: &Session, kind: CollectionType) -> Result<Vec<SavedItem>, Error> {
    let username = session.username();
    let expected = crate::collection_session::identity(session)?;
    collect_pages(kind, |token| {
        let username = &username;
        let expected = &expected;
        async move {
            crate::collection_session::ensure_current(expected)?;
            let body = page_request(username, kind.set(), &token)?;
            let raw = crate::sync_request::paced(|| {
                let expected = &expected;
                let body = &body;
                async move {
                    crate::collection_session::ensure_current(expected)?;
                    session
                        .spclient()
                        .request(&Method::POST, PAGING, Some(headers()), Some(body))
                        .await
                }
            })
            .await?;
            crate::collection_session::ensure_current(expected)?;
            read_page(&raw)
        }
    })
    .await
}

async fn collect_pages<F, R>(kind: CollectionType, mut fetch: F) -> Result<Vec<SavedItem>, Error>
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
            if !track.uri.starts_with(match kind {
                CollectionType::Tracks => "spotify:track:",
                CollectionType::Albums => "spotify:album:",
                CollectionType::Artists => "spotify:artist:",
                CollectionType::Episodes => "spotify:episode:",
                CollectionType::Shows => "spotify:show:",
            }) {
                continue;
            }
            if !kind.matches(&track.uri) {
                return Err(Error::data_loss("collection contains an invalid item URI"));
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
        "collection: read {} saved {kind:?} across {} pages",
        result.len(),
        seen.len()
    );
    Ok(result)
}

fn page_request(username: &str, set: &str, token: &str) -> Result<Vec<u8>, Error> {
    let mut bytes = Vec::new();
    {
        let mut out = CodedOutputStream::vec(&mut bytes);
        out.write_string(1, username)?;
        out.write_string(2, set)?;
        out.write_string(3, token)?;
        out.write_int32(4, PAGE_SIZE as i32)?;
        out.flush()?;
    }
    Ok(bytes)
}

fn headers() -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static(CONTENT));
    headers.insert(ACCEPT, HeaderValue::from_static(CONTENT));
    headers
}

/// Save/remove native collection items. Other URI types must use their own APIs.
pub async fn set_saved(session: &Session, uris: &[String], saved: bool) -> Result<(), Error> {
    let batches = write_batches(uris)?;
    let expected = crate::collection_session::identity(session)?;
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(Error::internal)?;
    let added_at = if saved {
        i32::try_from(now.as_secs())?
    } else {
        0
    };
    for (set, items) in batches {
        for items in items.chunks(PAGE_SIZE) {
            let update_id = format!(
                "outify-{}-{}-{}",
                expected.session_id,
                now.as_nanos(),
                UPDATE.fetch_add(1, Ordering::Relaxed),
            );
            let body = write_request(
                &expected.account_id,
                set,
                items,
                saved,
                added_at,
                &update_id,
            )?;
            // Reuse the same update ID on retries, and never switch accounts mid-write.
            crate::sync_request::paced(|| {
                let expected = &expected;
                let body = &body;
                async move {
                    crate::collection_session::ensure_current(expected)?;
                    session
                        .spclient()
                        .request(&Method::POST, WRITE, Some(headers()), Some(body))
                        .await
                }
            })
            .await?;
            crate::collection_session::ensure_current(&expected)?;
        }
    }
    Ok(())
}

fn write_batches(uris: &[String]) -> Result<Vec<(&'static str, Vec<&str>)>, Error> {
    let mut collection = Vec::new();
    let mut artists = Vec::new();
    let mut episodes = Vec::new();
    let mut shows = Vec::new();
    let mut seen = HashSet::new();
    for uri in uris {
        let kind = CollectionType::from_uri(uri)?
            .ok_or_else(|| Error::invalid_argument("unsupported collection URI type"))?;
        if seen.insert(uri) {
            match kind {
                CollectionType::Tracks | CollectionType::Albums => collection.push(uri.as_str()),
                CollectionType::Artists => artists.push(uri.as_str()),
                CollectionType::Episodes => episodes.push(uri.as_str()),
                CollectionType::Shows => shows.push(uri.as_str()),
            }
        }
    }
    Ok(vec![
        ("collection", collection),
        ("artist", artists),
        ("listenlater", episodes),
        ("show", shows),
    ]
    .into_iter()
    .filter(|(_, items)| !items.is_empty())
    .collect())
}

fn write_request(
    username: &str,
    set: &str,
    uris: &[&str],
    saved: bool,
    added_at: i32,
    update_id: &str,
) -> Result<Vec<u8>, Error> {
    let mut bytes = Vec::new();
    let mut out = CodedOutputStream::vec(&mut bytes);
    out.write_string(1, username)?;
    out.write_string(2, set)?;
    for uri in uris {
        let mut item = Vec::new();
        {
            let mut out = CodedOutputStream::vec(&mut item);
            out.write_string(1, uri)?;
            out.write_int32(2, added_at)?;
            out.write_bool(3, !saved)?;
            out.flush()?;
        }
        out.write_bytes(3, &item)?;
    }
    out.write_string(4, update_id)?;
    out.flush()?;
    drop(out);
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

fn read_item(bytes: &[u8]) -> Result<(SavedItem, bool), Error> {
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
    Ok((SavedItem { uri, added_at }, removed))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    fn track(n: u8, date: i64) -> SavedItem {
        SavedItem {
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
        let raw = page_request("listener", "collection", "next-token").unwrap();
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
    fn writes_group_tracks_and_albums_separately_from_artist_follows() {
        let track = track(1, 0).uri;
        let album = track.replace(":track:", ":album:");
        let artist = track.replace(":track:", ":artist:");
        let uris = vec![track.clone(), artist.clone(), album.clone(), track.clone()];
        let batches = write_batches(&uris).unwrap();
        assert_eq!(
            batches,
            vec![
                ("collection", vec![track.as_str(), album.as_str()]),
                ("artist", vec![artist.as_str()]),
            ]
        );
        assert!(write_batches(&["spotify:track:not-an-id".into()]).is_err());
        assert!(write_batches(&[format!("{track}:extra")]).is_err());
        assert!(write_batches(&[track.replace(":track:", ":playlist:")]).is_err());
        assert!(
            write_batches(&[])
                .unwrap()
                .iter()
                .all(|(_, items)| items.is_empty())
        );
    }

    #[test]
    fn writes_encode_save_and_removal_with_a_stable_update_id() {
        let uri = track(1, 0).uri;
        for (saved, timestamp) in [(true, 1_700_000_000), (false, 0)] {
            let raw = write_request(
                "listener",
                "collection",
                &[&uri],
                saved,
                timestamp,
                "update-1",
            )
            .unwrap();
            let mut input = CodedInputStream::from_bytes(&raw);
            assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(10));
            assert_eq!(input.read_string().unwrap(), "listener");
            assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(18));
            assert_eq!(input.read_string().unwrap(), "collection");
            assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(26));
            let (item, removed) = read_item(&input.read_bytes().unwrap()).unwrap();
            assert_eq!(item.uri, uri);
            assert_eq!(item.added_at, Some(timestamp.into()));
            assert_eq!(removed, !saved);
            assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(34));
            assert_eq!(input.read_string().unwrap(), "update-1");
            assert_eq!(input.read_raw_tag_or_eof().unwrap(), None);
            assert_eq!(
                raw,
                write_request(
                    "listener",
                    "collection",
                    &[&uri],
                    saved,
                    timestamp,
                    "update-1"
                )
                .unwrap()
            );
        }
    }

    #[test]
    fn podcast_membership_uses_listenlater_and_show_instead_of_the_music_set() {
        let track = track(1, 0).uri;
        let episode = track.replace(":track:", ":episode:");
        let show = track.replace(":track:", ":show:");
        let uris = [episode.clone(), show.clone()];
        assert_eq!(
            write_batches(&uris).unwrap(),
            vec![
                ("listenlater", vec![episode.as_str()]),
                ("show", vec![show.as_str()])
            ]
        );
        assert_eq!(CollectionType::Episodes.set(), "listenlater");
        assert_eq!(CollectionType::Shows.set(), "show");
        assert!(CollectionType::Episodes.matches(&episode));
        assert!(CollectionType::Shows.matches(&show));
    }

    #[tokio::test]
    async fn album_and_artist_reads_use_their_sets_and_filter_other_item_types() {
        let track = track(1, 10);
        let mut album = track.clone();
        album.uri = album.uri.replace(":track:", ":album:");
        let mut artist = track.clone();
        artist.uri = artist.uri.replace(":track:", ":artist:");
        for (kind, expected) in [
            (CollectionType::Albums, album.clone()),
            (CollectionType::Artists, artist.clone()),
        ] {
            let raw = page_request("listener", kind.set(), "").unwrap();
            let mut input = CodedInputStream::from_bytes(&raw);
            input.read_raw_tag_or_eof().unwrap();
            input.read_string().unwrap();
            assert_eq!(input.read_raw_tag_or_eof().unwrap(), Some(18));
            assert_eq!(input.read_string().unwrap(), kind.set());
            let result = collect_pages(kind, |_| {
                let page = Page {
                    items: vec![
                        (track.clone(), false),
                        (album.clone(), false),
                        (artist.clone(), false),
                    ],
                    next: String::new(),
                };
                async move { Ok(page) }
            })
            .await
            .unwrap();
            assert_eq!(result, vec![expected]);
        }
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
        let result = collect_pages(CollectionType::Tracks, |token| {
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
        let result = collect_pages(CollectionType::Tracks, |_| {
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
        let result = collect_pages(CollectionType::Tracks, |_| {
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
