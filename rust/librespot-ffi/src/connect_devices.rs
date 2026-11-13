//! Maps the existing Connect controller's protobuf roster to Outify's device picker contract.
use crate::{
    collection_session::CollectionSession,
    types::responses::{Device, DevicesResponse},
};
use librespot_connect::ConnectDevices;
use librespot_protocol::devices::DeviceType;

pub fn response(
    roster: ConnectDevices,
    local_device_id: String,
    session: CollectionSession,
) -> DevicesResponse {
    let mut devices = roster
        .devices
        .iter()
        .filter(|(_, info)| ConnectDevices::is_visible(info))
        .map(|(id, info)| {
            let supports_volume =
                !info.capabilities.disable_volume && info.capabilities.volume_steps > 0;
            let volume_percent = supports_volume.then(|| {
                ((info.volume.min(u16::MAX as u32) * 100 + u16::MAX as u32 / 2) / u16::MAX as u32)
                    as i32
            });
            Device {
                id: Some(id.clone()),
                is_active: roster.active_device_id == *id,
                is_private_session: info.is_private_session,
                is_restricted: ConnectDevices::is_restricted(id, info, &local_device_id),
                name: if info.name.is_empty() {
                    "Spotify Connect device".to_owned()
                } else {
                    info.name.clone()
                },
                r#type: device_type(info.device_type.enum_value_or_default()).to_owned(),
                volume_percent,
                supports_volume,
            }
        })
        .collect::<Vec<_>>();
    devices.sort_by(|a, b| {
        b.is_active
            .cmp(&a.is_active)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.id.cmp(&b.id))
    });
    DevicesResponse {
        devices,
        local_device_id,
        active_device_id: roster.active_device_id,
        is_playing: roster.is_playing,
        session,
    }
}

fn device_type(kind: DeviceType) -> &'static str {
    match kind {
        DeviceType::COMPUTER | DeviceType::CHROMEBOOK => "Computer",
        DeviceType::TABLET => "Tablet",
        DeviceType::SMARTPHONE => "Smartphone",
        DeviceType::SPEAKER => "Speaker",
        DeviceType::TV => "TV",
        DeviceType::AVR => "AVR",
        DeviceType::STB => "STB",
        DeviceType::AUDIO_DONGLE => "AudioDongle",
        DeviceType::GAME_CONSOLE => "GameConsole",
        DeviceType::CAST_VIDEO => "CastVideo",
        DeviceType::CAST_AUDIO => "CastAudio",
        DeviceType::AUTOMOBILE | DeviceType::CAR_THING => "Automobile",
        DeviceType::SMARTWATCH => "Smartwatch",
        _ => "Unknown",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_protocol::connect::{Capabilities, DeviceInfo};
    use protobuf::MessageField;

    fn session() -> CollectionSession {
        CollectionSession {
            account_id: "account".into(),
            session_id: "session".into(),
        }
    }
    fn device(name: &str, volume: u32) -> DeviceInfo {
        DeviceInfo {
            name: name.into(),
            can_play: true,
            volume,
            device_type: DeviceType::COMPUTER.into(),
            capabilities: MessageField::some(Capabilities {
                volume_steps: 64,
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    #[test]
    fn roster_mapping_preserves_ids_privacy_ownership_and_scales_volume() {
        let mut private = device("Speaker", 32768);
        private.is_private_session = true;
        private.disallow_transfer_reasons.push("restricted".into());
        let roster = ConnectDevices {
            active_device_id: "remote".into(),
            is_playing: false,
            devices: [
                ("local".into(), device("Outify", 65535)),
                ("remote".into(), private),
            ]
            .into(),
            ..Default::default()
        };
        let result = response(roster, "local".into(), session());
        assert_eq!(result.devices[0].id.as_deref(), Some("remote"));
        assert!(
            result.devices[0].is_active
                && result.devices[0].is_private_session
                && result.devices[0].is_restricted
        );
        assert_eq!(result.devices[0].volume_percent, Some(50));
        assert_eq!(result.devices[1].volume_percent, Some(100));
        let json = serde_json::to_value(result).unwrap();
        assert_eq!(json["session"]["accountId"], "account");
        assert_eq!(json["local_device_id"], "local");
        assert_eq!(json["is_playing"], false);
    }

    #[test]
    fn hidden_observers_and_offline_devices_are_filtered_without_losing_active_ownership() {
        let mut hidden = device("Hidden", 0);
        hidden.capabilities.mut_or_insert_default().hidden = true;
        let mut offline = device("Offline", 0);
        offline.is_offline = true;
        let observer = DeviceInfo {
            name: "Observer".into(),
            ..Default::default()
        };
        let roster = ConnectDevices {
            active_device_id: "hidden".into(),
            devices: [
                ("hidden".into(), hidden),
                ("offline".into(), offline),
                ("observer".into(), observer),
            ]
            .into(),
            ..Default::default()
        };
        let result = response(roster, "local".into(), session());
        assert!(result.devices.is_empty());
        assert_eq!(result.active_device_id, "hidden");
    }

    #[test]
    fn unsupported_volume_is_null_and_out_of_range_volume_is_clamped() {
        let mut unsupported = device("Fixed", 60000);
        unsupported
            .capabilities
            .mut_or_insert_default()
            .disable_volume = true;
        let roster = ConnectDevices {
            devices: [
                ("fixed".into(), unsupported),
                ("clamped".into(), device("Clamped", u32::MAX)),
            ]
            .into(),
            ..Default::default()
        };
        let result = response(roster, "local".into(), session());
        assert_eq!(result.devices[0].volume_percent, Some(100));
        assert_eq!(result.devices[1].volume_percent, None);
        assert!(!result.devices[1].supports_volume);
    }
}
