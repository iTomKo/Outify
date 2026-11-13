use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
pub struct DevicesResponse {
    pub devices: Vec<Device>,
    pub local_device_id: String,
    pub active_device_id: String,
    pub is_playing: bool,
    pub session: crate::collection_session::CollectionSession,
}

#[derive(Serialize, Deserialize)]
pub struct Device {
    pub id: Option<String>,
    pub is_active: bool,
    pub is_private_session: bool,
    pub is_restricted: bool,
    pub name: String,
    pub r#type: String,
    pub volume_percent: Option<i32>,
    pub supports_volume: bool,
}
