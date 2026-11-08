// Shoutout to Tu2525 - https://github.com/Tu2525/spotifast/blob/5fca13cea72e64ecda9f15eac61b54fcf71a9989/src/jam.rs

use std::str::FromStr;

use futures_util::StreamExt as _;
use librespot_core::{
    Error, Session,
    dealer::protocol::{Message, PayloadValue},
    error::ErrorKind,
};
use librespot_protocol::social_connect_v2::{Session as SocialSession, SessionType};
use protobuf::Message as _;
use reqwest::{
    Method,
    header::{CONTENT_LENGTH, HeaderMap, HeaderValue},
};
use serde::Serialize;
use tokio::sync::mpsc;

const INFO: &str = "/social-connect/v2/sessions/info";
const JOIN: &str = "/social-connect/v2/sessions/join";
const INVITE: &str = "https://open.spotify.com/socialsession/";
const TOPIC: &str = "social-connect/v2/session_update";
const CURRENT: &str = "/social-connect/v2/sessions/current";
const START: &str = "/social-connect/v2/sessions/current_or_new";
const END: &str = "/social-connect/v3/sessions";
const REMOVE_MEMBER: &str = "/social-connect/v3/sessions";

// One person in a Jam
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct JamMember {
    pub id: String,
    pub name: String,
    pub image: Option<String>,
    // Do they own the Jam
    pub host: bool,
    // Is it you
    pub you: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Jam {
    pub members: Vec<JamMember>,
    // Is this account the owner
    pub owner: bool,
    // Invite link, only for owner
    pub link: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum JoinMode {
    ListenAndControl,
    CONTROL,
}

impl FromStr for JoinMode {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "ListenAndControl" => Ok(Self::ListenAndControl),
            "Control" => Ok(Self::CONTROL),
            _ => Err(format!("Invalid JoinMode: {s}")),
        }
    }
}

impl JoinMode {
    fn as_str(&self) -> &'static str {
        match self {
            Self::CONTROL => "CONTROL",
            Self::ListenAndControl => "LISTEN_AND_CONTROL",
        }
    }
}

struct Read {
    jam: Jam,
    id: String,
    background: bool,
}

pub async fn start(session: &Session) -> Result<Jam, Error> {
    let endpoint = format!(
        "{START}?activate=true&local_device_id={}&type=REMOTE",
        session.device_id()
    );

    let body = session
        .spclient()
        .request(&Method::GET, &endpoint, Some(headers(session, false)), None)
        .await
        .map_err(|e| Error::new(e.kind, format!("cannot start a Jam: {e}")))?;
    let read = read_session(&body, &session.username())
        .ok_or_else(|| Error::data_loss("the Jam that started cannot be read"))?;
    Ok(read.jam)
}

pub async fn join(session: &Session, token: &str, mode: JoinMode) -> Result<Jam, Error> {
    if !is_path_id(token) {
        return Err(Error::invalid_argument("invalid Jam invitation token"));
    }
    let endpoint = format!(
        "{JOIN}/{token}?playback_control={}&join_type=deeplinking&local_device_id={}",
        mode.as_str(),
        session.device_id()
    );
    let body = session
        .spclient()
        .request(&Method::POST, &endpoint, Some(headers(session, true)), None)
        .await
        .map_err(|e| Error::new(e.kind, format!("cannot join a Jam: {e}")))?;
    let read = read_session(&body, &session.username())
        .ok_or_else(|| Error::data_loss("the Jam you were invited cannot be read"))?;
    Ok(read.jam)
}

pub async fn current(session: &Session) -> Result<Option<Jam>, Error> {
    Ok(current_read(session).await?.map(|read| read.jam))
}

async fn current_read(session: &Session) -> Result<Option<Read>, Error> {
    let answer = session
        .spclient()
        .request(&Method::GET, CURRENT, Some(headers(session, false)), None)
        .await;

    match answer {
        Ok(body) => {
            let read = read_session(&body, &session.username())
                .ok_or_else(|| Error::data_loss("the Jam answer cannot be read"))?;
            if read.background {
                info!("jam: ignoring inactive/background session; no active Jam");
                Ok(None)
            } else {
                Ok(Some(read))
            }
        }
        Err(error) if error.kind == ErrorKind::NotFound => Ok(None),
        Err(error) => Err(Error::new(
            error.kind,
            format!("cannot read the Jam: {error}"),
        )),
    }
}

