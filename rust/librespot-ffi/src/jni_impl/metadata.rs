use jni::{
    objects::{JClass, JString},
    sys::jstring,
};
use librespot_core::{Session, SpotifyUri};
use librespot_metadata::Metadata;

use crate::{outifyuri::OutifyUri, session::with_session};

// From librespot_metadata
const SPOTIFY_ITEM_TYPE_ALBUM: &str = "album";
const SPOTIFY_ITEM_TYPE_ARTIST: &str = "artist";
#[allow(unused)]
const SPOTIFY_ITEM_TYPE_EPISODE: &str = "episode";
const SPOTIFY_ITEM_TYPE_PLAYLIST: &str = "playlist";
#[allow(unused)]
const SPOTIFY_ITEM_TYPE_SHOW: &str = "show";
const SPOTIFY_ITEM_TYPE_TRACK: &str = "track";
#[allow(unused)]
const SPOTIFY_ITEM_TYPE_LOCAL: &str = "local";
#[allow(unused)]
const SPOTIFY_ITEM_TYPE_UNKNOWN: &str = "unknown";

#[unsafe(export_name = "Java_cc_tomko_outify_data_metadata_NativeMetadata_getNativeMetadata")]
pub extern "system" fn get_native_metadata(
    mut env: jni::JNIEnv,
    _this: JClass,
    juri: JString,
) -> jstring {
    let uri: String = match env.get_string(&juri) {
        Ok(u) => u.into(),
        Err(e) => {
            error!("jni get_string failed for metadata uri: {e}");
            return std::ptr::null_mut();
        }
    };

    let outify_uri = OutifyUri::from_uri(&uri);
    let uri_string = outify_uri.to_uri();

    let spotify_uri = match SpotifyUri::from_uri(uri_string.as_str()) {
        Ok(u) => u,
        Err(e) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid Spotify URI: {}", e),
            );
            return std::ptr::null_mut();
        }
    };

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_native_metadata");
            return std::ptr::null_mut();
        }
    };

    let result: Result<Option<String>, librespot_core::error::Error> = match with_session(|session| {
        rt.block_on(async move {
            match spotify_uri.item_type() {
                SPOTIFY_ITEM_TYPE_TRACK => get_track_metadata(&session, &spotify_uri).await,
                SPOTIFY_ITEM_TYPE_ALBUM => get_album_metadata(&session, &spotify_uri).await,
                SPOTIFY_ITEM_TYPE_ARTIST => get_artist_metadata(&session, &spotify_uri).await,
                SPOTIFY_ITEM_TYPE_PLAYLIST => get_playlist_metadata(&session, &spotify_uri).await,
                SPOTIFY_ITEM_TYPE_SHOW => get_show_metadata(&session, &spotify_uri).await,
                SPOTIFY_ITEM_TYPE_EPISODE => get_episode_metadata(&session, &spotify_uri).await,
                &_ => Ok(None),
            }
        })
    }) {
        Ok(r) => r,
        Err(e) => {
            error!("with_session failed for metadata: {e}");
            return std::ptr::null_mut();
        },
    };

    match result {
        Ok(Some(json)) => match env.new_string(&json) {
            Ok(jni_str) => jni_str.into_raw(),
            Err(e) => {
                error!("jni new_string failed for metadata result: {e}");
                std::ptr::null_mut()
            }
        },
        Ok(None) => {
            // no metadata found
            let error_json = r#"{"error": {"type": "not_found", "message": "no metadata"}}"#;
            match env.new_string(error_json) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            // Inspect error kind for rate-limit
            match e.kind {
                librespot_core::error::ErrorKind::ResourceExhausted => {
                    let err = serde_json::json!({
                        "error": {
                            "type": "rate_limit",
                            "retry_after_seconds": null,
                            "message": format!("Rate limited: {}", e)
                        }
                    });
                    let err_str = err.to_string();
                    match env.new_string(&err_str) {
                        Ok(s) => s.into_raw(),
                        Err(_) => std::ptr::null_mut(),
                    }
                }
                _ => {
                    let err = serde_json::json!({
                        "error": {
                            "type": "unknown",
                            "message": format!("{}", e)
                        }
                    });
                    let err_str = err.to_string();
                    match env.new_string(&err_str) {
                        Ok(s) => s.into_raw(),
                        Err(_) => std::ptr::null_mut(),
                    }
                }
            }
        }
    }
}

