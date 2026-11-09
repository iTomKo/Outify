use super::track::TrackJson;
use librespot_core::{Error, Session, SpotifyUri};
use librespot_protocol::{
    entity_extension_data::EntityExtensionData,
    extended_metadata::{
        BatchedEntityRequest, BatchedExtensionResponse, EntityRequest, ExtensionQuery,
    },
    extension_kind::ExtensionKind,
    metadata::Track as TrackMessage,
};
use protobuf::{EnumOrUnknown, Message};
use std::collections::{HashMap, HashSet};

pub const BATCH_SIZE: usize = 500;

pub async fn tracks(session: &Session, uris: &[String]) -> Result<Vec<TrackJson>, Error> {
    collect_tracks(uris, |request| async move {
        crate::sync_request::paced(|| session.spclient().get_extended_metadata(request.clone()))
            .await
    })
    .await
}

async fn collect_tracks<F, R>(uris: &[String], mut fetch: F) -> Result<Vec<TrackJson>, Error>
where
    F: FnMut(BatchedEntityRequest) -> R,
    R: Future<Output = Result<BatchedExtensionResponse, Error>>,
{
    let mut seen = HashSet::new();
    let uris: Vec<_> = uris
        .iter()
        .filter(|uri| seen.insert((*uri).clone()))
        .collect();
    if uris
        .iter()
        .any(|uri| !matches!(SpotifyUri::from_uri(uri), Ok(SpotifyUri::Track { .. })))
    {
        return Err(Error::invalid_argument(
            "batch metadata requires Spotify track URIs",
        ));
    }
    let mut tracks = HashMap::new();
    for batch in uris.chunks(BATCH_SIZE) {
        debug!("metadata: fetching a batch of {} tracks", batch.len());
        let request = BatchedEntityRequest {
            entity_request: batch
                .iter()
                .map(|uri| EntityRequest {
                    entity_uri: (*uri).clone(),
                    query: vec![ExtensionQuery {
                        extension_kind: EnumOrUnknown::new(ExtensionKind::TRACK_V4),
                        ..Default::default()
                    }],
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        };
        let response = fetch(request).await?;
        let mut answered = HashSet::new();
        for entity in response
            .extended_metadata
            .into_iter()
            .flat_map(|array| array.extension_data)
        {
            let uri = entity.entity_uri.clone();
            if !batch.iter().any(|requested| requested.as_str() == uri) {
                continue;
            }
            answered.insert(uri.clone());
            if let Some(track) = decode_track(entity)? {
                tracks.insert(uri, track);
            }
        }
        if answered.len() != batch.len() {
            return Err(Error::data_loss(
                "track metadata batch response is incomplete",
            ));
        }
    }
    Ok(uris
        .into_iter()
        .filter_map(|uri| tracks.remove(uri))
        .collect())
}

fn decode_track(entity: EntityExtensionData) -> Result<Option<TrackJson>, Error> {
    let status = entity.header.status_code;
    if status == 404 || status == 410 {
        return Ok(None);
    }
    if status != 0 && !(200..300).contains(&status) {
        return Err(if status == 429 {
            Error::resource_exhausted("track metadata batch was rate limited")
        } else {
            Error::unavailable(format!("track metadata entity failed with status {status}"))
        });
    }
    let data = entity
        .extension_data
        .as_ref()
        .ok_or_else(|| Error::data_loss("track metadata entity has no data"))?;
    let message = TrackMessage::parse_from_bytes(&data.value)?;
    let track = librespot_metadata::Track::try_from(&message)?;
    if track.id.to_uri() != entity.entity_uri {
        return Err(Error::data_loss(
            "track metadata URI does not match its entity",
        ));
    }
    Ok(Some(TrackJson::from(&track)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_protocol::{
        entity_extension_data::EntityExtensionDataHeader,
        extended_metadata::EntityExtensionDataArray,
        metadata::{Album, Artist, Date},
    };

    fn uri(index: u16) -> String {
        let mut gid = [0; 16];
        gid[14..].copy_from_slice(&index.to_be_bytes());
        format!(
            "spotify:track:{}",
            librespot_core::SpotifyId::from_raw(&gid)
                .unwrap()
                .to_base62()
        )
    }

    fn entity(uri: &str, status: i32) -> EntityExtensionData {
        EntityExtensionData {
            entity_uri: uri.into(),
            header: Some(EntityExtensionDataHeader {
                status_code: status,
                ..Default::default()
            })
            .into(),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn metadata_is_really_batched_in_chunks_of_500_and_deduplicated() {
        let mut uris: Vec<_> = (0..1001).map(uri).collect();
        uris.push(uris[0].clone());
        let mut sizes = Vec::new();
        let result = collect_tracks(&uris, |request| {
            sizes.push(request.entity_request.len());
            let entities = request
                .entity_request
                .into_iter()
                .map(|request| {
                    assert_eq!(
                        request.query[0].extension_kind.enum_value().unwrap(),
                        ExtensionKind::TRACK_V4
                    );
                    entity(&request.entity_uri, 404)
                })
                .collect();
            async move {
                Ok(BatchedExtensionResponse {
                    extended_metadata: vec![EntityExtensionDataArray {
                        extension_data: entities,
                        ..Default::default()
                    }],
                    ..Default::default()
                })
            }
        })
        .await
        .unwrap();
        assert!(result.is_empty());
        assert_eq!(sizes, vec![500, 500, 1]);
    }

    #[test]
    fn batched_tracks_keep_outifys_track_album_artist_and_file_shape() {
        let artist = Artist {
            gid: Some(vec![2; 16]),
            name: Some("Artist".into()),
            ..Default::default()
        };
        let album = Album {
            gid: Some(vec![3; 16]),
            name: Some("Album".into()),
            artist: vec![artist.clone()],
            date: Some(Date {
                year: Some(2020),
                month: Some(1),
                day: Some(2),
                ..Default::default()
            })
            .into(),
            ..Default::default()
        };
        let message = TrackMessage {
            gid: Some(vec![1; 16]),
            name: Some("Track".into()),
            artist: vec![artist],
            album: Some(album).into(),
            duration: Some(180000),
            ..Default::default()
        };
        let uri = format!(
            "spotify:track:{}",
            librespot_core::SpotifyId::from_raw(&[1; 16])
                .unwrap()
                .to_base62()
        );
        let mut response = entity(&uri, 200);
        response.extension_data = Some(protobuf::well_known_types::any::Any {
            value: message.write_to_bytes().unwrap(),
            ..Default::default()
        })
        .into();
        let json = serde_json::to_value(decode_track(response).unwrap().unwrap()).unwrap();
        assert_eq!(json["uri"], uri);
        assert_eq!(json["name"], "Track");
        assert_eq!(json["album"]["name"], "Album");
        assert_eq!(json["artists"][0]["name"], "Artist");
        assert!(json["files"].is_array());
    }

    #[tokio::test]
    async fn incomplete_responses_and_invalid_requests_are_not_treated_as_success() {
        let result = collect_tracks(&[uri(1)], |_| async {
            Ok(BatchedExtensionResponse::default())
        })
        .await;
        assert_eq!(
            result.err().unwrap().kind,
            librespot_core::error::ErrorKind::DataLoss
        );
        assert!(
            collect_tracks(&["not-a-track".into()], |_| async {
                panic!("must not send")
            })
            .await
            .is_err()
        );
    }

    #[test]
    fn entity_failures_propagate_but_deleted_tracks_can_be_skipped() {
        assert!(decode_track(entity(&uri(1), 404)).unwrap().is_none());
        assert!(decode_track(entity(&uri(1), 200)).is_err());
        assert_eq!(
            decode_track(entity(&uri(1), 429)).err().unwrap().kind,
            librespot_core::error::ErrorKind::ResourceExhausted
        );
        assert_eq!(
            decode_track(entity(&uri(1), 500)).err().unwrap().kind,
            librespot_core::error::ErrorKind::Unavailable
        );
    }
}
