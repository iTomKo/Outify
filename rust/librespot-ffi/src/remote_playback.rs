//! Remote controls reuse the existing Connect session, never Activate or a local player.
use crate::collection_session::CollectionSession;
use librespot_connect::ConnectDevices;
use librespot_core::Error;
use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Play {
    pub context_uri: String,
    pub track_uri: Option<String>,
    pub shuffle: Option<bool>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Target {
    pub session: CollectionSession,
    pub runtime: u64,
    pub connection: u64,
    pub ownership: u64,
    pub device_id: String,
    pub track_key: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Command {
    pub target: Target,
    pub action: String,
    pub value: Option<i64>,
    pub play: Option<Play>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    target: Target,
    local_device_id: String,
    device_name: String,
    uri: String,
    duration_ms: i64,
    position_ms: i64,
    sampled_at_ms: i64,
    speed: f64,
    playing: bool,
    buffering: bool,
    shuffle: bool,
    repeat_context: bool,
    repeat_track: bool,
    volume: Option<u32>,
    allowed: Vec<&'static str>,
}

fn track_key(roster: &ConnectDevices) -> String {
    roster
        .player_state
        .as_ref()
        .map(|p| format!("{}|{}|{}", p.playback_id, p.track.uid, p.track.uri))
        .unwrap_or_default()
}

fn allowed(roster: &ConnectDevices, action: &str) -> bool {
    let Some(info) = roster.devices.get(&roster.active_device_id) else {
        return false;
    };
    let Some(p) = roster.player_state.as_ref() else {
        return false;
    };
    if !info.can_play
        || info.is_offline
        || info.capabilities.connect_disabled
        || info.capabilities.restrict_to_local
        || !info.capabilities.is_controllable
        || !info.disallow_playback_reasons.is_empty()
    {
        return false;
    }
    for r in [&p.restrictions, &p.context_restrictions] {
        if !r.disallow_remote_control_reasons.is_empty() {
            return false;
        }
        if action == "resume" && !r.disallow_playing_reasons.is_empty() {
            return false;
        }
        let reasons = match action {
            "play" => {
                if !r.disallow_interrupting_playback_reasons.is_empty()
                    || !r.disallow_updating_context_reasons.is_empty()
                {
                    return false;
                }
                &r.disallow_playing_reasons
            }
            "resume" => &r.disallow_resuming_reasons,
            "pause" => &r.disallow_pausing_reasons,
            "skip_next" => &r.disallow_skipping_next_reasons,
            "skip_prev" => &r.disallow_skipping_prev_reasons,
            "seek_to" => &r.disallow_seeking_reasons,
            "volume" => {
                if info.capabilities.disable_volume || info.capabilities.volume_steps <= 0 {
                    return false;
                }
                continue;
            }
            _ => return false,
        };
        if !reasons.is_empty() {
            return false;
        }
    }
    (!p.track.uri.is_empty() || action == "volume" || action == "play")
        && (action != "seek_to" || p.duration > 0)
}

pub fn snapshot(
    roster: &ConnectDevices,
    local: String,
    session: CollectionSession,
    runtime: u64,
    now: i64,
    delta_ms: i64,
) -> Snapshot {
    let p = roster.player_state.clone().unwrap_or_default();
    let speed = if p.playback_speed.is_finite() && p.playback_speed > 0.0 {
        p.playback_speed
    } else {
        1.0
    };
    let playing = p.is_playing && !p.is_paused && !p.is_buffering;
    let elapsed = if playing && p.timestamp > 0 {
        now.saturating_sub(p.timestamp.saturating_sub(delta_ms))
            .max(0)
    } else {
        0
    };
    let duration = p.duration.max(0);
    let position = p
        .position_as_of_timestamp
        .max(0)
        .saturating_add((elapsed as f64 * speed) as i64);
    let info = roster.devices.get(&roster.active_device_id);
    Snapshot {
        target: Target {
            session,
            runtime,
            connection: roster.connection_generation,
            ownership: roster.ownership_generation,
            device_id: roster.active_device_id.clone(),
            track_key: track_key(roster),
        },
        local_device_id: local,
        device_name: info.map(|i| i.name.clone()).unwrap_or_default(),
        uri: p.track.uri.clone(),
        duration_ms: duration,
        position_ms: if duration > 0 {
            position.min(duration)
        } else {
            position
        },
        sampled_at_ms: now,
        speed,
        playing,
        buffering: p.is_buffering && !p.is_paused,
        shuffle: p.options.shuffling_context,
        repeat_context: p.options.repeating_context,
        repeat_track: p.options.repeating_track,
        volume: info
            .filter(|i| !i.capabilities.disable_volume && i.capabilities.volume_steps > 0)
            .map(|i| i.volume.min(u16::MAX as u32)),
        allowed: [
            "play",
            "resume",
            "pause",
            "skip_next",
            "skip_prev",
            "seek_to",
            "volume",
        ]
        .into_iter()
        .filter(|a| allowed(roster, a))
        .collect(),
    }
}

pub fn validate(roster: &ConnectDevices, command: &Command, local: &str) -> Result<(), Error> {
    let t = &command.target;
    if t.device_id == local
        || t.device_id.is_empty()
        || t.device_id != roster.active_device_id
        || t.connection != roster.connection_generation
        || t.ownership != roster.ownership_generation
    {
        return Err(Error::failed_precondition(
            "Playback device changed; refresh before retrying",
        ));
    }
    if !allowed(roster, &command.action) {
        return Err(Error::permission_denied(
            "Remote control is unavailable or restricted",
        ));
    }
    if command.action == "play" {
        let play = command
            .play
            .as_ref()
            .ok_or_else(|| Error::invalid_argument("Missing play context"))?;
        play_payload(command)?;
        if let Some(shuffle) = play.shuffle {
            let p = roster
                .player_state
                .as_ref()
                .ok_or_else(|| Error::unavailable("Player state unavailable"))?;
            if shuffle != p.options.shuffling_context
                && [&p.restrictions, &p.context_restrictions]
                    .iter()
                    .any(|r| !r.disallow_toggling_shuffle_reasons.is_empty())
            {
                return Err(Error::permission_denied("Shuffle is restricted"));
            }
        }
    } else if command.play.is_some() {
        return Err(Error::invalid_argument(
            "Unexpected play context on transport command",
        ));
    }
    if command.action == "seek_to" {
        if t.track_key.is_empty() || t.track_key != track_key(roster) {
            return Err(Error::failed_precondition("Track changed before seek"));
        }
        let value = command
            .value
            .ok_or_else(|| Error::invalid_argument("Missing seek position"))?;
        if value < 0
            || roster
                .player_state
                .as_ref()
                .is_none_or(|p| value > p.duration)
        {
            return Err(Error::invalid_argument("Seek position outside track"));
        }
    }
    if command.action == "volume" && !command.value.is_some_and(|v| (0..=65535).contains(&v)) {
        return Err(Error::invalid_argument("Invalid volume"));
    }
    Ok(())
}

// Accept only supported catalog contexts, never arbitrary URLs or an unrelated user's collection.
fn catalog_uri(raw: &str, username: &str, track_only: bool) -> Result<String, Error> {
    use librespot_core::SpotifyId;
    let canonical_id = |id: &str| SpotifyId::from_base62(id).map(|id| id.to_base62());
    if raw.len() > 2048 {
        return Err(Error::invalid_argument("Play URI is too long"));
    }
    let parts: Vec<_> = raw.split(':').collect();
    let uri = match parts.as_slice() {
        ["spotify", kind @ ("track" | "episode"), id] => {
            format!("spotify:{kind}:{}", canonical_id(id)?)
        }
        [
            "spotify",
            kind @ ("album" | "artist" | "playlist" | "show"),
            id,
        ] if !track_only => format!("spotify:{kind}:{}", canonical_id(id)?),
        ["spotify", "user", user, "playlist", id] if !track_only && !user.is_empty() => {
            format!("spotify:user:{user}:playlist:{}", canonical_id(id)?)
        }
        ["outify", "liked"] if !track_only => format!("spotify:user:{username}:collection"),
        ["outify", "liked", "artist", id] if !track_only => format!(
            "spotify:user:{username}:collection:artist:{}",
            canonical_id(id)?
        ),
        ["spotify", "user", user, "collection"] if !track_only && *user == username => {
            raw.to_owned()
        }
        ["spotify", "user", user, "collection", "artist", id]
            if !track_only && *user == username =>
        {
            format!(
                "spotify:user:{username}:collection:artist:{}",
                canonical_id(id)?
            )
        }
        ["spotify", "station", "track", id] if !track_only => {
            format!("spotify:station:track:{}", canonical_id(id)?)
        }
        _ => return Err(Error::invalid_argument("Unsupported remote play URI")),
    };
    Ok(uri)
}

fn play_payload(command: &Command) -> Result<serde_json::Value, Error> {
    let play = command
        .play
        .as_ref()
        .ok_or_else(|| Error::invalid_argument("Missing play context"))?;
    let context = catalog_uri(&play.context_uri, &command.target.session.account_id, false)?;
    let track = play
        .track_uri
        .as_ref()
        .map(|uri| catalog_uri(uri, &command.target.session.account_id, true))
        .transpose()?;
    let mut options = serde_json::json!({"seek_to": 0, "initially_paused": false});
    if let Some(track) = track {
        options["skip_to"] = serde_json::json!({"track_uri": track});
    }
    if let Some(shuffle) = play.shuffle {
        options["player_options_override"] = serde_json::json!({"shuffling_context": shuffle});
    }
    Ok(serde_json::json!({
        "endpoint": "play",
        "context": {"uri": context, "url": format!("context://{context}"), "metadata": {}},
        "play_origin": {"feature_identifier": "outify", "feature_version": env!("CARGO_PKG_VERSION")},
        "options": options,
        "logging_params": {},
    }))
}

pub fn wire(command: &Command, local: &str) -> Result<(http::Method, String, Vec<u8>), Error> {
    let target = crate::native_request::component(&command.target.device_id);
    let local = crate::native_request::component(local);
    let (method, endpoint, body) = if command.action == "volume" {
        (
            http::Method::PUT,
            format!("/connect-state/v1/connect/volume/from/{local}/to/{target}"),
            serde_json::json!({"volume": command.value}),
        )
    } else {
        let mut payload = if command.action == "play" {
            play_payload(command)?
        } else {
            serde_json::json!({"endpoint": command.action})
        };
        if command.action == "seek_to" {
            payload["value"] = serde_json::json!(command.value);
        }
        (
            http::Method::POST,
            format!("/connect-state/v1/player/command/from/{local}/to/{target}"),
            serde_json::json!({"command": payload}),
        )
    };
    Ok((method, endpoint, body.to_string().into_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_protocol::{
        connect::{Capabilities, DeviceInfo},
        player::{PlayerState, ProvidedTrack},
    };
    use protobuf::MessageField;

    fn fixture() -> (ConnectDevices, Command) {
        let roster = ConnectDevices {
            active_device_id: "speaker".into(),
            connection_generation: 4,
            ownership_generation: 9,
            devices: [(
                "speaker".into(),
                DeviceInfo {
                    can_play: true,
                    volume: 32000,
                    capabilities: MessageField::some(Capabilities {
                        is_controllable: true,
                        volume_steps: 64,
                        ..Default::default()
                    }),
                    ..Default::default()
                },
            )]
            .into(),
            player_state: Some(PlayerState {
                track: MessageField::some(ProvidedTrack {
                    uri: "spotify:track:abc".into(),
                    uid: "uid".into(),
                    ..Default::default()
                }),
                playback_id: "playback".into(),
                duration: 180000,
                timestamp: 10000,
                position_as_of_timestamp: 12000,
                playback_speed: 1.0,
                is_playing: true,
                ..Default::default()
            }),
            ..Default::default()
        };
        let target = snapshot(
            &roster,
            "local".into(),
            CollectionSession {
                account_id: "account".into(),
                session_id: "login".into(),
            },
            7,
            10000,
            0,
        )
        .target;
        (
            roster,
            Command {
                target,
                action: "seek_to".into(),
                value: Some(90000),
                play: None,
            },
        )
    }

    #[test]
    fn target_guards_reject_disconnect_handoff_and_away_back() {
        let (mut roster, command) = fixture();
        assert!(validate(&roster, &command, "local").is_ok());
        roster.connection_generation += 1;
        assert!(validate(&roster, &command, "local").is_err());
        roster.connection_generation -= 1;
        roster.ownership_generation += 1;
        assert!(validate(&roster, &command, "local").is_err());
        roster.ownership_generation -= 1;
        roster.active_device_id = "another".into();
        assert!(validate(&roster, &command, "local").is_err());
        roster.active_device_id = "local".into();
        assert!(validate(&roster, &command, "local").is_err());
    }

    #[test]
    fn seek_is_bound_to_the_playback_instance_even_for_a_repeated_track() {
        let (mut roster, command) = fixture();
        roster.player_state.as_mut().unwrap().playback_id = "new-instance".into();
        assert!(validate(&roster, &command, "local").is_err());
        let mut pause = command;
        pause.action = "pause".into();
        pause.value = None;
        assert!(validate(&roster, &pause, "local").is_ok());
    }

    #[test]
    fn restrictions_and_capabilities_are_enforced_without_using_transfer_restrictions() {
        let (mut roster, mut command) = fixture();
        roster
            .devices
            .get_mut("speaker")
            .unwrap()
            .disallow_transfer_reasons
            .push("no-transfer".into());
        assert!(validate(&roster, &command, "local").is_ok());
        roster
            .player_state
            .as_mut()
            .unwrap()
            .restrictions
            .mut_or_insert_default()
            .disallow_seeking_reasons
            .push("ad".into());
        assert!(validate(&roster, &command, "local").is_err());
        command.action = "volume".into();
        command.value = Some(10000);
        assert!(validate(&roster, &command, "local").is_ok());
        roster
            .devices
            .get_mut("speaker")
            .unwrap()
            .capabilities
            .as_mut()
            .unwrap()
            .disable_volume = true;
        assert!(validate(&roster, &command, "local").is_err());
        command.action = "pause".into();
        command.value = None;
        roster
            .player_state
            .as_mut()
            .unwrap()
            .context_restrictions
            .mut_or_insert_default()
            .disallow_remote_control_reasons
            .push("restricted".into());
        assert!(validate(&roster, &command, "local").is_err());
    }

    #[test]
    fn paused_volume_is_valid_and_invalid_values_and_unknown_actions_are_rejected() {
        let (mut roster, mut command) = fixture();
        roster.player_state.as_mut().unwrap().is_paused = true;
        command.action = "volume".into();
        for value in [0, 65535] {
            command.value = Some(value);
            assert!(validate(&roster, &command, "local").is_ok());
        }
        for value in [-1, 65536] {
            command.value = Some(value);
            assert!(validate(&roster, &command, "local").is_err());
        }
        command.action = "activate".into();
        assert!(validate(&roster, &command, "local").is_err());
        command.action = "seek_to".into();
        command.value = Some(180001);
        assert!(validate(&roster, &command, "local").is_err());
    }

    #[test]
    fn position_uses_server_offset_and_never_advances_paused_or_buffering_tracks() {
        let (mut roster, command) = fixture();
        let read = |r: &ConnectDevices| {
            snapshot(
                r,
                "local".into(),
                command.target.session.clone(),
                7,
                12000,
                1000,
            )
        };
        assert_eq!(read(&roster).position_ms, 15000);
        roster.player_state.as_mut().unwrap().is_paused = true;
        assert_eq!(read(&roster).position_ms, 12000);
        assert!(!read(&roster).buffering, "a paused player is not buffering");
        roster.player_state.as_mut().unwrap().is_paused = false;
        roster.player_state.as_mut().unwrap().is_buffering = true;
        assert_eq!(read(&roster).position_ms, 12000);
        assert!(!read(&roster).playing);
        assert!(read(&roster).buffering);
    }

    #[test]
    fn wire_commands_use_connect_json_and_never_activate_or_transfer() {
        let (_, mut command) = fixture();
        let (method, endpoint, body) = wire(&command, "local").unwrap();
        assert_eq!(method, http::Method::POST);
        assert_eq!(
            endpoint,
            "/connect-state/v1/player/command/from/local/to/speaker"
        );
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&body).unwrap(),
            serde_json::json!({"command":{"endpoint":"seek_to","value":90000}})
        );
        command.action = "volume".into();
        command.value = Some(65535);
        let (method, endpoint, body) = wire(&command, "local").unwrap();
        assert_eq!(method, http::Method::PUT);
        assert_eq!(
            endpoint,
            "/connect-state/v1/connect/volume/from/local/to/speaker"
        );
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&body).unwrap(),
            serde_json::json!({"volume":65535})
        );
    }

    fn play_command(
        context: &str,
        track: Option<&str>,
        shuffle: Option<bool>,
    ) -> (ConnectDevices, Command) {
        let (roster, mut command) = fixture();
        command.action = "play".into();
        command.value = None;
        command.play = Some(Play {
            context_uri: context.into(),
            track_uri: track.map(str::to_owned),
            shuffle,
        });
        (roster, command)
    }

    #[test]
    fn selecting_a_track_uses_remote_play_and_never_a_transfer_or_activation() {
        let track = "spotify:track:4cOdK2wGLETKBW3PvgPWqT";
        let (roster, command) = play_command(track, None, None);
        assert!(validate(&roster, &command, "local").is_ok());
        let (method, endpoint, body) = wire(&command, "local").unwrap();
        assert_eq!(method, http::Method::POST);
        assert_eq!(
            endpoint,
            "/connect-state/v1/player/command/from/local/to/speaker"
        );
        let payload: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(payload["command"]["endpoint"], "play");
        assert_eq!(payload["command"]["context"]["uri"], track);
        assert_eq!(payload["command"]["options"]["seek_to"], 0);
        assert_eq!(payload["command"]["options"]["initially_paused"], false);
        assert!(
            payload["command"]["options"]
                .get("player_options_override")
                .is_none()
        );
        // Validate against the receiver's actual command codec, not only our own JSON shape.
        let decoded: librespot_core::dealer::protocol::PlayCommand =
            serde_json::from_value(payload["command"].clone()).unwrap();
        assert_eq!(decoded.context.uri.unwrap(), track);
    }

    #[test]
    fn contexts_skip_to_the_selected_track_and_resolve_liked_using_the_bound_account() {
        let track = "spotify:track:4cOdK2wGLETKBW3PvgPWqT";
        let artist = "6xBZgSMsnKVmaAxzWEwMSD";
        for (context, expected) in [
            (
                "outify:liked".to_owned(),
                "spotify:user:account:collection".to_owned(),
            ),
            (
                format!("outify:liked:artist:{artist}"),
                format!("spotify:user:account:collection:artist:{artist}"),
            ),
            (
                "spotify:album:6raEoLfzOskhSksjJIHjA3".to_owned(),
                "spotify:album:6raEoLfzOskhSksjJIHjA3".to_owned(),
            ),
        ] {
            let (roster, command) = play_command(&context, Some(track), Some(true));
            assert!(validate(&roster, &command, "local").is_ok());
            let (_, _, body) = wire(&command, "local").unwrap();
            let payload: serde_json::Value = serde_json::from_slice(&body).unwrap();
            assert_eq!(payload["command"]["context"]["uri"], expected);
            assert_eq!(payload["command"]["options"]["skip_to"]["track_uri"], track);
            assert_eq!(
                payload["command"]["options"]["player_options_override"]["shuffling_context"],
                true
            );
            let decoded: librespot_core::dealer::protocol::PlayCommand =
                serde_json::from_value(payload["command"].clone()).unwrap();
            assert_eq!(decoded.options.skip_to.unwrap().track_uri.unwrap(), track);
            assert_eq!(
                decoded
                    .options
                    .player_options_override
                    .unwrap()
                    .shuffling_context,
                Some(true)
            );
        }
    }

    #[test]
    fn remote_selection_rejects_stale_ownership_restricted_contexts_and_invalid_uris() {
        let track = "spotify:track:4cOdK2wGLETKBW3PvgPWqT";
        let (mut roster, command) = play_command(track, None, None);
        roster.ownership_generation += 1;
        assert!(validate(&roster, &command, "local").is_err());
        roster.ownership_generation -= 1;
        roster
            .player_state
            .as_mut()
            .unwrap()
            .restrictions
            .mut_or_insert_default()
            .disallow_interrupting_playback_reasons
            .push("ad".into());
        assert!(validate(&roster, &command, "local").is_err());
        for invalid in [
            "",
            "https://example.com/track",
            "spotify:track:bad/id",
            "spotify:user:someone-else:collection",
            "spotify:local:artist:album:title:1",
            "outify:liked:artist:invalid",
        ] {
            let (roster, command) = play_command(invalid, None, None);
            assert!(validate(&roster, &command, "local").is_err(), "{invalid}");
        }
        let (roster, command) =
            play_command(track, Some("spotify:album:6raEoLfzOskhSksjJIHjA3"), None);
        assert!(validate(&roster, &command, "local").is_err());
    }

    #[tokio::test]
    async fn remote_http_attempt_does_not_replay_a_rate_limit_or_ambiguous_failure() {
        use tokio::{
            io::{AsyncReadExt, AsyncWriteExt},
            net::TcpListener,
        };
        for response in [
            Some(
                "HTTP/1.1 429 Too Many Requests\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            ),
            None,
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let server = tokio::spawn(async move {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut bytes = [0; 4096];
                let _ = socket.read(&mut bytes).await.unwrap();
                if let Some(response) = response {
                    socket.write_all(response.as_bytes()).await.unwrap();
                }
                socket.shutdown().await.unwrap();
                // The future returning an error is not permission to replay the command.
                assert!(
                    tokio::time::timeout(std::time::Duration::from_millis(100), listener.accept())
                        .await
                        .is_err()
                );
            });
            let client = librespot_core::http_client::HttpClient::new(None);
            let request = http::Request::builder()
                .method(http::Method::POST)
                .uri(format!("http://{address}/command"))
                .body(bytes::Bytes::from_static(
                    b"{\"command\":{\"endpoint\":\"skip_next\"}}",
                ))
                .unwrap();
            let result = tokio::time::timeout(
                std::time::Duration::from_secs(2),
                client.request_body_once(request),
            )
            .await
            .unwrap();
            assert!(result.is_err());
            server.await.unwrap();
        }
    }
}
