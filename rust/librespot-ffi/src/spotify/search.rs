// Helper for deconstructing the search json

use serde::Deserialize;

#[derive(Deserialize, Debug)]
pub struct SearchSection {
    items: Vec<Option<UriItem>>,
}

#[derive(Deserialize, Debug)]
pub struct UriItem {
    uri: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct SearchResponse {
    pub tracks: Option<SearchSection>,
    pub artists: Option<SearchSection>,
    pub albums: Option<SearchSection>,
    pub playlists: Option<SearchSection>,
    pub shows: Option<SearchSection>,
    pub episodes: Option<SearchSection>,
    pub audiobooks: Option<SearchSection>,
}

pub(crate) fn extract_all_uris(res: SearchResponse) -> Vec<String> {
    [
        res.tracks,
        res.artists,
        res.albums,
        res.playlists,
        res.shows,
        res.episodes,
        res.audiobooks,
    ]
    .into_iter()
    .flatten()
    .flat_map(|section| {
        section
            .items
            .into_iter()
            .flatten()
            .filter_map(|item| item.uri)
    })
    .collect()
}
