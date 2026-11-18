use super::SpotifyClient;
use crate::{
    collection_api::{self, CollectionType},
    pathfinder, playlist_api, podcast_api,
    spotify::error::SpotifyApiError,
    types::responses::library::{EpisodeUri, SavedItem, SavedItemsResponse, Uri},
};
use reqwest::StatusCode;
use serde::Serialize;
use std::str::FromStr;

impl SpotifyClient {
    pub async fn save_items(&self, uris: Vec<String>) -> Result<StatusCode, SpotifyApiError> {
        self.update_items(uris, true).await
    }

    pub async fn delete_items(&self, uris: Vec<String>) -> Result<StatusCode, SpotifyApiError> {
        self.update_items(uris, false).await
    }

    /// All library changes target playback auth, never a separately signed-in OAuth user.
    async fn update_items(
        &self,
        uris: Vec<String>,
        saved: bool,
    ) -> Result<StatusCode, SpotifyApiError> {
        let items = partition_items(uris)?;
        if items.collection.is_empty() && items.playlists.is_empty() && items.users.is_empty() {
            return Ok(StatusCode::OK);
        }
        let session = crate::session::with_session(Clone::clone)?;
        if !items.collection.is_empty() {
            collection_api::set_saved(&session, &items.collection, saved).await?;
        }
        if !items.playlists.is_empty() {
            playlist_api::set_saved(&session, &items.playlists, saved).await?;
        }
        if !items.users.is_empty() {
            pathfinder::follow_users(&session, &items.users, saved).await?;
        }
        Ok(StatusCode::OK)
    }

    pub async fn get_saved(
        &self,
        item: SavedItemType,
    ) -> Result<SavedItemsResponse<Uri>, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        let items = collection_api::saved_items(&session, item.collection_type()).await?;
        Ok(SavedItemsResponse {
            items: items
                .into_iter()
                .map(|item| SavedItem {
                    item: Uri { uri: item.uri },
                })
                .collect(),
        })
    }

    pub async fn get_saved_episode_items(
        &self,
    ) -> Result<SavedItemsResponse<EpisodeUri>, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        Ok(podcast_api::saved_episodes(&session).await?)
    }

    pub async fn get_episode_details(
        &self,
        episode_id: &str,
    ) -> Result<EpisodeDetails, SpotifyApiError> {
        let session = crate::session::with_session(Clone::clone)?;
        Ok(podcast_api::episode_details(&session, episode_id).await?)
    }
}

#[derive(Default)]
struct LibraryItems {
    collection: Vec<String>,
    playlists: Vec<String>,
    users: Vec<String>,
}

/// Validate the entire batch before performing any mutation.
fn partition_items(uris: Vec<String>) -> Result<LibraryItems, SpotifyApiError> {
    let mut items = LibraryItems::default();
    for uri in uris {
        if CollectionType::from_uri(&uri)?.is_some() {
            items.collection.push(uri);
        } else if uri.starts_with("spotify:user:") && uri.split(':').count() == 3 {
            pathfinder::username(&uri)?;
            items.users.push(uri);
        } else {
            items.playlists.push(playlist_api::playlist_uri(&uri)?);
        }
    }
    Ok(items)
}

#[derive(Serialize)]
pub struct EpisodeDetails {
    pub show_uri: String,
    pub fully_played: bool,
    pub resume_position_ms: i64,
}

pub enum SavedItemType {
    Tracks,
    Albums,
    Artists,
    Episodes,
    Shows,
}

impl SavedItemType {
    fn collection_type(&self) -> CollectionType {
        match self {
            Self::Tracks => CollectionType::Tracks,
            Self::Albums => CollectionType::Albums,
            Self::Artists => CollectionType::Artists,
            Self::Episodes => CollectionType::Episodes,
            Self::Shows => CollectionType::Shows,
        }
    }
}

impl FromStr for SavedItemType {
    type Err = SpotifyApiError;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "tracks" => Ok(Self::Tracks),
            "albums" => Ok(Self::Albums),
            "artists" => Ok(Self::Artists),
            "episodes" => Ok(Self::Episodes),
            "shows" => Ok(Self::Shows),
            _ => Err(SpotifyApiError::Generic(format!(
                "Invalid SavedItemType: {value}"
            ))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn routes_every_supported_type_and_rejects_unknown_items_before_writing() {
        let id = librespot_core::SpotifyId::from_raw(&[1; 16])
            .unwrap()
            .to_base62();
        let uris: Vec<_> = ["track", "album", "artist", "episode", "show", "playlist"]
            .iter()
            .map(|kind| format!("spotify:{kind}:{id}"))
            .chain(["spotify:user:listener".into()])
            .collect();
        let items = partition_items(uris).unwrap();
        assert_eq!(items.collection.len(), 5);
        assert_eq!(items.playlists.len(), 1);
        assert_eq!(items.users, ["spotify:user:listener"]);
        assert!(partition_items(vec!["spotify:track:bad".into()]).is_err());
        assert!(partition_items(vec!["spotify:unknown:item".into()]).is_err());
    }
    #[tokio::test]
    async fn no_native_operation_falls_back_to_web_oauth_without_a_playback_session() {
        let client = SpotifyClient::new();
        let id = librespot_core::SpotifyId::from_raw(&[1; 16])
            .unwrap()
            .to_base62();
        for kind in ["playlist", "episode", "show", "user"] {
            for saved in [true, false] {
                assert!(matches!(
                    client
                        .update_items(vec![format!("spotify:{kind}:{id}")], saved)
                        .await,
                    Err(SpotifyApiError::Librespot(_))
                ));
            }
        }
        assert!(matches!(
            client.get_saved_episode_items().await,
            Err(SpotifyApiError::Librespot(_))
        ));
        assert!(matches!(
            client.get_episode_details(&id).await,
            Err(SpotifyApiError::Librespot(_))
        ));
    }
}
