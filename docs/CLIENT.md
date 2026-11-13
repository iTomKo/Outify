# Client
This markdown covers the topic of custom Spotify Client credentials - mainly used for interaction with Spotify Web API.
These credentials are used for "Account login" in Outify settings.

### Why use custom credentials?
Outify is built with client credentials shared amongst all other users. Spotify can easily see all this traffic and might prohibit you from using their API via the default credentials.
By using custom credentials you should no longer be easy to get flagged and limited in any way.

### Why are these credentials required?
Outify uses Spotify Web API to interact with your profile.
- personal top artists/tracks
- ..

Without these credentials you are unable to use these features.

### Collection with playback login
Library and playlist operations use the authenticated **playback session**, not
the separate Web API account. Playback login alone is sufficient to:
- search Spotify's catalog (tracks, artists, albums, playlists, shows, and episodes)
- like/unlike tracks and synchronize liked songs
- save/remove albums and read saved albums
- follow/unfollow artists and read followed artists
- create and edit playlists, add/remove tracks or episodes, and save/remove playlists
- save/remove podcast episodes, follow/unfollow shows, and read both collections
- read episode parent-show and resume information
- follow/unfollow Spotify users and read their follow state

These operations always target the playback account, even if the Web API account
is different. A failed playback-session request does not fall back to Web API OAuth.

Playlist mutations use revisioned playlist-v2 protobuf requests. Podcast collections
use collection-v2 (`listenlater` for saved episodes, `show` for followed shows).
Catalog search, user follows, and episode resume information use Pathfinder. Its persisted-query
hashes are discovered from Spotify's public web-player JavaScript and cached in
memory; credentials are sent only to authenticated Spotify API requests.
Search uses the per-category queries from the web player's lazy-loaded search bundle,
with the same playback session and no Web API fallback. OAuth is still used for
personal top artists/tracks.

### Using custom credentials
I. Visit [Spotify Developer's Dashboard](https://developer.spotify.com/dashboard). (You have to be logged in)
II. Click "Create app".
III. Fill in the required fields and fill out these fields as said:
- __Redirect URIs__: http://127.0.0.1:5588/account/login | This is required for OAuth to complete automatically
- __Which API/SDKs are you planning to use?__: Web API | This is required for already mentioned features to work correctly.
IV. Copy your __Client ID__ and __Client secret__ (need to click on "View client secret").
V. Open Outify. Navigate to settings > Playback > Scroll down to Advanced Settings (click) > paste your client id and secret respectively.
VI. Restart Outify for your credentials to take effect.