pub async fn leave(session: &Session) -> Result<(), Error> {
    let Some(read) = current_read(session).await? else {
        return Ok(());
    };
    let (method, endpoint) = leave_request(&read, session.device_id())?;
    session
        .spclient()
        .request(
            &method,
            &endpoint,
            Some(headers(session, method == Method::POST)),
            None,
        )
        .await
        .map_err(|e| Error::new(e.kind, format!("cannot leave the Jam: {e}")))?;
    Ok(())
}

fn leave_request(read: &Read, device_id: &str) -> Result<(Method, String), Error> {
    if !is_path_id(&read.id) {
        return Err(Error::data_loss("the Jam has an invalid session id"));
    }
    let (method, suffix) = if read.jam.owner {
        (Method::DELETE, "")
    } else {
        (Method::POST, "/leave")
    };
    Ok((
        method,
        format!("{END}/{}{suffix}?local_device_id={device_id}", read.id),
    ))
}

/// Returns information about an invited Jam without joining it.
pub async fn info(session: &Session, token: &str) -> Result<Jam, Error> {
    if !is_path_id(token) {
        return Err(Error::invalid_argument("invalid Jam invitation token"));
    }
    info!("jam: reading invitation information");
    let endpoint = format!("{INFO}/{token}");
    let result = session
        .spclient()
        .request(&Method::GET, &endpoint, Some(headers(session, false)), None)
        .await
        .map_err(|e| Error::new(e.kind, format!("cannot read the Jam invitation: {e}")))
        .and_then(|body| {
            read_session(&body, &session.username())
                .map(|read| read.jam)
                .ok_or_else(|| Error::data_loss("the invited Jam cannot be read"))
        });
    invitation_or_owned_current(token, result, current(session)).await
}

async fn invitation_or_owned_current(
    token: &str,
    result: Result<Jam, Error>,
    current: impl Future<Output = Result<Option<Jam>, Error>>,
) -> Result<Jam, Error> {
    match result {
        Err(error) if error.kind == ErrorKind::NotFound => {
            // A self-invite lookup can return 404. Only reuse current membership
            // when it proves this account owns the exact invitation requested.
            if let Ok(Some(jam)) = current.await
                && jam.owner
                && jam.link.as_deref().and_then(token_in).as_deref() == Some(token)
            {
                info!("jam: invitation preview resolved from the owned current Jam");
                return Ok(jam);
            }
            Err(error)
        }
        result => result,
    }
}

/// Kick member from Jam. Host only
pub async fn kick(session: &Session, member_id: &str) -> Result<(), Error> {
    let endpoint = format!(
        "{REMOVE_MEMBER}/{}/member/{}/kick?local_device_id={}",
        session.session_id(),
        member_id,
        session.device_id()
    );
    session
        .spclient()
        .request(&Method::POST, &endpoint, Some(headers(session, true)), None)
        .await
        .map_err(|e| Error::new(e.kind, format!("failed to kick member {member_id}: {e}")))?;
    Ok(())
}

/// Emits snapshots (`Some`) or a cleared Jam (`None`) for the JNI layer to forward.
/// Tokio wakes the receiver; no frontend-specific event or waker is needed.
/// Subscribes synchronously, before Spirc launches the dealer, to avoid missing
/// updates during startup. The returned future ends when either channel closes.
pub fn watch(
    session: Session,
    events: mpsc::UnboundedSender<Option<Jam>>,
) -> Result<impl Future<Output = ()> + Send, Error> {
    let mut updates = session.dealer().listen_for(TOPIC, Ok)?;
    let own = session.username();
    Ok(async move {
        loop {
            let message = tokio::select! {
                _ = events.closed() => break,
                message = updates.next() => match message {
                    Some(Ok(message)) => message,
                    Some(Err(error)) => {
                        warn!("jam: cannot read update: {error}");
                        continue;
                    }
                    None => break,
                },
            };
            let jam = match update_of(&message, &own) {
                Update::Ended => None,
                Update::Changed(jam) => Some(jam),
                Update::Nothing => continue,
            };
            if events.send(jam).is_err() {
                break;
            }
        }
    })
}

