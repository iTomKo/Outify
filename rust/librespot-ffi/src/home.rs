//! Music-first home recommendations, authenticated by the existing playback login.
use crate::{
    collection_session::{self, CollectionSession},
    pathfinder,
};
use librespot_core::{Error, Session, SpotifyUri};
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::HashSet;

const MADE_FOR_YOU: &str = "spotify:page:0JQ5DAt0tbjZptfcdMSKl3";

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HomeItem {
    uri: String,
    kind: &'static str,
    title: String,
    subtitle: String,
    image_url: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HomeSection {
    id: String,
    title: String,
    items: Vec<HomeItem>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HomeFeed {
    session: CollectionSession,
    recent: Vec<HomeItem>,
    picked_albums: Vec<HomeItem>,
    sections: Vec<HomeSection>,
    warnings: Vec<&'static str>,
}

fn home_variables(zone: &str) -> Value {
    json!({"homeEndUserIntegration":"INTEGRATION_WEB_PLAYER", "timeZone":zone,
        "sp_t":"", "facet":"", "sectionItemsLimit":20, "includeEpisodeContentRatingsV2":false})
}

fn made_variables() -> Value {
    json!({"uri":MADE_FOR_YOU, "pagePagination":{"offset":0,"limit":20},
        "sectionPagination":{"offset":0,"limit":20}, "browseEndUserIntegration":"INTEGRATION_WEB_PLAYER",
        "includeEpisodeContentRatingsV2":false})
}

pub async fn fetch(
    session: &Session,
    expected: &CollectionSession,
    zone: &str,
) -> Result<HomeFeed, Error> {
    if &collection_session::identity(session)? != expected {
        return Err(Error::unavailable(
            "Home belongs to a different playback login",
        ));
    }
    collection_session::ensure_current(expected)?;
    if zone.is_empty() || zone.len() > 100 || zone.chars().any(char::is_control) {
        return Err(Error::invalid_argument("Invalid home time zone"));
    }
    let (home, made) = tokio::join!(
        pathfinder::query(session, expected, "home", home_variables(zone)),
        pathfinder::query(session, expected, "browsePage", made_variables()),
    );
    collection_session::ensure_current(expected)?;
    merge(expected.clone(), home, made)
}

fn merge(
    session: CollectionSession,
    home: Result<Value, Error>,
    made: Result<Value, Error>,
) -> Result<HomeFeed, Error> {
    let mut feed = HomeFeed {
        session,
        recent: Vec::new(),
        picked_albums: Vec::new(),
        sections: Vec::new(),
        warnings: Vec::new(),
    };
    let mut readable = false;
    for (source, result, pointer) in [
        ("home", home, "/home/sectionContainer/sections/items"),
        ("madeForYou", made, "/browse/sections/items"),
    ] {
        let sections = result.and_then(|data| {
            data.pointer(pointer)
                .and_then(Value::as_array)
                .cloned()
                .ok_or_else(|| Error::data_loss(format!("{source} response has no sections")))
        });
        match sections {
            Ok(sections) => {
                readable = true;
                for (index, section) in sections.iter().take(40).enumerate() {
                    let kind = section
                        .pointer("/data/__typename")
                        .and_then(Value::as_str)
                        .unwrap_or("");
                    let items = section
                        .pointer("/sectionItems/items")
                        .and_then(Value::as_array)
                        .map(|items| items.iter().filter_map(item).take(30).collect::<Vec<_>>())
                        .unwrap_or_default();
                    if source == "home" && kind == "HomeShortsSectionData" {
                        feed.recent.extend(items);
                        continue;
                    }
                    if source == "home" && kind != "HomeGenericSectionData" {
                        continue;
                    }
                    if items.is_empty() {
                        continue;
                    }
                    let title = section
                        .pointer("/data/title/transformedLabel")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .trim()
                        .to_owned();
                    if title.is_empty() {
                        continue;
                    }
                    feed.sections.push(HomeSection {
                        id: format!("{source}-{index}"),
                        title,
                        items,
                    });
                }
            }
            Err(error) => {
                warn!("Cannot load {source} home feed: {error}");
                feed.warnings.push(source);
            }
        }
    }
    if !readable {
        return Err(Error::unavailable(
            "Spotify home and Made for You are unavailable",
        ));
    }
    let mut recent = HashSet::new();
    feed.recent.retain(|item| recent.insert(item.uri.clone()));
    feed.recent.truncate(10);
    let mut albums = HashSet::new();
    for section in &feed.sections {
        for item in &section.items {
            if item.kind == "album"
                && feed.picked_albums.len() < 20
                && albums.insert(item.uri.clone())
            {
                feed.picked_albums.push(item.clone());
            }
        }
    }
    let mut shown = albums;
    let mut titles = HashSet::new();
    feed.sections.retain_mut(|section| {
        if titles.contains(&section.title) {
            return false;
        }
        section.items.retain(|item| shown.insert(item.uri.clone()));
        if section.items.is_empty() {
            return false;
        }
        titles.insert(section.title.clone());
        true
    });
    Ok(feed)
}

fn item(wrapper: &Value) -> Option<HomeItem> {
    let entity = wrapper.pointer("/content/data")?;
    let uri = wrapper.get("uri").or_else(|| entity.get("uri"))?.as_str()?;
    if entity
        .pointer("/playability/playable")
        .and_then(Value::as_bool)
        == Some(false)
        || entity.get("isPlayable").and_then(Value::as_bool) == Some(false)
    {
        return None;
    }
    let kind = match (
        entity.get("__typename")?.as_str()?,
        SpotifyUri::from_uri(uri).ok()?,
    ) {
        ("Album", SpotifyUri::Album { .. }) => "album",
        ("Playlist", SpotifyUri::Playlist { .. }) => "playlist",
        ("Artist", SpotifyUri::Artist { .. }) => "artist",
        ("Track", SpotifyUri::Track { .. }) => "track",
        _ => return None, // No podcast/show shelves in this music-first home.
    };
    let title = entity
        .get("name")
        .or_else(|| entity.pointer("/profile/name"))?
        .as_str()?
        .trim();
    if title.is_empty() {
        return None;
    }
    let artists = entity
        .pointer("/artists/items")
        .and_then(Value::as_array)
        .map(|artists| {
            artists
                .iter()
                .filter_map(|a| a.pointer("/profile/name").or_else(|| a.get("name")))
                .filter_map(Value::as_str)
                .filter(|name| !name.is_empty())
                .collect::<Vec<_>>()
                .join(", ")
        })
        .unwrap_or_default();
    let subtitle = if !artists.is_empty() {
        artists
    } else {
        entity
            .pointer("/ownerV2/data/name")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned()
    };
    let image_url = [
        "/coverArt/sources",
        "/images/items/0/sources",
        "/visuals/avatarImage/sources",
        "/albumOfTrack/coverArt/sources",
    ]
    .iter()
    .find_map(|path| {
        entity
            .pointer(path)
            .and_then(Value::as_array)
            .and_then(|sources| {
                sources
                    .iter()
                    .filter_map(|source| source.get("url").and_then(Value::as_str))
                    .find(|url| url.starts_with("https://"))
                    .map(str::to_owned)
            })
    });
    Some(HomeItem {
        uri: uri.to_owned(),
        kind,
        title: title.to_owned(),
        subtitle,
        image_url,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn identity() -> CollectionSession {
        CollectionSession {
            account_id: "listener".into(),
            session_id: "login".into(),
        }
    }
    fn card(kind: &str, name: &str) -> Value {
        let spotify_kind = kind.to_lowercase();
        json!({"uri":format!("spotify:{spotify_kind}:4cOdK2wGLETKBW3PvgPWqT"),
            "content":{"data":{"__typename":kind,"name":name,"coverArt":{"sources":[{"url":"https://i.scdn.co/image/cover"}]}}}})
    }
    fn shelf(kind: &str, title: &str, items: Vec<Value>) -> Value {
        json!({"data":{"__typename":kind,"title":{"transformedLabel":title}},"sectionItems":{"items":items}})
    }
    #[test]
    fn home_preserves_personalized_order_extracts_albums_and_deduplicates_shelves() {
        let album = card("Album", "Picked album");
        let playlist = card("Playlist", "Mix");
        let data = json!({"home":{"sectionContainer":{"sections":{"items":[
            shelf("HomeShortsSectionData", "", vec![album.clone(),album.clone()]),
            shelf("HomeGenericSectionData", "Picked for you", vec![album.clone(),playlist.clone()]),
            shelf("HomeGenericSectionData", "Podcasts", vec![card("Podcast", "Show")])
        ]}}}});
        let made = json!({"browse":{"sections":{"items":[shelf("BrowseGenericSectionData", "Made for you", vec![playlist])]}}});
        let feed = merge(identity(), Ok(data), Ok(made)).unwrap();
        assert_eq!(feed.recent.len(), 1);
        assert_eq!(feed.picked_albums[0].title, "Picked album");
        assert_eq!(feed.sections.len(), 1);
        assert_eq!(feed.sections[0].items[0].kind, "playlist");
        assert!(feed.warnings.is_empty());
        let wire = serde_json::to_value(feed).unwrap();
        assert_eq!(wire["session"]["accountId"], "listener");
        assert_eq!(
            wire["pickedAlbums"][0]["imageUrl"],
            "https://i.scdn.co/image/cover"
        );
    }
    #[test]
    fn malformed_and_unavailable_entities_are_not_displayed_as_recommendations() {
        assert!(item(&card("Album", "")).is_none());
        assert!(item(&card("Podcast", "Podcast")).is_none());
        let mut unavailable = card("Track", "Not playable");
        unavailable["content"]["data"]["playability"] = json!({"playable":false});
        assert!(item(&unavailable).is_none());
        let mut wrong = card("Album", "Wrong");
        wrong["uri"] = json!("spotify:track:4cOdK2wGLETKBW3PvgPWqT");
        assert!(item(&wrong).is_none());
        assert!(item(&json!({"uri":"spotify:album:bad","content":{"data":{"__typename":"Album","name":"Bad"}}})).is_none());
    }
    #[test]
    fn partial_feeds_work_but_failure_is_not_mistaken_for_an_empty_feed() {
        let home = json!({"home":{"sectionContainer":{"sections":{"items":[]}}}});
        assert_eq!(
            merge(identity(), Ok(home), Err(Error::unavailable("no made")))
                .unwrap()
                .warnings,
            ["madeForYou"]
        );
        assert!(merge(identity(), Ok(json!({})), Ok(json!({}))).is_err());
        assert!(
            merge(
                identity(),
                Err(Error::unavailable("offline")),
                Err(Error::unavailable("offline"))
            )
            .is_err()
        );
        assert_eq!(home_variables("Europe/Prague")["timeZone"], "Europe/Prague");
        assert_eq!(made_variables()["uri"], MADE_FOR_YOU);
    }
    #[tokio::test]
    async fn home_never_falls_back_to_oauth_without_playback_authentication() {
        let session = Session::new(Default::default(), None);
        assert!(fetch(&session, &identity(), "UTC").await.is_err());
    }
}
