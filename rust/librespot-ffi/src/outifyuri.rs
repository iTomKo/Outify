use std::cell::OnceCell;

use librespot_core::SpotifyUri;

/// Lazily resolved account username, shared by a batch of uri resolutions.
pub type UsernameCache = OnceCell<String>;

#[derive(Clone, PartialEq, Eq, Hash)]
pub enum OutifyUri {
    Spotify(SpotifyUri),
    Liked,
    ArtistLiked { id: String },
}

impl OutifyUri {
    pub fn from_uri(uri: &str) -> Self {
        let mut parts = uri.split(':');

        match parts.next() {
            Some("spotify") => match SpotifyUri::from_uri(uri) {
                Ok(spotify_uri) => Self::Spotify(spotify_uri),
                Err(_) => Self::Spotify(SpotifyUri::Unknown {
                    kind: parts.next().unwrap_or("").to_owned().into(),
                    id: parts.next().unwrap_or("").to_owned(),
                }),
            },

            Some("outify") => match (parts.next(), parts.next(), parts.next()) {
                (Some("liked"), Some("artist"), Some(id)) => {
                    Self::ArtistLiked { id: id.to_owned() }
                }
                (Some("liked"), _, _) => Self::Liked,
                (Some(kind), Some(id), _) => Self::Spotify(SpotifyUri::Unknown {
                    kind: kind.to_owned().into(),
                    id: id.to_owned(),
                }),
                _ => Self::Spotify(SpotifyUri::Unknown {
                    kind: "".to_owned().into(),
                    id: "".to_owned(),
                }),
            },

            Some(kind) => Self::Spotify(SpotifyUri::Unknown {
                kind: kind.to_owned().into(),
                id: parts.next().unwrap_or("").to_owned(),
            }),

            None => Self::Spotify(SpotifyUri::Unknown {
                kind: "".to_owned().into(),
                id: "".to_owned(),
            }),
        }
    }

    /// Whether [`Self::to_uri`] needs a username.
    ///
    /// Plain Spotify URIs do not, so a caller can avoid resolving the username
    /// for work that does not require one.
    pub fn needs_username(&self) -> bool {
        matches!(self, OutifyUri::Liked | OutifyUri::ArtistLiked { .. })
    }

    /// Converts to a Spotify URI.
    ///
    /// `user_id` is only needed for the collection URIs, which are addressed by
    /// username.
    pub fn to_uri(&self, user_id: &str) -> String {
        match &self {
            OutifyUri::Spotify(uri) => uri.to_uri(),
            OutifyUri::Liked => {
                format!("spotify:user:{user_id}:collection")
            }
            OutifyUri::ArtistLiked { id } => {
                format!("spotify:user:{user_id}:collection:artist:{id}")
            }
        }
    }
}

/// Resolves `uri` to a Spotify URI, reading the username only if it is needed.
pub fn resolve_uri(uri: &OutifyUri, cell: &UsernameCache) -> Result<String, ()> {
    if !uri.needs_username() {
        return Ok(uri.to_uri(""));
    }

    if let Some(username) = cell.get() {
        return Ok(uri.to_uri(username));
    }

    match crate::session::get_username() {
        Ok(username) => {
            let _ = cell.set(username.clone());
            Ok(uri.to_uri(&username))
        }
        Err(e) => {
            warn!("cannot resolve uri, username unavailable: {e}");
            Err(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spotify_uri_ignores_username() {
        let uri = OutifyUri::from_uri("spotify:track:4cOdK2wGLETKBW3PvgPWqT");
        assert_eq!(
            uri.to_uri("someone"),
            "spotify:track:4cOdK2wGLETKBW3PvgPWqT"
        );
    }

    #[test]
    fn spotify_uri_works_without_a_username() {
        // The username used to be read from the session here, which panicked
        // whenever the session was being rebuilt.
        let uri = OutifyUri::from_uri("spotify:track:4cOdK2wGLETKBW3PvgPWqT");
        assert_eq!(uri.to_uri(""), "spotify:track:4cOdK2wGLETKBW3PvgPWqT");
    }

    #[test]
    fn liked_uses_the_given_username() {
        let uri = OutifyUri::from_uri("outify:liked");
        assert_eq!(uri.to_uri("someone"), "spotify:user:someone:collection");
    }

    #[test]
    fn collections_are_addressed_through_the_outify_scheme() {
        assert_eq!(
            OutifyUri::from_uri("outify:liked").to_uri("someone"),
            "spotify:user:someone:collection"
        );
    }

    #[test]
    fn artist_liked_uses_the_given_username() {
        let uri = OutifyUri::from_uri("outify:liked:artist:artist-id");
        assert_eq!(
            uri.to_uri("someone"),
            "spotify:user:someone:collection:artist:artist-id"
        );
    }

    #[test]
    fn only_collections_need_a_username() {
        let track = OutifyUri::from_uri("spotify:track:4cOdK2wGLETKBW3PvgPWqT");
        assert!(!track.needs_username());

        assert!(OutifyUri::from_uri("outify:liked").needs_username());
        assert!(OutifyUri::from_uri("outify:liked:artist:artist-id").needs_username());
    }

    #[test]
    fn bare_liked_prefix_is_a_collection() {
        let uri = OutifyUri::from_uri("outify:liked");
        assert!(matches!(uri, OutifyUri::Liked));
    }
}
