//! Small playback-authenticated Pathfinder client. Hashes come only from Spotify's public web assets.
use crate::{
    collection_session::{self, CollectionSession},
    native_request,
};
use http::Method;
use librespot_core::{Error, Session};
use regex::Regex;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::LazyLock,
    time::{Duration, Instant},
};
use tokio::sync::Mutex;

const ENDPOINT: &str = "https://api-partner.spotify.com/pathfinder/v2/query";
const TTL: Duration = Duration::from_secs(24 * 60 * 60);
struct Registry {
    fetched: Instant,
    hashes: HashMap<String, String>,
}
static HASHES: Mutex<Option<Registry>> = Mutex::const_new(None);
static DEFINITIONS: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"\(\s*"([A-Za-z0-9_]+)"\s*,\s*"(?:query|mutation)"\s*,\s*"([a-f0-9]{64})""#)
        .unwrap()
});
static SCRIPTS: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[a-zA-Z0-9]+\.js"#)
        .unwrap()
});
static CHUNK_ENTRIES: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?:^|[,{])\s*([0-9]+)\s*:\s*"([A-Za-z0-9_~.-]+)""#).unwrap());

async fn hash(operation: &str, rejected: Option<&str>) -> Result<String, Error> {
    let mut registry = HASHES.lock().await;
    if let Some(cached) = registry.as_ref() {
        if let Some(value) = cached.hashes.get(operation) {
            if cached.fetched.elapsed() < TTL && rejected != Some(value.as_str()) {
                return Ok(value.clone());
            }
        }
    }
    // No Spotify credentials are sent to this unauthenticated HTTP client.
    let public = reqwest::Client::builder()
        .user_agent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        .timeout(Duration::from_secs(20)).build().map_err(Error::internal)?;
    let html = public_text(&public, "https://open.spotify.com/").await?;
    let url = SCRIPTS
        .find(&html)
        .ok_or_else(|| Error::unavailable("Spotify web player script was not found"))?
        .as_str();
    let script = public_text(&public, url).await?;
    let mut hashes = parse_hashes(&script);
    let route = if operation.starts_with("search") {
        Some("xpui-routes-search")
    } else if operation == "home" {
        Some("xpui-routes-home")
    } else if operation == "browsePage" {
        Some("browse-v2")
    } else {
        None
    };
    if !hashes.contains_key(operation) {
        if let Some(url) = route.and_then(|route| route_script(&script, route)) {
            hashes.extend(parse_hashes(&public_text(&public, &url).await?));
        }
    }
    let value = hashes.get(operation).cloned().ok_or_else(|| {
        Error::unavailable(format!("Spotify web player has no {operation} operation"))
    })?;
    if let Some(cached) = registry
        .as_ref()
        .filter(|cached| cached.fetched.elapsed() < TTL)
    {
        for (name, value) in &cached.hashes {
            if name != operation {
                hashes.entry(name.clone()).or_insert_with(|| value.clone());
            }
        }
    }
    *registry = Some(Registry {
        fetched: Instant::now(),
        hashes,
    });
    Ok(value)
}

