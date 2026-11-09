//! Follow collection track deltas without rereading the entire library per like.
use futures_util::StreamExt;
use librespot_core::{Error, Session, SpotifyId, dealer::protocol::PayloadValue};
use protobuf::{CodedInputStream, rt::WireType};
use serde::Serialize;
use std::sync::Mutex;
use tokio::task::JoinHandle;

const TOPIC: &str = "hm://collection/collection/";
static LISTENER: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

#[derive(Debug, PartialEq, Eq, Serialize)]
struct TrackChange {
    uri: String,
    removed: bool,
}

pub async fn start(session: &Session) {
    stop();
    // Register synchronously before Spirc launches the dealer.
    let mut updates = match session.dealer().listen_for(TOPIC, Ok) {
        Ok(updates) => updates,
        Err(error) => {
            warn!("cannot subscribe to collection deltas: {error}");
            return;
        }
    };
    let mut shutdown = session.subscribe_shutdown();
    let session = session.clone();
    let listener = tokio::spawn(async move {
        loop {
            tokio::select! {
                biased;
                _ = shutdown.changed() => break,
                update = updates.next() => {
                    let Some(update) = update else {
                        if !session.is_invalid() { invalidate(&session); }
                        break;
                    };
                    if session.is_invalid() { break; }
                    match update {
                        Ok(message) => match message.payload {
                            PayloadValue::Raw(bytes) => match track_changes(&bytes) {
                                Ok(changes) if !changes.is_empty() => dispatch_changes(&session, changes),
                                Ok(_) => {},
                                Err(error) => {
                                    warn!("invalid collection delta; requesting reconciliation: {error}");
                                    invalidate(&session);
                                }
                            },
                            // Spotify repeats the same push as JSON. Applying both
                            // copies can reorder a like against a later removal.
                            PayloadValue::Json(_) => {},
                            _ => invalidate(&session),
                        },
                        Err(error) => {
                            warn!("collection delta failed: {error}");
                            invalidate(&session);
                        }
                    }
                }
            }
        }
    });
    *LISTENER.lock().unwrap() = Some(listener);
}

pub fn stop() {
    if let Some(listener) = LISTENER.lock().unwrap().take() {
        listener.abort();
    }
}

fn invalidate(session: &Session) {
    let Ok(identity) = crate::collection_session::identity(session) else {
        return;
    };
    let Ok(json) = serde_json::to_string(&identity) else {
        return;
    };
    if let Some(callback) = crate::jni_impl::session::session_cb() {
        crate::jni_utils::jni_bridge::dispatch(
            callback,
            crate::jni_impl::session::METHOD_COLLECTION_CHANGED,
            vec![crate::jni_utils::jni_bridge::BridgeArg::Str(json)],
        );
    }
}

fn dispatch_changes(session: &Session, changes: Vec<TrackChange>) {
    let Ok(identity) = crate::collection_session::identity(session) else {
        return;
    };
    let Ok(json) =
        serde_json::to_string(&serde_json::json!({"session": identity, "changes": changes}))
    else {
        invalidate(session);
        return;
    };
    if let Some(callback) = crate::jni_impl::session::session_cb() {
        crate::jni_utils::jni_bridge::dispatch(
            callback,
            crate::jni_impl::session::METHOD_COLLECTION_DELTA,
            vec![crate::jni_utils::jni_bridge::BridgeArg::Str(json)],
        );
    }
}

fn skip(input: &mut CodedInputStream<'_>, tag: u32) -> Result<(), Error> {
    let wire = WireType::new(tag & 7)
        .ok_or_else(|| Error::data_loss("invalid collection delta wire type"))?;
    input.skip_field(wire)?;
    Ok(())
}

fn track_changes(bytes: &[u8]) -> Result<Vec<TrackChange>, Error> {
    let mut input = CodedInputStream::from_bytes(bytes);
    let mut changes = Vec::new();
    while let Some(tag) = input.read_raw_tag_or_eof()? {
        if tag == 10 {
            if let Some(change) = track_change(&input.read_bytes()?)? {
                changes.push(change);
            }
        } else {
            skip(&mut input, tag)?;
        }
    }
    Ok(changes)
}

fn track_change(bytes: &[u8]) -> Result<Option<TrackChange>, Error> {
    let mut input = CodedInputStream::from_bytes(bytes);
    let mut kind = 0;
    let mut gid = Vec::new();
    let mut removed = false;
    while let Some(tag) = input.read_raw_tag_or_eof()? {
        match tag {
            8 => kind = input.read_uint64()?,
            18 => gid = input.read_bytes()?,
            48 => removed = input.read_bool()?,
            _ => skip(&mut input, tag)?,
        }
    }
    if kind != 0 {
        return Ok(None);
    }
    let id = SpotifyId::from_raw(&gid)?;
    Ok(Some(TrackChange {
        uri: format!("spotify:track:{}", id.to_base62()),
        removed,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use protobuf::CodedOutputStream;

    fn item(kind: u64, gid: &[u8], removed: bool) -> Vec<u8> {
        let mut bytes = Vec::new();
        {
            let mut out = CodedOutputStream::vec(&mut bytes);
            out.write_uint64(1, kind).unwrap();
            out.write_bytes(2, gid).unwrap();
            out.write_bool(6, removed).unwrap();
            out.flush().unwrap();
        }
        bytes
    }

    #[test]
    fn raw_updates_preserve_add_remove_order_and_ignore_non_track_items() {
        let gid = [1; 16];
        let uri = format!(
            "spotify:track:{}",
            SpotifyId::from_raw(&gid).unwrap().to_base62()
        );
        let mut bytes = Vec::new();
        {
            let mut out = CodedOutputStream::vec(&mut bytes);
            out.write_bytes(1, &item(0, &gid, false)).unwrap();
            out.write_bytes(1, &item(1, &gid, false)).unwrap();
            out.write_bytes(1, &item(0, &gid, true)).unwrap();
            out.flush().unwrap();
        }
        assert_eq!(
            track_changes(&bytes).unwrap(),
            vec![
                TrackChange {
                    uri: uri.clone(),
                    removed: false
                },
                TrackChange { uri, removed: true },
            ]
        );
    }

    #[test]
    fn invalid_deltas_require_reconciliation_instead_of_guessing_an_id() {
        assert!(track_change(&item(0, &[1, 2], false)).is_err());
        assert!(track_changes(&[10, 100, 0]).is_err());
    }
}
