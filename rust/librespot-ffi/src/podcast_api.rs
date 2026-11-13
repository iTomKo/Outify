use crate::{
    collection_api::{self, CollectionType},
    collection_session, pathfinder,
    spotify::client::EpisodeDetails,
    types::responses::library::{EpisodeUri, SavedItem, SavedItemsResponse, ShowUri},
};
use librespot_core::{Error, Session, SpotifyId, SpotifyUri};
use librespot_protocol::{
    extended_metadata::{BatchedEntityRequest, EntityRequest, ExtensionQuery},
    extension_kind::ExtensionKind,
    metadata::Episode,
};
use protobuf::{EnumOrUnknown, Message};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};

/// Resolve parent shows in batches; a failed request must not look like an empty library.
pub async fn saved_episodes(session: &Session) -> Result<SavedItemsResponse<EpisodeUri>, Error> {
    let expected = collection_session::identity(session)?;
    let episodes = collection_api::saved_items(session, CollectionType::Episodes).await?;
    let mut shows = HashMap::new();
    for batch in episodes.chunks(500) {
        let request = BatchedEntityRequest {
            entity_request: batch
                .iter()
                .map(|episode| EntityRequest {
                    entity_uri: episode.uri.clone(),
                    query: vec![ExtensionQuery {
                        extension_kind: EnumOrUnknown::new(ExtensionKind::EPISODE_V4),
                        ..Default::default()
                    }],
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        };
        collection_session::ensure_current(&expected)?;
        let response = crate::sync_request::paced(|| async {
            collection_session::ensure_current(&expected)?;
            session
                .spclient()
                .get_extended_metadata(request.clone())
                .await
        })
        .await?;
        collection_session::ensure_current(&expected)?;
        let mut answered = HashSet::new();
        for entity in response
            .extended_metadata
            .into_iter()
            .flat_map(|array| array.extension_data)
        {
            if !batch.iter().any(|episode| episode.uri == entity.entity_uri) {
                continue;
            }
            answered.insert(entity.entity_uri.clone());
            let status = entity.header.status_code;
            if status == 404 || status == 410 {
                continue;
            }
            if status != 0 && !(200..300).contains(&status) {
                return Err(Error::unavailable(format!(
                    "episode metadata failed with status {status}"
                )));
            }
            let raw = entity
                .extension_data
                .as_ref()
                .ok_or_else(|| Error::data_loss("episode metadata is missing"))?;
            let episode = Episode::parse_from_bytes(&raw.value)?;
            if SpotifyId::from_raw(episode.gid())?.to_base62()
                != entity.entity_uri.trim_start_matches("spotify:episode:")
            {
                return Err(Error::data_loss("episode metadata URI mismatch"));
            }
            if !episode.show.gid().is_empty() {
                shows.insert(
                    entity.entity_uri,
                    format!(
                        "spotify:show:{}",
                        SpotifyId::from_raw(episode.show.gid())?.to_base62()
                    ),
                );
            }
        }
        if answered.len() != batch.len() {
            return Err(Error::data_loss("episode metadata batch is incomplete"));
        }
    }
    collection_session::ensure_current(&expected)?;
    Ok(SavedItemsResponse {
        items: episodes
            .into_iter()
            .map(|episode| SavedItem {
                item: EpisodeUri {
                    show: shows.remove(&episode.uri).map(|uri| ShowUri { uri }),
                    uri: episode.uri,
                },
            })
            .collect(),
    })
}

pub async fn episode_details(session: &Session, id: &str) -> Result<EpisodeDetails, Error> {
    let id = SpotifyId::from_base62(id)?.to_base62();
    let expected = collection_session::identity(session)?;
    let uri = format!("spotify:episode:{id}");
    let data = pathfinder::query(
        session,
        &expected,
        "getEpisodeOrChapter",
        json!({
            "uri": uri, "includeEpisodeContentRatingsV2": false,
        }),
    )
    .await?;
    decode_details(&data, &uri)
}

fn decode_details(data: &Value, uri: &str) -> Result<EpisodeDetails, Error> {
    let episode = data
        .get("episodeUnionV2")
        .filter(|episode| episode.get("uri").and_then(Value::as_str) == Some(uri))
        .ok_or_else(|| Error::data_loss("episode response is missing or mismatched"))?;
    let show_uri = episode
        .pointer("/podcastV2/data/uri")
        .and_then(Value::as_str)
        .filter(|uri| matches!(SpotifyUri::from_uri(uri), Ok(SpotifyUri::Show { .. })))
        .ok_or_else(|| Error::data_loss("episode response has no parent show"))?;
    let played = episode.get("playedState");
    let resume_position_ms = played
        .and_then(|played| played.get("playPositionMilliseconds"))
        .and_then(Value::as_i64)
        .unwrap_or(0);
    if resume_position_ms < 0 {
        return Err(Error::data_loss("negative episode resume position"));
    }
    Ok(EpisodeDetails {
        show_uri: show_uri.to_owned(),
        fully_played: played
            .and_then(|played| played.get("state"))
            .and_then(Value::as_str)
            == Some("COMPLETED"),
        resume_position_ms,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn details_preserve_parent_show_and_remote_resume_state() {
        let id = SpotifyId::from_raw(&[1; 16]).unwrap().to_base62();
        let uri = format!("spotify:episode:{id}");
        let show = format!("spotify:show:{id}");
        let data = json!({"episodeUnionV2":{"uri":uri,"podcastV2":{"data":{"uri":show}},"playedState":{"state":"COMPLETED","playPositionMilliseconds":12345}}});
        let details = decode_details(&data, &uri).unwrap();
        assert_eq!(details.show_uri, show);
        assert!(details.fully_played);
        assert_eq!(details.resume_position_ms, 12345);
        assert!(decode_details(&data, "spotify:episode:other").is_err());
        assert!(decode_details(&json!({}), &uri).is_err());
    }
}
