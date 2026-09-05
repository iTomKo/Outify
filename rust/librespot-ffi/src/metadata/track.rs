use librespot_core::date::ReleaseDate;
use librespot_metadata::{Album, Artist, Track, image::Image};
use serde::{Deserialize, Serialize};

use librespot_protocol::metadata::Date as DateMessage;

// Serializable Track object
#[derive(Serialize)]
pub struct TrackJson {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub album: AlbumJson,
    pub artists: Vec<ArtistJson>,
    pub popularity: i32,
    pub duration: i32,
    pub explicit: bool,
    pub files: Vec<FileJson>,
}

impl From<&Track> for TrackJson {
    fn from(track: &Track) -> Self {
        let files = track
            .files
            .0
            .iter()
            .map(|(format, file_id)| FileJson {
                r#type: format!("{:?}", format),
                id: file_id.to_base16(),
            })
            .collect();

        Self {
            id: track.id.to_id(),
            uri: track.id.to_uri(),
            name: track.name.clone(),
            album: AlbumJson::from(&track.album),
            artists: track.artists.iter().map(ArtistJson::from).collect(),
            popularity: track.popularity,
            duration: track.duration,
            explicit: track.is_explicit,
            files,
        }
    }
}

#[derive(Serialize, Deserialize)]
pub struct FileJson {
    pub r#type: String,
    pub id: String,
}

// Serializable Artist object
#[derive(Serialize, Deserialize)]
pub struct ArtistJson {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub popularity: i32,
    pub portraits: Vec<ImageJson>,
    pub tracks: Vec<String>, // Just raw SpotifyUris
    pub covers: Vec<ImageJson>,
    pub albums: Vec<String>,
    pub singles: Vec<String>,
}

impl From<&Artist> for ArtistJson {
    fn from(artist: &Artist) -> Self {
        let tracks = artist
            .top_tracks
            .0
            .iter()
            .flat_map(|top| top.tracks.iter())
            .map(|track| track.to_uri())
            .collect();

        Self {
            id: artist.id.to_id(),
            uri: artist.id.to_uri(),
            name: artist.name.clone(),
            popularity: artist.popularity,
            portraits: artist.portraits.iter().map(ImageJson::from).collect(),
            tracks: tracks,
            covers: artist.portrait_group.iter().map(ImageJson::from).collect(),
            albums: unique_albums_in_order(artist),
            singles: artist
                .singles
                .0
                .iter()
                .flat_map(|group| group.0.0.iter())
                .map(|uri| uri.to_uri())
                .collect::<Vec<String>>(),
        }
    }
}

fn unique_albums_in_order(artist: &Artist) -> Vec<String> {
    let mut result = Vec::new();
    for uri in artist.albums.0.iter().flat_map(|group| group.0.0.iter()) {
        result.push(uri.to_uri());
    }
    result
}

// Serializable Album object
#[derive(Serialize, Deserialize)]
pub struct AlbumJson {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub artists: Vec<ArtistJson>,
    pub popularity: i32,
    pub tracks: Vec<String>, // Just Spotify URI
    pub covers: Vec<ImageJson>,
    pub date: ReleaseDate,
}

impl From<&Album> for AlbumJson {
    fn from(album: &Album) -> Self {
        Self {
            id: album.id.to_id(),
            uri: album.id.to_uri(),
            name: album.name.clone(),
            artists: album.artists.iter().map(ArtistJson::from).collect(),
            popularity: album.popularity,
            tracks: album.tracks().map(|uri| uri.to_uri()).collect(),
            covers: album.covers.iter().map(ImageJson::from).collect(),
            date: album.release_date.clone(),
        }
    }
}

#[derive(Serialize, Deserialize)]
pub struct ImageJson {
    pub uri: String,
    pub size: i8, // 0 -> 3 values ranging by size
    pub width: i32,
    pub height: i32,
}

impl From<&Image> for ImageJson {
    fn from(img: &Image) -> Self {
        let size = match img.size {
            librespot_metadata::image::ImageSize::DEFAULT => 0,
            librespot_metadata::image::ImageSize::SMALL => 1,
            librespot_metadata::image::ImageSize::LARGE => 2,
            librespot_metadata::image::ImageSize::XLARGE => 3,
        };

        Self {
            uri: img.id.to_base16(),
            size,
            width: img.width,
            height: img.height,
        }
    }
}
