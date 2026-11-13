# Client
This markdown covers the topic of custom Spotify Client credentials - mainly used for interaction with Spotify Web API.
These credentials are used for "Account login" in Outify settings.

### Why use custom credentials?
Outify is built with client credentials shared amongst all other users. Spotify can easily see all this traffic and might prohibit you from using their API via the default credentials.
By using custom credentials you should no longer be easy to get flagged and limited in any way.

### Why are these credentials required?
Outify uses Spotify Web API to interact with your profile.
- playlist edits and saving/removing playlists
- following/unfollowing users and podcast library operations
- searching
- personal top artists/tracks
- ..

Without these credentials you are unable to use these features.

### Collection with playback login
Tracks, albums, and followed artists use the authenticated **playback session**, not
the separate Web API account. Playback login alone is sufficient to:
- like/unlike tracks and synchronize liked songs
- save/remove albums and read saved albums
- follow/unfollow artists and read followed artists

These operations always target the playback account, even if the Web API account
is different. A failed playback-session request does not fall back to Web API OAuth.
Playlist and podcast operations retain their existing authentication requirements.

### Using custom credentials
I. Visit [Spotify Developer's Dashboard](https://developer.spotify.com/dashboard). (You have to be logged in)
II. Click "Create app".
III. Fill in the required fields and fill out these fields as said:
- __Redirect URIs__: http://127.0.0.1:5588/account/login | This is required for OAuth to complete automatically
- __Which API/SDKs are you planning to use?__: Web API | This is required for already mentioned features to work correctly.
IV. Copy your __Client ID__ and __Client secret__ (need to click on "View client secret").
V. Open Outify. Navigate to settings > Playback > Scroll down to Advanced Settings (click) > paste your client id and secret respectively.
VI. Restart Outify for your credentials to take effect.
