//! Identity attached to every collection result, including queued JNI callbacks.
use librespot_core::{Error, Session};
use serde::Serialize;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
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
        session_id: session.session_id(),
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
}
