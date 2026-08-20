use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct SavedItemsResponse<T> {
    pub items: Vec<SavedItem<T>>,
}

#[derive(Debug, Deserialize)]
pub struct SavedItem<T> {
    #[serde(alias = "album", alias = "track", alias = "episode", alias = "show")]
    pub item: T,
}

#[derive(Debug, Deserialize)]
pub struct Uri {
    pub uri: String,
}

#[derive(Debug, Deserialize)]
pub struct EpisodeUri {
    pub uri: String,
    pub show: Option<ShowUri>,
}

#[derive(Debug, Deserialize)]
pub struct ShowUri {
    pub uri: String,
}
