use super::common::*;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArtistObject {
    pub external_urls: Option<ExternalUrls>,
    pub followers: Option<Followers>,
    pub genres: Option<Vec<String>>,
    pub href: Option<String>,
    pub id: Option<String>,
    pub images: Option<Vec<ImageObject>>,
    pub name: String,
    pub popularity: Option<i32>,
    #[serde(rename = "type")]
    pub object_type: String,
    pub uri: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SimplifiedArtistObject {
    pub external_urls: Option<ExternalUrls>,
    pub href: Option<String>,
    pub id: Option<String>,
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub object_type: Option<String>,
    pub uri: Option<String>,
}