async fn public_text(client: &reqwest::Client, url: &str) -> Result<String, Error> {
    let mut response = client
        .get(url)
        .send()
        .await
        .map_err(Error::unavailable)?
        .error_for_status()
        .map_err(Error::unavailable)?;
    if response
        .content_length()
        .is_some_and(|length| length > 20 * 1024 * 1024)
    {
        return Err(Error::data_loss("Spotify public asset is too large"));
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(Error::unavailable)? {
        if bytes.len() + chunk.len() > 20 * 1024 * 1024 {
            return Err(Error::data_loss("Spotify public asset is too large"));
        }
        bytes.extend_from_slice(&chunk);
    }
    String::from_utf8(bytes).map_err(Error::from)
}

fn parse_hashes(script: &str) -> HashMap<String, String> {
    DEFINITIONS
        .captures_iter(script)
        .map(|capture| (capture[1].to_owned(), capture[2].to_owned()))
        .collect()
}

/// Resolve the search route from the JS chunk maps, not the later CSS hash map.
/// Only a fixed Spotify CDN host is used; no JavaScript is executed.
#[cfg(test)]
fn search_script(script: &str) -> Option<String> {
    route_script(script, "xpui-routes-search")
}

fn route_script(script: &str, route: &str) -> Option<String> {
    // Other modules also assign `.u` (for example `.u=[]`) before the chunk loader.
    script.split(".u=").skip(1).find_map(|runtime| {
        let (runtime, _) = runtime.split_once(".js\"")?;
        let entries: Vec<_> = CHUNK_ENTRIES.captures_iter(runtime).collect();
        let id = entries
            .iter()
            .find(|entry| &entry[2] == route)?
            .get(1)?
            .as_str();
        let hash = entries
            .iter()
            .find(|entry| {
                &entry[1] == id
                    && (8..=64).contains(&entry[2].len())
                    && entry[2].bytes().all(|byte| byte.is_ascii_hexdigit())
            })?
            .get(2)?
            .as_str();
        Some(format!(
            "https://open.spotifycdn.com/cdn/build/web-player/{route}.{hash}.js"
        ))
    })
}

pub async fn query(
    session: &Session,
    expected: &CollectionSession,
    operation: &str,
    variables: Value,
) -> Result<Value, Error> {
    collection_session::ensure_current(expected)?;
    let mut current = hash(operation, None).await?;
    for attempt in 0..2 {
        let body = serde_json::to_vec(&json!({
            "operationName": operation,
            "variables": variables,
            "extensions": { "persistedQuery": { "version": 1, "sha256Hash": current } },
        }))?;
        let raw = native_request::request(
            session,
            expected,
            Method::POST,
            ENDPOINT,
            "application/json",
            &body,
        )
        .await?;
        let response: Value = serde_json::from_slice(&raw)?;
        // Retry only a rejected persisted query: mutations may already have committed on other errors.
        if attempt == 0 && response.get("data").is_none_or(Value::is_null) && stale_hash(&response)
        {
            current = hash(operation, Some(&current)).await?;
            continue;
        }
        return decode(response);
    }
    Err(Error::unavailable(
        "Spotify rejected the refreshed query hash",
    ))
}

fn stale_hash(response: &Value) -> bool {
    response
        .get("errors")
        .and_then(Value::as_array)
        .is_some_and(|errors| {
            errors.iter().any(|error| {
                error.get("message").and_then(Value::as_str) == Some("PersistedQueryNotFound")
                    || error.pointer("/extensions/code").and_then(Value::as_str)
                        == Some("PERSISTED_QUERY_NOT_FOUND")
            })
        })
}

fn decode(response: Value) -> Result<Value, Error> {
    if let Some(errors) = response.get("errors").and_then(Value::as_array) {
        if !errors.is_empty() {
            return Err(Error::failed_precondition(format!(
                "Spotify rejected the operation: {errors:?}"
            )));
        }
    }
    response
        .get("data")
        .filter(|value| !value.is_null())
        .cloned()
        .ok_or_else(|| Error::data_loss("Pathfinder response has no data"))
}

pub fn username(uri: &str) -> Result<&str, Error> {
    uri.strip_prefix("spotify:user:")
        .filter(|name| {
            !name.trim().is_empty() && !name.contains(':') && !name.chars().any(char::is_control)
        })
        .ok_or_else(|| Error::invalid_argument("expected a Spotify user URI"))
}

pub async fn follow_users(session: &Session, uris: &[String], follow: bool) -> Result<(), Error> {
    let usernames = uris
        .iter()
        .map(|uri| username(uri).map(str::to_owned))
        .collect::<Result<Vec<_>, _>>()?;
    let expected = collection_session::identity(session)?;
    let operation = if follow {
        "followUsers"
    } else {
        "unfollowUsers"
    };
    for batch in usernames.chunks(100) {
        let data = query(session, &expected, operation, json!({"usernames": batch})).await?;
        confirm_follows(&data, operation, batch)?;
    }
    Ok(())
}

fn confirm_follows(data: &Value, operation: &str, usernames: &[String]) -> Result<(), Error> {
    let responses = data
        .get(operation)
        .and_then(|value| value.get("responses"))
        .and_then(Value::as_array)
        .ok_or_else(|| Error::data_loss("user follow operation was not confirmed"))?;
    for username in usernames {
        if !responses.iter().any(|response| {
            response.get("username").and_then(Value::as_str) == Some(username.as_str())
                && response.get("result").and_then(Value::as_bool) == Some(true)
        }) {
            return Err(Error::failed_precondition(
                "Spotify did not confirm every user follow change",
            ));
        }
    }
    Ok(())
}

pub async fn is_following(session: &Session, user_uri: &str) -> Result<bool, Error> {
    username(user_uri)?;
    let expected = collection_session::identity(session)?;
    let data = query(
        session,
        &expected,
        "isFollowingUsers",
        json!({"uris": [user_uri]}),
    )
    .await?;
    decode_following(&data, user_uri)
}

fn decode_following(data: &Value, user_uri: &str) -> Result<bool, Error> {
    let user = data
        .get("users")
        .and_then(Value::as_array)
        .and_then(|users| {
            users
                .iter()
                .find(|user| user.get("uri").and_then(Value::as_str) == Some(user_uri))
        })
        .ok_or_else(|| Error::data_loss("Spotify did not return the requested user"))?;
    if user.get("__typename").and_then(Value::as_str) == Some("NotFound") {
        return Ok(false);
    }
    user.get("following")
        .and_then(Value::as_bool)
        .ok_or_else(|| Error::data_loss("Spotify did not return user follow state"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discovers_hashes_without_pinning_a_web_player_release() {
        let hash = "a".repeat(64);
        let script = format!(
            r#"x=new aQ.l("followUsers","mutation","{hash}",null), y=new X("getEpisodeOrChapter", "query", "{hash}", null)"#
        );
        let hashes = parse_hashes(&script);
        assert_eq!(hashes.get("followUsers"), Some(&hash));
        assert_eq!(hashes.get("getEpisodeOrChapter"), Some(&hash));
        assert!(parse_hashes(r#"("followUsers","mutation","bad",null)"#).is_empty());
    }

    #[tokio::test]
    #[ignore = "downloads public Spotify web assets; no authentication is used"]
    async fn public_web_assets_expose_the_required_operations() {
        for operation in [
            "followUsers",
            "unfollowUsers",
            "isFollowingUsers",
            "getEpisodeOrChapter",
            "home",
            "browsePage",
        ] {
            let hash = hash(operation, None).await.unwrap();
            assert_eq!(hash.len(), 64);
        }
    }

    #[tokio::test]
    #[ignore = "downloads public Spotify search assets; no authentication is used"]
    async fn public_search_bundle_exposes_all_search_operations() {
        for operation in [
            "searchTracks",
            "searchArtists",
            "searchAlbums",
            "searchPlaylists",
            "searchPodcasts",
            "searchEpisodes",
            "searchAudiobooks",
        ] {
            assert_eq!(hash(operation, None).await.unwrap().len(), 64);
        }
    }

    #[test]
    fn discovers_the_search_chunk_without_confusing_its_css_hash_or_running_js() {
        let runtime = r#"e.u=[],e.s=[];r.u=e=>({44:"xpui-routes-search",55:"other"}[e]||e)+"."+{44:"bdb477e8",55:"11223344"}[e]+".js",r.css=e=>({44:"bad0cafe"}[e])+".css";t.u=[]"#;
        assert_eq!(
            search_script(runtime).as_deref(),
            Some("https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.bdb477e8.js")
        );
        assert!(search_script(r#"r.u=e=>({44:"xpui-routes-search"}[e])+".js""#).is_none());
        assert!(
            search_script(
                r#"r.u=e=>({44:"xpui-routes-search"}[e])+"."+{44:"../../other"}[e]+".js""#
            )
            .is_none()
        );
    }

    #[test]
    fn discovers_browse_from_its_named_chunk_not_the_obsolete_browse_route() {
        let runtime = r#"r.u=e=>({3719:"browse-v2"}[e]||e)+"."+{3719:"e67c81d6"}[e]+".js",r.css=e=>({3719:"2578e80e"}[e])+".css""#;
        assert_eq!(
            route_script(runtime, "browse-v2").as_deref(),
            Some("https://open.spotifycdn.com/cdn/build/web-player/browse-v2.e67c81d6.js")
        );
    }

    #[test]
    fn rejects_partial_mutations_and_only_retries_a_missing_persisted_query() {
        assert!(
            confirm_follows(
                &json!({"followUsers":{"responses":[{"username":"me","result":true}]}}),
                "followUsers",
                &["me".into()]
            )
            .is_ok()
        );
        assert!(
            confirm_follows(
                &json!({"followUsers":{"responses":[{"username":"me","result":false}]}}),
                "followUsers",
                &["me".into()]
            )
            .is_err()
        );
        assert!(confirm_follows(&json!({}), "followUsers", &["me".into()]).is_err());
        assert!(decode(json!({"data":{},"errors":[{"message":"denied"}]})).is_err());
        assert!(stale_hash(
            &json!({"errors":[{"message":"PersistedQueryNotFound"}]})
        ));
        assert!(!stale_hash(&json!({"errors":[{"message":"denied"}]})));
        assert!(username("spotify:user:ö /?").is_ok());
        assert!(username("spotify:user:").is_err());
        assert!(username("spotify:user:a:playlist:b").is_err());
    }

    #[test]
    fn follow_state_uses_the_following_field_and_never_another_users_state() {
        let uri = "spotify:user:listener";
        assert!(decode_following(&json!({"users":[{"uri":uri,"following":true}]}), uri).unwrap());
        assert!(
            !decode_following(&json!({"users":[{"uri":uri,"__typename":"NotFound"}]}), uri)
                .unwrap()
        );
        assert!(
            decode_following(
                &json!({"users":[{"uri":"spotify:user:other","following":true}]}),
                uri
            )
            .is_err()
        );
        assert!(decode_following(&json!({"users":[{"uri":uri}]}), uri).is_err());
    }
}
