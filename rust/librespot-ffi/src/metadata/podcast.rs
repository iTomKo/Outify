use librespot_metadata::{Episode, Show};
use serde::Serialize;

use crate::metadata::track::ImageJson;

// Serializable Album object
#[derive(Serialize, serde::Deserialize)]
pub struct EpisodeJson {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub duration: i32,
    pub description: String,
    pub number: i32,
    pub publish_time: i64,
    pub covers: Vec<ImageJson>,
    pub language: String,
    pub is_explicit: bool,
    pub show_name: String,
    pub keywords: Vec<String>,
    pub allow_background_playback: bool,
    pub external_url: String,
    pub episode_type: String,
    pub has_music_and_talk: bool,
    pub is_audiobook_chapter: bool,
}

impl From<&Episode> for EpisodeJson {
    fn from(episode: &Episode) -> Self {
        let episode_type = match episode.episode_type {
            librespot_metadata::episode::EpisodeType::FULL => "FULL",
            librespot_metadata::episode::EpisodeType::TRAILER => "TRAILER",
            librespot_metadata::episode::EpisodeType::BONUS => "BONUS",
        }
        .to_string();

        Self {
            id: episode.id.to_id(),
            uri: episode.id.to_uri(),
            name: episode.name.clone(),
            duration: episode.duration,
            description: episode.description.clone(),
            number: episode.number,
            publish_time: episode.publish_time.unix_timestamp(),
            covers: episode.covers.iter().map(ImageJson::from).collect(),
            language: episode.language.clone(),
            is_explicit: episode.is_explicit,
            show_name: episode.show_name.clone(),
            keywords: episode.keywords.clone(),
            allow_background_playback: episode.allow_background_playback,
            external_url: episode.external_url.clone(),
            episode_type: episode_type,
            has_music_and_talk: episode.has_music_and_talk,
            is_audiobook_chapter: episode.is_audiobook_chapter,
        }
    }
}

#[derive(Serialize, serde::Deserialize)]
pub struct ShowJson {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub description: String,
    pub publisher: String,
    pub language: String,
    pub is_explicit: bool,
    pub covers: Vec<ImageJson>,
    pub episodes: Vec<String>, // Episode URIs
    pub keywords: Vec<String>,
    pub media_type: String,
    pub consumption_order: String,
    pub trailer_uri: Option<String>,
    pub has_music_and_talk: bool,
    pub is_audiobook: bool,
}

impl From<&Show> for ShowJson {
    fn from(show: &Show) -> Self {
        let media_type = match show.media_type {
            librespot_metadata::show::ShowMediaType::MIXED => "MIXED",
            librespot_metadata::show::ShowMediaType::AUDIO => "AUDIO",
            librespot_metadata::show::ShowMediaType::VIDEO => "VIDEO",
        }
        .to_string();

        let consumption_order = match show.consumption_order {
            librespot_metadata::show::ShowConsumptionOrder::SEQUENTIAL => "SEQUENTIAL",
            librespot_metadata::show::ShowConsumptionOrder::EPISODIC => "EPISODIC",
            librespot_metadata::show::ShowConsumptionOrder::RECENT => "RECENT",
        }
        .to_string();

        Self {
            id: show.id.to_id(),
            uri: show.id.to_uri(),
            name: show.name.clone(),
            description: show.description.clone(),
            publisher: show.publisher.clone(),
            language: show.language.clone(),
            is_explicit: show.is_explicit,
            covers: show.covers.iter().map(ImageJson::from).collect(),
            episodes: show
                .episodes
                .iter()
                .map(|uri| uri.to_uri())
                .collect(),
            keywords: show.keywords.clone(),
            media_type: media_type,
            consumption_order: consumption_order,
            trailer_uri: show.trailer_uri.clone().map(|uri| uri.to_uri()),
            has_music_and_talk: show.has_music_and_talk,
            is_audiobook: show.is_audiobook,
        }
    }
}
