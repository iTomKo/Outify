//! Spotify's per-category Pathfinder search, using playback authentication.
use crate::{collection_session, pathfinder};
use librespot_core::{Error, Session, SpotifyId, SpotifyUri};
use serde_json::{Value, json};
use std::collections::HashSet;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum SearchType {
    Track,
    Artist,
    Album,
    Playlist,
    Show,
    Episode,
    Audiobook,
}

impl SearchType {
    fn parse(value: &str) -> Result<Self, Error> {
        match value {
            "track" => Ok(Self::Track),
            "artist" => Ok(Self::Artist),
            "album" => Ok(Self::Album),
            "playlist" => Ok(Self::Playlist),
            "show" => Ok(Self::Show),
            "episode" => Ok(Self::Episode),
            "audiobook" => Ok(Self::Audiobook),
            _ => Err(Error::invalid_argument(format!(
                "unsupported search type: {value}"
            ))),
        }
    }

    fn operation(self) -> &'static str {
        match self {
            Self::Track => "searchTracks",
            Self::Artist => "searchArtists",
            Self::Album => "searchAlbums",
            Self::Playlist => "searchPlaylists",
            Self::Show => "searchPodcasts",
            Self::Episode => "searchEpisodes",
            Self::Audiobook => "searchAudiobooks",
        }
    }

    fn section(self) -> &'static str {
        match self {
            Self::Track => "tracksV2",
            Self::Artist => "artists",
            Self::Album => "albumsV2",
            Self::Playlist => "playlists",
            Self::Show => "podcasts",
            Self::Episode => "episodes",
            Self::Audiobook => "audiobooks",
        }
    }

    fn matches(self, data: &Value, uri: &str) -> bool {
        if uri.split(':').count() != 3 {
            return false;
        }
        match (
            self,
            data.get("__typename").and_then(Value::as_str),
            SpotifyUri::from_uri(uri),
        ) {
            (Self::Track, Some("Track"), Ok(SpotifyUri::Track { .. }))
            | (Self::Artist, Some("Artist"), Ok(SpotifyUri::Artist { .. }))
            | (Self::Album, Some("Album"), Ok(SpotifyUri::Album { .. }))
            | (Self::Playlist, Some("Playlist"), Ok(SpotifyUri::Playlist { .. }))
            | (Self::Show, Some("Podcast"), Ok(SpotifyUri::Show { .. }))
            | (Self::Episode, Some("Episode"), Ok(SpotifyUri::Episode { .. }))
            | (Self::Audiobook, Some("Audiobook"), Ok(SpotifyUri::Show { .. })) => true,
            (Self::Audiobook, Some("Audiobook"), _) => uri
                .strip_prefix("spotify:audiobook:")
                .is_some_and(|id| SpotifyId::from_base62(id).is_ok()),
            _ => false,
        }
    }
}

struct SearchRequest<'a> {
    term: &'a str,
    types: Vec<SearchType>,
    limit: i32,
    offset: i32,
}

impl<'a> SearchRequest<'a> {
    fn new(
        query: &'a str,
        types: &str,
        limit: Option<i32>,
        offset: Option<i32>,
    ) -> Result<Self, Error> {
        let mut seen = HashSet::new();
        let types = types
            .split(',')
            .map(|kind| SearchType::parse(kind.trim()))
            .collect::<Result<Vec<_>, _>>()?
            .into_iter()
            .filter(|kind| seen.insert(*kind))
            .collect();
        let limit = limit.unwrap_or(20);
        let offset = offset.unwrap_or(0);
        if !(1..=50).contains(&limit) || offset < 0 {
            return Err(Error::invalid_argument(
                "search limit must be 1..50 and offset must be non-negative",
            ));
        }
        Ok(Self {
            term: query.trim(),
            types,
            limit,
            offset,
        })
    }

    fn variables(&self) -> Value {
        json!({
            "searchTerm": self.term, "offset": self.offset, "limit": self.limit,
            "numberOfTopResults": self.limit,
            "includeAudiobooks": self.types.contains(&SearchType::Audiobook),
            "includePreReleases": false, "includeAlbumPreReleases": false,
            "includeAuthors": false, "includeEpisodeContentRatingsV2": false,
        })
    }
}

