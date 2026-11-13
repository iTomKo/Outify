//! Playlist-v2 mutations
use crate::{
    collection_session::{self, CollectionSession},
    native_request,
};
use http::Method;
use librespot_core::{Error, Session, SpotifyId, SpotifyUri};
use librespot_protocol::playlist4_external::{
    Add, ChangeInfo, CreateListReply, Delta, Item, ItemAttributes, ItemAttributesPartialState,
    ListAttributeKind, ListAttributes, ListAttributesPartialState, ListChanges, Op, OpList, Rem,
    SelectedListContent, SourceInfo, UpdateItemAttributes, UpdateListAttributes, op::Kind,
    source_info::Client,
};
use protobuf::{Message, MessageField};
use std::collections::HashSet;

const PAGE: usize = 1000;

pub fn playlist_id(id: &str) -> Result<String, Error> {
    Ok(SpotifyId::from_base62(id)?.to_base62())
}

pub fn playlist_uri(uri: &str) -> Result<String, Error> {
    let parts: Vec<_> = uri.split(':').collect();
    if !matches!(
        parts.as_slice(),
        ["spotify", "playlist", _] | ["spotify", "user", _, "playlist", _]
    ) {
        return Err(Error::invalid_argument("invalid playlist URI"));
    }
    match SpotifyUri::from_uri(uri)? {
        SpotifyUri::Playlist { id, .. } => Ok(format!("spotify:playlist:{}", id.to_base62())),
        _ => Err(Error::invalid_argument("expected a playlist URI")),
    }
}

fn rootlist_path(expected: &CollectionSession) -> String {
    format!(
        "/playlist/v2/user/{}/rootlist",
        native_request::component(&expected.account_id)
    )
}

pub async fn rootlist(session: &Session) -> Result<SelectedListContent, Error> {
    let expected = collection_session::identity(session)?;
    snapshot(session, &expected, &rootlist_path(&expected)).await
}

async fn snapshot(
    session: &Session,
    expected: &CollectionSession,
    path: &str,
) -> Result<SelectedListContent, Error> {
    read_pages(|from| async move {
        let endpoint = format!("{path}?from={from}&length={PAGE}&decorate=revision,attributes,length,owner,capabilities");
        let raw = native_request::request(session, expected, Method::GET, &endpoint, "application/x-protobuf", &[]).await?;
        Ok(SelectedListContent::parse_from_bytes(&raw)?)
    }).await
}

async fn read_pages<F, R>(mut fetch: F) -> Result<SelectedListContent, Error>
where
    F: FnMut(usize) -> R,
    R: Future<Output = Result<SelectedListContent, Error>>,
{
    let mut result = fetch(0).await?;
    let length = usize::try_from(
        result
            .length
            .ok_or_else(|| Error::data_loss("playlist length is missing"))?,
    )?;
    if result.revision().is_empty() || length > 1_000_000 {
        return Err(Error::data_loss("invalid playlist snapshot"));
    }
    let mut items = Vec::new();
    let mut page = result.clone();
    loop {
        if page.revision != result.revision || page.length() != result.length() {
            return Err(Error::unavailable(
                "playlist changed during pagination; refresh and retry",
            ));
        }
        let contents = page.contents.take().unwrap_or_default();
        if usize::try_from(contents.pos())? != items.len()
            || (contents.items.is_empty() && items.len() < length)
        {
            return Err(Error::data_loss("incomplete playlist page"));
        }
        items.extend(contents.items);
        if items.len() == length {
            break;
        }
        if items.len() > length {
            return Err(Error::data_loss("playlist page exceeds its length"));
        }
        page = fetch(items.len()).await?;
    }
    let contents = result.contents.mut_or_insert_default();
    contents.items = items;
    contents.set_pos(0);
    contents.set_truncated(false);
    Ok(result)
}

async fn post<M: Message>(
    session: &Session,
    expected: &CollectionSession,
    path: &str,
    message: &M,
) -> Result<Vec<u8>, Error> {
    native_request::request(
        session,
        expected,
        Method::POST,
        path,
        "application/x-protobuf",
        &message.write_to_bytes()?,
    )
    .await
}