// Retrieves the album metadata as JSON
async fn get_album_metadata(
    session: &Session,
    spotify_uri: &SpotifyUri,
) -> Result<Option<String>, librespot_core::error::Error> {
    match librespot_metadata::Album::get(session, &spotify_uri).await {
        Ok(metadata) => {
            let album = crate::metadata::track::AlbumJson::from(&metadata);
            Ok(convert_to_string(&album))
        }
        Err(e) => Err(e),
    }
}

// Retrieves the album metadata as JSON
async fn get_track_metadata(
    session: &Session,
    spotify_uri: &SpotifyUri,
) -> Result<Option<String>, librespot_core::error::Error> {
    match librespot_metadata::Track::get(session, &spotify_uri).await {
        Ok(metadata) => {
            let mut track = crate::metadata::track::TrackJson::from(&metadata);
            
            // Fetch full album metadata to get complete artist info
            let album_uri = SpotifyUri::from_uri(&track.album.uri).ok();
            if let Some(album_uri) = album_uri {
                if let Ok(Some(full_album_json)) = get_album_metadata(session, &album_uri).await {
                    if let Ok(full_album) = serde_json::from_str::<crate::metadata::track::AlbumJson>(&full_album_json) {
                        track.album = full_album;
                    }
                }
            }
            
            Ok(convert_to_string(&track))
        }
        Err(e) => Err(e),
    }
}

// Retrieves the artist metadata as JSON
async fn get_artist_metadata(
    session: &Session,
    spotify_uri: &SpotifyUri,
) -> Result<Option<String>, librespot_core::error::Error> {
    match librespot_metadata::Artist::get(session, &spotify_uri).await {
        Ok(metadata) => {
            let artist = crate::metadata::track::ArtistJson::from(&metadata);
            Ok(convert_to_string(&artist))
        }
        Err(e) => Err(e),
    }
}

async fn get_playlist_metadata(
    session: &Session,
    spotify_uri: &SpotifyUri,
) -> Result<Option<String>, librespot_core::error::Error> {
    match librespot_metadata::Playlist::get(session, &spotify_uri).await {
        Ok(metadata) => {
            let playlist = crate::metadata::playlist::PlaylistJson::from(&metadata);
            Ok(convert_to_string(&playlist))
        }
        Err(e) => Err(e),
    }
}

// Retrieves the show metadata as JSON
async fn get_show_metadata(
    session: &Session,
    spotify_uri: &SpotifyUri,
) -> Result<Option<String>, librespot_core::error::Error> {
    match librespot_metadata::Show::get(session, &spotify_uri).await {
        Ok(metadata) => {
            let show = crate::metadata::podcast::ShowJson::from(&metadata);
            Ok(convert_to_string(&show))
        }
        Err(e) => Err(e),
    }
}

// Retrieves the episode metadata as JSON
async fn get_episode_metadata(
    session: &Session,
    spotify_uri: &SpotifyUri,
) -> Result<Option<String>, librespot_core::error::Error> {
    match librespot_metadata::Episode::get(session, &spotify_uri).await {
        Ok(metadata) => {
            let episode = crate::metadata::podcast::EpisodeJson::from(&metadata);
            Ok(convert_to_string(&episode))
        }
        Err(e) => Err(e),
    }
}

fn convert_to_string<T: serde::Serialize>(metadata: &T) -> Option<String> {
    match serde_json::to_string(&metadata) {
        Ok(json) => Some(json),
        Err(e) => {
            error!("serde for metadata failed: {e}");
            None
        }
    }
}