#[derive(Debug, PartialEq, Eq)]
enum Update {
    Ended,
    Changed(Jam),
    Nothing,
}

fn update_of(message: &Message, own: &str) -> Update {
    let PayloadValue::Json(json) = &message.payload else {
        return Update::Nothing;
    };
    let Ok(json) = serde_json::from_str(json) else {
        warn!("jam: unreadable update");
        return Update::Nothing;
    };
    update_in(&json, own)
}

fn update_in(json: &serde_json::Value, own: &str) -> Update {
    let reason = json.get("reason").and_then(serde_json::Value::as_str);
    debug!("jam: update {reason:?}");
    if matches!(
        reason,
        Some("SESSION_DELETED" | "YOU_LEFT" | "YOU_WERE_KICKED")
    ) {
        return Update::Ended;
    }

    let Some(session) = json.get("session") else {
        return Update::Nothing;
    };
    match session_of(json, own) {
        Some(jam) if background_session_of(session, &jam) => {
            info!("jam: ignoring inactive/background update; no active Jam");
            Update::Ended
        }
        Some(jam) => Update::Changed(jam),
        None => Update::Nothing,
    }
}

fn headers(session: &Session, post: bool) -> HeaderMap {
    let mut headers = HeaderMap::new();
    if post {
        headers.insert(CONTENT_LENGTH, HeaderValue::from_static("0"));
    }
    if let Ok(id) = HeaderValue::from_str(&session.connection_id())
        && !id.is_empty()
    {
        headers.insert("spotify-connection-id", id);
    }
    headers
}

// Whether id is made only of what is safe to put in URL path
fn is_path_id(id: &str) -> bool {
    !id.is_empty()
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

/// The token that follows `socialsession/` in a link, or `sessions/join/` in
/// the address Spotify itself gives a session. Letters and digits only.
fn token_in(text: &str) -> Option<String> {
    let rest = ["socialsession/", "sessions/join/"]
        .into_iter()
        .find_map(|marker| text.split(marker).nth(1))?;
    let token = rest.split(|c: char| !c.is_ascii_alphanumeric()).next()?;
    (!token.is_empty()).then(|| token.to_owned())
}

fn invite(url: &str) -> Option<String> {
    match token_in(url) {
        Some(token) => Some(format!("{INVITE}{token}")),
        None => url.starts_with("https://").then(|| url.to_owned()),
    }
}

fn read_session(body: &[u8], own: &str) -> Option<Read> {
    if let Ok(json) = serde_json::from_slice::<serde_json::Value>(body) {
        let session = json.get("session").unwrap_or(&json);
        let jam = session_of(&json, own)?;
        return Some(Read {
            background: background_session_of(session, &jam),
            jam,
            id: key_of(session, ["session_id", "sessionId"])?
                .as_str()?
                .to_owned(),
        });
    }
    let session = SocialSession::parse_from_bytes(body).ok()?;
    if session.session_id.is_empty() {
        return None;
    }
    info!(
        "jam: session metadata (type={:?}, owner={}, discoverable={}, listening={}, controlling={}, members={})",
        session.initial_session_type,
        session.is_session_owner,
        session.is_discoverable,
        session.is_listening,
        session.is_controlling,
        session.session_members.len()
    );
    let link = session
        .is_session_owner
        .then(|| invite(&session.join_session_url))
        .flatten();
    let listed = session.session_members.iter().map(|member| {
        let image = match member.image_url.is_empty() {
            true => &member.large_image_url,
            false => &member.image_url,
        };
        [&member.id, &member.username, &member.display_name, image].map(String::as_str)
    });
    Some(Read {
        background: is_background_session(
            session.is_session_owner,
            session.session_members.len(),
            session.initial_session_type.enum_value_or_default() == SessionType::IN_PERSON,
            session.is_discoverable,
        ),
        jam: Jam {
            members: members_of(
                listed,
                &session.session_owner_id,
                session.is_session_owner,
                own,
            ),
            owner: session.is_session_owner,
            link,
        },
        id: session.session_id,
    })
}

// Spotify may retain a solo, undiscoverable IN_PERSON session for ordinary
// playback/Wi-Fi discovery. Its existence alone does not mean a Jam was started.
fn is_background_session(owner: bool, members: usize, in_person: bool, discoverable: bool) -> bool {
    owner && members <= 1 && in_person && !discoverable
}

fn background_session_of(session: &serde_json::Value, jam: &Jam) -> bool {
    // Current Spotify responses explicitly mark activated Jams.
    if let Some(active) = session.get("active").and_then(serde_json::Value::as_bool) {
        return !active;
    }
    // Older JSON/protobuf responses may omit the activation flag.
    let in_person = key_of(session, ["initial_session_type", "initialSessionType"])
        .is_some_and(|kind| kind.as_str() == Some("IN_PERSON") || kind.as_i64() == Some(3));
    let discoverable = key_of(session, ["is_discoverable", "isDiscoverable"])
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false);
    is_background_session(jam.owner, jam.members.len(), in_person, discoverable)
}