fn changes(revision: &[u8], ops: Vec<Op>) -> ListChanges {
    let mut source = SourceInfo::new();
    source.set_client(Client::CLIENT);
    let mut info = ChangeInfo::new();
    info.source = MessageField::some(source);
    let mut delta = Delta::new();
    delta.ops = ops;
    delta.info = MessageField::some(info);
    let mut changes = ListChanges::new();
    changes.set_base_revision(revision.to_vec());
    changes.deltas.push(delta);
    changes.set_want_resulting_revisions(true);
    changes
}

fn item(uri: &str) -> Item {
    let mut item = Item::new();
    item.set_uri(uri.to_owned());
    item
}

fn add(uris: &[String]) -> Op {
    let mut add = Add::new();
    add.items = uris.iter().map(|uri| item(uri)).collect();
    add.set_add_last(true);
    let mut op = Op::new();
    op.set_kind(Kind::ADD);
    op.add = MessageField::some(add);
    op
}

/// Remove every requested occurrence, backwards so duplicate URIs keep correct indices.
fn removals(content: &SelectedListContent, wanted: &HashSet<String>) -> Vec<Op> {
    content
        .contents
        .items
        .iter()
        .enumerate()
        .rev()
        .filter_map(|(index, item)| {
            let uri = playlist_uri(item.uri()).unwrap_or_else(|_| item.uri().to_owned());
            if !wanted.contains(&uri) {
                return None;
            }
            let mut rem = Rem::new();
            rem.set_from_index(index as i32);
            rem.set_length(1);
            let mut op = Op::new();
            op.set_kind(Kind::REM);
            op.rem = MessageField::some(rem);
            Some(op)
        })
        .collect()
}

async fn edit(
    session: &Session,
    expected: &CollectionSession,
    path: &str,
    snapshot: &SelectedListContent,
    ops: Vec<Op>,
) -> Result<(), Error> {
    if ops.is_empty() {
        return Ok(());
    }
    let raw = post(
        session,
        expected,
        &format!("{path}/changes"),
        &changes(snapshot.revision(), ops),
    )
    .await?;
    let reply = SelectedListContent::parse_from_bytes(&raw)?;
    if reply.multiple_heads() || reply.changes_require_resync() {
        return Err(Error::unavailable(
            "playlist revision conflict; refresh and retry",
        ));
    }
    if reply.revision().is_empty() && reply.resulting_revisions.is_empty() {
        return Err(Error::data_loss("playlist edit was not confirmed"));
    }
    Ok(())
}

pub async fn set_saved(session: &Session, uris: &[String], saved: bool) -> Result<(), Error> {
    let wanted: HashSet<_> = uris
        .iter()
        .map(|uri| playlist_uri(uri))
        .collect::<Result<_, _>>()?;
    let expected = collection_session::identity(session)?;
    let path = rootlist_path(&expected);
    let content = snapshot(session, &expected, &path).await?;
    let ops = if saved {
        let existing: HashSet<_> = content
            .contents
            .items
            .iter()
            .filter_map(|item| playlist_uri(item.uri()).ok())
            .collect();
        // Preserve caller order while avoiding duplicate rootlist entries.
        let mut seen = existing;
        let missing: Vec<_> = uris
            .iter()
            .map(|uri| playlist_uri(uri))
            .collect::<Result<Vec<_>, _>>()?
            .into_iter()
            .filter(|uri| seen.insert(uri.clone()))
            .collect();
        if missing.is_empty() {
            vec![]
        } else {
            vec![add(&missing)]
        }
    } else {
        removals(&content, &wanted)
    };
    edit(session, &expected, &path, &content, ops).await
}

fn playable_uris(uris: &[String]) -> Result<(), Error> {
    for uri in uris {
        if uri.split(':').count() != 3
            || !matches!(
                SpotifyUri::from_uri(uri),
                Ok(SpotifyUri::Track { .. } | SpotifyUri::Episode { .. })
            )
        {
            return Err(Error::invalid_argument(
                "playlist items must be track or episode URIs",
            ));
        }
    }
    Ok(())
}

pub async fn update_items(
    session: &Session,
    id: &str,
    uris: &[String],
    saved: bool,
) -> Result<(), Error> {
    let id = playlist_id(id)?;
    playable_uris(uris)?;
    if uris.is_empty() {
        return Ok(());
    }
    let expected = collection_session::identity(session)?;
    let path = format!("/playlist/v2/playlist/{id}");
    let content = snapshot(session, &expected, &path).await?;
    let ops = if saved {
        vec![add(uris)]
    } else {
        removals(&content, &uris.iter().cloned().collect())
    };
    edit(session, &expected, &path, &content, ops).await
}

