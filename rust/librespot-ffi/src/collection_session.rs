//! Identity attached to every collection result, including queued JNI callbacks.
use librespot_core::{Error, Session};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CollectionSession {
    pub account_id: String,
    pub session_id: String,
}

pub fn identity(session: &Session) -> Result<CollectionSession, Error> {
    let account_id = session.username();
    if session.is_invalid() || account_id.is_empty() {
        return Err(Error::unavailable(
            "collection session is not authenticated",
        ));
    }
    Ok(CollectionSession {
        account_id,
        // Spotify changes session_id for playback/Jam membership without replacing login.
        // A stale-account lease must follow the immutable authenticated Session instance.
        session_id: session.instance_id().to_owned(),
    })
}

pub fn ensure_current(expected: &CollectionSession) -> Result<(), Error> {
    let current = crate::session::with_session(identity)??;
    if &current != expected {
        return Err(Error::unavailable("collection session was replaced"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_wire_names_match_the_kotlin_snapshot_and_delta_envelopes() {
        let identity = CollectionSession {
            account_id: "listener".into(),
            session_id: "native-session".into(),
        };
        assert_eq!(
            serde_json::to_value(&identity).unwrap(),
            serde_json::json!({
                "accountId": "listener", "sessionId": "native-session"
            })
        );
        assert_ne!(
            identity,
            CollectionSession {
                account_id: "other".into(),
                session_id: "native-session".into()
            }
        );
        assert_ne!(
            identity,
            CollectionSession {
                account_id: "listener".into(),
                session_id: "replacement".into()
            }
        );
    }

    #[tokio::test]
    async fn an_unauthenticated_session_never_supplies_a_collection_identity() {
        let session = Session::new(Default::default(), None);
        assert_eq!(
            identity(&session).unwrap_err().kind,
            librespot_core::error::ErrorKind::Unavailable
        );
    }

    #[tokio::test]
    async fn connect_session_rotation_does_not_change_authenticated_identity() {
        let session = Session::new(Default::default(), None);
        session.set_username("listener");
        let before = identity(&session).unwrap();
        let clone = session.clone();
        for playback_id in ["remote-playback", "jam-session", "local-playback"] {
            clone.set_session_id(playback_id);
            assert_eq!(session.session_id(), playback_id);
            assert_eq!(identity(&session).unwrap(), before);
            assert_eq!(identity(&clone).unwrap(), before);
        }
    }

    #[tokio::test]
    async fn distinct_authenticated_sessions_do_not_share_identity_even_in_the_same_playback_session() {
        let first = Session::new(Default::default(), None);
        let replacement = Session::with_handle(Default::default(), None, tokio::runtime::Handle::current());
        for session in [&first, &replacement] {
            session.set_username("listener");
            session.set_session_id("shared-playback-session");
        }
        assert_ne!(identity(&first).unwrap(), identity(&replacement).unwrap());
    }
}