fn members_of<'a>(
    listed: impl Iterator<Item = [&'a str; 4]>,
    owner: &str,
    owned: bool,
    own: &str,
) -> Vec<JamMember> {
    let mut members = listed
        .filter_map(|[id, username, name, image]| {
            let id = [id, username].into_iter().find(|id| !id.is_empty())?;
            Some(JamMember {
                id: id.to_owned(),
                name: [name, username, id]
                    .into_iter()
                    .find(|name| !name.is_empty())?
                    .to_owned(),
                image: image_of(image),
                host: !owner.is_empty() && (id == owner || username == owner),
                you: id == own || username == own,
            })
        })
        .collect::<Vec<_>>();
    if owned
        && !members.iter().any(|mem| mem.host)
        && let Some(you) = members.iter_mut().find(|mem| mem.you)
    {
        you.host = true;
    }
    members
}

fn image_of(image: &str) -> Option<String> {
    match image.strip_prefix("spotify:image:") {
        Some(id) => Some(format!("https://i.scdn.co/image/{id}")),
        None => image.starts_with("https://").then(|| image.to_owned()),
    }
}

/// The value under the first of `keys` a JSON object has, since the case of
/// the keys varies.
fn key_of<'a>(object: &'a serde_json::Value, keys: [&str; 2]) -> Option<&'a serde_json::Value> {
    keys.into_iter().find_map(|key| object.get(key))
}

/// The text under the first of `keys` in a JSON object, or nothing.
fn text_of<'a>(object: &'a serde_json::Value, keys: [&str; 2]) -> &'a str {
    key_of(object, keys)
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default()
}