fn attributes(name: &str, description: &str, collaborative: bool) -> Op {
    let mut values = ListAttributes::new();
    values.set_name(name.to_owned());
    if !description.is_empty() {
        values.set_description(description.to_owned());
    }
    values.set_collaborative(collaborative);
    let mut state = ListAttributesPartialState::new();
    state.values = MessageField::some(values);
    if description.is_empty() {
        state
            .no_value
            .push(ListAttributeKind::LIST_DESCRIPTION.into());
    }
    let mut update = UpdateListAttributes::new();
    update.new_attributes = MessageField::some(state);
    let mut op = Op::new();
    op.set_kind(Kind::UPDATE_LIST_ATTRIBUTES);
    op.update_list_attributes = MessageField::some(update);
    op
}

fn validate_attributes(name: &str, public: bool, collaborative: bool) -> Result<(), Error> {
    if name.trim().is_empty() || (public && collaborative) {
        return Err(Error::invalid_argument(
            "a playlist needs a name; collaborative playlists must be private",
        ));
    }
    Ok(())
}

async fn visibility(
    session: &Session,
    expected: &CollectionSession,
    id: &str,
    public: bool,
) -> Result<(), Error> {
    let path = rootlist_path(expected);
    let content = snapshot(session, expected, &path).await?;
    let uri = format!("spotify:playlist:{id}");
    let index = content
        .contents
        .items
        .iter()
        .position(|item| playlist_uri(item.uri()).ok().as_ref() == Some(&uri))
        .ok_or_else(|| Error::failed_precondition("playlist is not in your library"))?;
    let mut values = ItemAttributes::new();
    values.set_public(public);
    let mut state = ItemAttributesPartialState::new();
    state.values = MessageField::some(values);
    let mut update = UpdateItemAttributes::new();
    update.set_index(index as i32);
    update.new_attributes = MessageField::some(state);
    let mut op = Op::new();
    op.set_kind(Kind::UPDATE_ITEM_ATTRIBUTES);
    op.update_item_attributes = MessageField::some(update);
    edit(session, expected, &path, &content, vec![op]).await
}

pub async fn create(
    session: &Session,
    name: &str,
    description: &str,
    public: bool,
    collaborative: bool,
) -> Result<String, Error> {
    validate_attributes(name, public, collaborative)?;
    let expected = collection_session::identity(session)?;
    // Creation takes an OpList, unlike the ListChanges used by /changes.
    let mut ops = OpList::new();
    ops.ops.push(attributes(name, description, collaborative));
    let raw = post(session, &expected, "/playlist/v2/playlist", &ops).await?;
    let reply = CreateListReply::parse_from_bytes(&raw)?;
    let uri = playlist_uri(reply.uri())?;
    set_saved(session, &[uri.clone()], true).await?;
    let id = uri.trim_start_matches("spotify:playlist:").to_owned();
    visibility(session, &expected, &id, public).await?;
    Ok(id)
}