pub async fn search(
    session: &Session,
    query: &str,
    types: &str,
    limit: Option<i32>,
    offset: Option<i32>,
) -> Result<Vec<String>, Error> {
    let request = SearchRequest::new(query, types, limit, offset)?;
    if request.term.is_empty() {
        return Ok(Vec::new());
    }
    let expected = collection_session::identity(session)?;
    let mut uris = Vec::new();
    for &kind in &request.types {
        let data =
            pathfinder::query(session, &expected, kind.operation(), request.variables()).await?;
        uris.extend(decode(&data, kind)?);
    }
    collection_session::ensure_current(&expected)?;
    Ok(uris)
}

fn decode(data: &Value, kind: SearchType) -> Result<Vec<String>, Error> {
    let items = data
        .get("searchV2")
        .and_then(|search| search.get(kind.section()))
        .and_then(|section| section.get("items"))
        .and_then(Value::as_array)
        .ok_or_else(|| {
            Error::data_loss(format!("{} response has no result items", kind.operation()))
        })?;
    let mut seen = HashSet::new();
    Ok(items
        .iter()
        .filter_map(|hit| {
            // Track results wrap the entity in `item`; the other categories wrap it directly in `data`.
            let entity = hit.get("item").unwrap_or(hit).get("data")?;
            let uri = entity.get("uri")?.as_str()?;
            (kind.matches(entity, uri) && seen.insert(uri)).then(|| uri.to_owned())
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn uri(kind: &str, n: u8) -> String {
        format!(
            "spotify:{kind}:{}",
            SpotifyId::from_raw(&[n; 16]).unwrap().to_base62()
        )
    }

    #[test]
    fn requests_use_typed_queries_trim_terms_and_preserve_offset_and_limit() {
        let request = SearchRequest::new(
            "  sigur rós & 日本語  ",
            "track, artist,track,show",
            Some(37),
            Some(20),
        )
        .unwrap();
        assert_eq!(
            request.types,
            [SearchType::Track, SearchType::Artist, SearchType::Show]
        );
        let variables = request.variables();
        assert_eq!(variables["searchTerm"], "sigur rós & 日本語");
        assert_eq!(variables["offset"], 20);
        assert_eq!(variables["limit"], 37);
        assert_eq!(SearchType::Show.operation(), "searchPodcasts");
        assert_eq!(
            SearchRequest::new("q", "album", None, None)
                .unwrap()
                .variables()["limit"],
            20
        );
        for (types, limit, offset) in [
            ("bad", None, None),
            ("", None, None),
            ("track,", None, None),
            ("track", Some(0), None),
            ("track", Some(51), None),
            ("track", None, Some(-1)),
        ] {
            assert!(SearchRequest::new("q", types, limit, offset).is_err());
        }
    }

    #[test]
    fn decodes_each_category_without_reordering_or_accepting_unavailable_entities() {
        for (kind, typename, uri_kind) in [
            (SearchType::Track, "Track", "track"),
            (SearchType::Artist, "Artist", "artist"),
            (SearchType::Album, "Album", "album"),
            (SearchType::Playlist, "Playlist", "playlist"),
            (SearchType::Show, "Podcast", "show"),
            (SearchType::Episode, "Episode", "episode"),
            (SearchType::Audiobook, "Audiobook", "show"),
        ] {
            let a = uri(uri_kind, 1);
            let b = uri(uri_kind, 2);
            let hit = |uri: &str| {
                let wrapper = json!({"data":{"__typename":typename,"uri":uri}});
                if kind == SearchType::Track {
                    json!({"item":wrapper})
                } else {
                    wrapper
                }
            };
            let data = json!({"searchV2":{kind.section():{"items":[hit(&b),null,{"data":null},{"data":{"__typename":"NotFound","uri":a}},hit(&a),hit(&b),hit("spotify:track:bad")]}}});
            assert_eq!(decode(&data, kind).unwrap(), [b, a]);
            assert!(decode(&json!({}), kind).is_err());
            assert_eq!(
                decode(&json!({"searchV2":{kind.section():{"items":[]}}}), kind).unwrap(),
                Vec::<String>::new()
            );
        }
    }

    #[tokio::test]
    async fn searching_requires_playback_auth_even_without_oauth_credentials() {
        let client = crate::spotify::client::SpotifyClient::new();
        assert!(matches!(
            client
                .search(
                    "queen",
                    "track,artist,album,playlist,show,episode",
                    None,
                    None
                )
                .await,
            Err(crate::spotify::error::SpotifyApiError::Librespot(_))
        ));
    }
}