/// The Jam in a JSON `Session`, or in a `SessionUpdate` holding one,
/// whichever case its keys are in. The link to invite others is there only
/// when `own` owns the session.
fn session_of(json: &serde_json::Value, own: &str) -> Option<Jam> {
    let session = json.get("session").unwrap_or(json);
    // Log state flags only, never invitation tokens, identifiers, or member data.
    let metadata = session
        .as_object()
        .into_iter()
        .flatten()
        .filter(|(key, value)| {
            value.is_boolean()
                || (matches!(key.as_str(), "initial_session_type" | "initialSessionType")
                    && (value.is_string() || value.is_number()))
        })
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect::<serde_json::Map<_, _>>();
    info!(
        "jam: session metadata {}",
        serde_json::Value::Object(metadata)
    );
    let owned = key_of(session, ["is_session_owner", "isSessionOwner"])
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false);
    // a session nobody else is in has no member list, since JSON leaves out
    // an empty one
    let members = key_of(session, ["session_members", "sessionMembers"])
        .and_then(serde_json::Value::as_array)
        .map_or(&[][..], Vec::as_slice);
    let link = key_of(session, ["join_session_url", "joinSessionUrl"])
        .and_then(serde_json::Value::as_str)
        .filter(|_| owned)
        .and_then(invite);
    let listed = members.iter().map(|member| {
        let image = match text_of(member, ["image_url", "imageUrl"]) {
            "" => text_of(member, ["large_image_url", "largeImageUrl"]),
            image => image,
        };
        [
            text_of(member, ["id", "id"]),
            text_of(member, ["username", "username"]),
            text_of(member, ["display_name", "displayName"]),
            image,
        ]
    });
    Some(Jam {
        members: members_of(
            listed,
            text_of(session, ["session_owner_id", "sessionOwnerId"]),
            owned,
            own,
        ),
        owner: owned,
        link,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_protocol::social_connect_v2::SessionMember;

    fn member(id: &str, username: &str, name: &str, image: &str) -> SessionMember {
        SessionMember {
            id: id.into(),
            username: username.into(),
            display_name: name.into(),
            image_url: image.into(),
            ..Default::default()
        }
    }

    #[test]
    fn kotlin_receives_a_nullable_snapshot_with_stable_field_names() {
        let jam = Jam {
            owner: true,
            link: Some("https://open.spotify.com/socialsession/Tok9".into()),
            members: vec![JamMember {
                id: "me".into(),
                name: "Me".into(),
                image: None,
                host: true,
                you: true,
            }],
        };
        assert_eq!(
            serde_json::to_value(Some(jam)).unwrap(),
            serde_json::json!({
                "owner": true,
                "link": "https://open.spotify.com/socialsession/Tok9",
                "members": [{"id": "me", "name": "Me", "image": null, "host": true, "you": true}]
            })
        );
        assert_eq!(serde_json::to_string(&None::<Jam>).unwrap(), "null");
    }

    #[tokio::test]
    async fn requests_use_the_dealer_connection_header_and_empty_post_body() {
        let session = Session::new(Default::default(), None);
        assert!(!headers(&session, false).contains_key("spotify-connection-id"));

        session.set_connection_id("dealer-connection");
        let get = headers(&session, false);
        assert_eq!(get["spotify-connection-id"], "dealer-connection");
        assert!(!get.contains_key(CONTENT_LENGTH));

        let post = headers(&session, true);
        assert_eq!(post["spotify-connection-id"], "dealer-connection");
        assert_eq!(post[CONTENT_LENGTH], "0");
    }

    #[tokio::test]
    async fn watching_stops_when_the_receiver_is_dropped() {
        let session = Session::new(Default::default(), None);
        let (tx, rx) = mpsc::unbounded_channel();
        drop(rx);

        tokio::time::timeout(
            std::time::Duration::from_secs(1),
            watch(session, tx).unwrap(),
        )
        .await
        .expect("watcher should stop without waiting for a dealer update");
    }

    #[test]
    fn dealer_messages_ignore_invalid_payloads_and_forward_jam_updates() {
        let message = |payload| Message {
            headers: Default::default(),
            payload,
            uri: TOPIC.into(),
        };
        for payload in [
            PayloadValue::Empty,
            PayloadValue::Raw(vec![0xff]),
            PayloadValue::Json("not JSON".into()),
        ] {
            assert_eq!(update_of(&message(payload), "me"), Update::Nothing);
        }
        assert_eq!(
            update_of(
                &message(PayloadValue::Json(r#"{"reason":"YOU_LEFT"}"#.into())),
                "me",
            ),
            Update::Ended
        );
        assert_eq!(
            update_of(
                &message(PayloadValue::Json(r#"{"session":{}}"#.into())),
                "me",
            ),
            Update::Changed(Jam::default())
        );
    }

    #[test]
    fn a_join_token_is_found_in_a_link_and_in_spotifys_own_address() {
        assert_eq!(
            token_in("https://open.spotify.com/socialsession/AbC123?si=x").as_deref(),
            Some("AbC123")
        );
        assert_eq!(
            token_in(r#"{"url":"https://spclient/sessions/join/Tok9"}"#).as_deref(),
            Some("Tok9")
        );
        assert_eq!(token_in("https://open.spotify.com/socialsession/"), None);
        assert_eq!(token_in("https://example.com/"), None);
    }

    #[test]
    fn an_invite_link_is_rebuilt_from_the_token() {
        assert_eq!(
            invite("spotify://sessions/join/Tok9").as_deref(),
            Some("https://open.spotify.com/socialsession/Tok9")
        );
        assert_eq!(
            invite("https://spotify.link/xyz").as_deref(),
            Some("https://spotify.link/xyz")
        );
        assert_eq!(invite("spotify:nothing"), None);
    }

    #[test]
    fn a_json_session_names_the_host_you_and_the_link() {
        let body = br#"{
            "session_id": "abc_-1",
            "join_session_url": "https://open.spotify.com/socialsession/Tok9",
            "session_owner_id": "me",
            "is_session_owner": true,
            "session_members": [
                {"id": "me", "username": "me", "display_name": "Me",
                 "image_url": "spotify:image:img1"},
                {"id": "guest", "username": "guest", "display_name": "Guest",
                 "large_image_url": "https://i.scdn.co/image/img2"}
            ]
        }"#;
        let read = read_session(body, "me").unwrap();
        assert_eq!(read.id, "abc_-1");
        assert!(read.jam.owner);
        assert_eq!(
            read.jam.link.as_deref(),
            Some("https://open.spotify.com/socialsession/Tok9")
        );
        assert_eq!(read.jam.members.len(), 2);
        let [me, guest] = &read.jam.members[..] else {
            panic!()
        };
        assert!(me.host && me.you);
        assert_eq!(me.image.as_deref(), Some("https://i.scdn.co/image/img1"));
        assert!(!guest.host && !guest.you);
        assert_eq!(guest.image.as_deref(), Some("https://i.scdn.co/image/img2"));
    }

    #[test]
    fn a_json_session_in_camel_case_without_members_still_reads() {
        let body = br#"{"session":{"sessionId":"s1","isSessionOwner":false,
            "joinSessionUrl":"https://open.spotify.com/socialsession/Tok9"}}"#;
        let read = read_session(body, "me").unwrap();
        assert_eq!(read.id, "s1");
        assert!(read.jam.members.is_empty());
        assert_eq!(read.jam.link, None, "only the owner gets the link");
    }

    #[test]
    fn a_protobuf_session_reads_like_a_json_one() {
        let session = SocialSession {
            session_id: "pb1".into(),
            join_session_url: "https://open.spotify.com/sessions/join/Tok9".into(),
            session_owner_id: "host".into(),
            is_session_owner: false,
            session_members: vec![
                member("host", "host", "Host", ""),
                member("", "me", "", "spotify:image:img1"),
            ],
            ..Default::default()
        };
        let body = session.write_to_bytes().unwrap();
        let read = read_session(&body, "me").unwrap();
        assert_eq!(read.id, "pb1");
        assert!(!read.jam.owner);
        assert_eq!(read.jam.link, None);
        let [host, me] = &read.jam.members[..] else {
            panic!()
        };
        assert!(host.host && !host.you);
        assert!(!me.host && me.you);
        assert_eq!(me.name, "me", "falls back to the username");
        assert_eq!(me.id, "me", "and the id to the username");
    }

    #[test]
    fn solo_in_person_playback_is_not_an_active_jam() {
        // Shape observed after playing a track, killing the app, and reopening.
        let session = serde_json::json!({
            "session_id": "background",
            "initialSessionType": "IN_PERSON",
            "is_session_owner": true,
            "is_controlling": true,
            "is_listening": true,
            "wifi_broadcast": true,
            "requires_jam_lens": true,
            "session_members": [{"id": "me", "username": "me"}]
        });
        let read = read_session(&serde_json::to_vec(&session).unwrap(), "me").unwrap();
        assert!(read.background);
        assert_eq!(
            update_in(
                &serde_json::json!({"session": session, "reason": "NEW_SESSION"}),
                "me"
            ),
            Update::Ended
        );
    }

    #[test]
    fn explicit_remote_jams_stay_active_even_with_only_the_host() {
        for kind in [
            serde_json::json!("REMOTE"),
            serde_json::json!("REMOTE_V2"),
            serde_json::json!(4),
            serde_json::json!(5),
        ] {
            let session = serde_json::json!({
                "session_id": "explicit",
                "initial_session_type": kind,
                "is_session_owner": true,
                "session_members": [{"id": "me"}]
            });
            let read = read_session(&serde_json::to_vec(&session).unwrap(), "me").unwrap();
            assert!(!read.background);
            assert!(matches!(
                update_in(&serde_json::json!({"session": session}), "me"),
                Update::Changed(_)
            ));
        }
    }

    #[test]
    fn activation_flag_takes_precedence_over_session_type_and_member_count() {
        for (active, kind, owner) in [(true, "IN_PERSON", true), (false, "REMOTE", false)] {
            let session = serde_json::json!({
                "session_id": "activation",
                "active": active,
                "initialSessionType": kind,
                "is_session_owner": owner,
                "session_members": [{"id": "me"}]
            });
            let read = read_session(&serde_json::to_vec(&session).unwrap(), "me").unwrap();
            assert_eq!(read.background, !active);
            let update = update_in(&serde_json::json!({"session": session}), "me");
            assert_eq!(matches!(update, Update::Changed(_)), active);
        }
    }

    #[test]
    fn in_person_guests_groups_and_discoverable_jams_stay_active() {
        for (owner, members, discoverable) in [(false, 1, false), (true, 2, false), (true, 1, true)]
        {
            for kind in [serde_json::json!("IN_PERSON"), serde_json::json!(3)] {
                let session = serde_json::json!({
                    "sessionId": "in-person",
                    "initialSessionType": kind,
                    "isSessionOwner": owner,
                    "isDiscoverable": discoverable,
                    "sessionMembers": (0..members).map(|i| serde_json::json!({"id": i.to_string()})).collect::<Vec<_>>()
                });
                let read = read_session(&serde_json::to_vec(&session).unwrap(), "me").unwrap();
                assert!(!read.background);
            }
        }
    }

    #[test]
    fn protobuf_background_sessions_are_classified_like_json() {
        let mut session = SocialSession {
            session_id: "solo".into(),
            is_session_owner: true,
            initial_session_type: SessionType::IN_PERSON.into(),
            session_members: vec![member("me", "me", "Me", "")],
            ..Default::default()
        };
        assert!(
            read_session(&session.write_to_bytes().unwrap(), "me")
                .unwrap()
                .background
        );
        session.initial_session_type = SessionType::REMOTE.into();
        assert!(
            !read_session(&session.write_to_bytes().unwrap(), "me")
                .unwrap()
                .background
        );
    }

    #[test]
    fn an_empty_or_garbled_answer_is_no_session() {
        assert!(read_session(b"", "me").is_none());
        assert!(read_session(&[0xff, 0xff, 0xff], "me").is_none());
    }

    #[test]
    fn the_owner_is_the_host_when_no_id_names_one() {
        let listed = [["me", "me", "Me", ""], ["guest", "guest", "Guest", ""]];
        let members = members_of(listed.into_iter(), "", true, "me");
        assert!(members[0].host && members[0].you);
        assert!(!members[1].host);
        let members = members_of(listed.into_iter(), "", false, "me");
        assert!(!members.iter().any(|member| member.host));
    }

    #[test]
    fn a_member_with_no_id_or_username_is_left_out() {
        let listed = [["", "", "Ghost", ""]];
        assert!(members_of(listed.into_iter(), "", false, "me").is_empty());
    }

    #[test]
    fn an_update_that_ends_the_jam_clears_it() {
        for reason in ["SESSION_DELETED", "YOU_LEFT", "YOU_WERE_KICKED"] {
            let json = serde_json::json!({"reason": reason});
            assert_eq!(update_in(&json, "me"), Update::Ended);
        }
    }

    #[test]
    fn an_update_with_a_session_replaces_the_jam_and_one_without_changes_nothing() {
        let json = serde_json::json!({
            "reason": "USER_JOINED",
            "session": {"session_id": "s", "session_members": [
                {"id": "me", "username": "me", "display_name": "Me"}]}
        });
        let Update::Changed(jam) = update_in(&json, "me") else {
            panic!("expected a change");
        };
        assert_eq!(jam.members.len(), 1);
        assert!(jam.members[0].you);
        assert_eq!(
            update_in(&serde_json::json!({"reason": "USER_JOINED"}), "me"),
            Update::Nothing
        );
    }

    #[test]
    fn only_plain_ids_go_into_a_path() {
        assert!(is_path_id("abc_-123"));
        assert!(!is_path_id(""));
        assert!(!is_path_id("a/b"));
        assert!(!is_path_id("a?b"));
    }
}