pub async fn modify(
    session: &Session,
    id: &str,
    name: &str,
    description: &str,
    public: bool,
    collaborative: bool,
) -> Result<(), Error> {
    let id = playlist_id(id)?;
    validate_attributes(name, public, collaborative)?;
    let expected = collection_session::identity(session)?;
    let path = format!("/playlist/v2/playlist/{id}");
    let content = snapshot(session, &expected, &path).await?;
    if content.owner_username() != expected.account_id {
        return Err(Error::permission_denied(
            "only the playlist owner can modify its details",
        ));
    }
    edit(
        session,
        &expected,
        &path,
        &content,
        vec![attributes(name, description, collaborative)],
    )
    .await?;
    visibility(session, &expected, &id, public).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    fn uri(kind: &str, value: u8) -> String {
        format!(
            "spotify:{kind}:{}",
            SpotifyId::from_raw(&[value; 16]).unwrap().to_base62()
        )
    }

    fn page(from: i32, length: i32, revision: &[u8], uris: &[String]) -> SelectedListContent {
        let mut page = SelectedListContent::new();
        page.set_length(length);
        page.set_revision(revision.to_vec());
        let contents = page.contents.mut_or_insert_default();
        contents.set_pos(from);
        contents.set_truncated(from + (uris.len() as i32) < length);
        contents.items = uris.iter().map(|uri| item(uri)).collect();
        page
    }

    #[test]
    fn appending_preserves_order_and_duplicates_and_accepts_podcast_episodes() {
        let uris = vec![uri("track", 1), uri("episode", 2), uri("track", 1)];
        playable_uris(&uris).unwrap();
        let op = add(&uris);
        assert!(op.add.add_last());
        assert_eq!(
            op.add
                .items
                .iter()
                .map(|item| item.uri())
                .collect::<Vec<_>>(),
            uris.iter().map(String::as_str).collect::<Vec<_>>()
        );
        assert!(playable_uris(&[uri("show", 1)]).is_err());
        assert!(playable_uris(&["spotify:track:bad".into()]).is_err());
    }

    #[test]
    fn removes_all_duplicate_occurrences_in_descending_order_with_a_base_revision() {
        let first = uri("track", 1);
        let second = uri("track", 2);
        let snapshot = page(
            0,
            4,
            b"revision",
            &[first.clone(), second.clone(), first.clone(), second.clone()],
        );
        let ops = removals(&snapshot, &HashSet::from([first]));
        assert_eq!(
            ops.iter().map(|op| op.rem.from_index()).collect::<Vec<_>>(),
            [2, 0]
        );
        assert!(
            ops.iter()
                .all(|op| op.rem.length() == 1 && !op.rem.items_as_key())
        );
        let raw = changes(snapshot.revision(), ops).write_to_bytes().unwrap();
        let decoded = ListChanges::parse_from_bytes(&raw).unwrap();
        assert_eq!(decoded.base_revision(), b"revision");
        assert!(decoded.want_resulting_revisions());
        assert!(removals(&snapshot, &HashSet::from([uri("track", 3)])).is_empty());
    }

    #[test]
    fn creation_uses_an_op_list_and_preserves_all_editable_attributes() {
        let mut create = OpList::new();
        create.ops.push(attributes("日本語 🎧", "", true));
        let decoded = OpList::parse_from_bytes(&create.write_to_bytes().unwrap()).unwrap();
        let values = &decoded.ops[0].update_list_attributes.new_attributes.values;
        assert_eq!(values.name(), "日本語 🎧");
        assert_eq!(values.description(), "");
        assert!(values.collaborative());
        assert!(validate_attributes("", false, false).is_err());
        assert!(validate_attributes("name", true, true).is_err());
        assert!(validate_attributes("name", false, true).is_ok());
    }

    #[tokio::test]
    async fn reads_every_page_and_never_returns_partial_or_mixed_revision_snapshots() {
        let a = uri("playlist", 1);
        let b = uri("playlist", 2);
        let c = uri("playlist", 3);
        let mut pages = VecDeque::from([
            page(0, 3, b"revision", &[a.clone(), b.clone()]),
            page(2, 3, b"revision", &[c.clone()]),
        ]);
        let mut offsets = Vec::new();
        let content = read_pages(|from| {
            offsets.push(from);
            let page = pages.pop_front().unwrap();
            async move { Ok(page) }
        })
        .await
        .unwrap();
        assert_eq!(offsets, [0, 2]);
        assert_eq!(content.contents.items.len(), 3);
        assert!(!content.contents.truncated());
        for next in [
            page(2, 3, b"different", &[c.clone()]),
            page(2, 3, b"revision", &[]),
            page(0, 3, b"revision", &[c.clone()]),
        ] {
            let mut pages =
                VecDeque::from([page(0, 3, b"revision", &[a.clone(), b.clone()]), next]);
            assert!(
                read_pages(|_| {
                    let page = pages.pop_front().unwrap();
                    async move { Ok(page) }
                })
                .await
                .is_err()
            );
        }
        assert!(
            read_pages(|_| async { Err(Error::unavailable("offline")) })
                .await
                .is_err()
        );
    }

    #[test]
    fn canonicalizes_legacy_playlist_uris_and_escapes_rootlist_usernames() {
        let plain = uri("playlist", 1);
        let legacy = plain.replace("spotify:playlist:", "spotify:user:owner:playlist:");
        assert_eq!(playlist_uri(&legacy).unwrap(), plain);
        assert!(playlist_id("../other").is_err());
        assert!(playlist_uri(&format!("{plain}:extra")).is_err());
        let expected = CollectionSession {
            account_id: "ö/a?b".into(),
            session_id: "session".into(),
        };
        assert_eq!(
            rootlist_path(&expected),
            "/playlist/v2/user/%C3%B6%2Fa%3Fb/rootlist"
        );
    }
}
