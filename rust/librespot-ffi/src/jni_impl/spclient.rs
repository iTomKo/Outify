use jni::{
    JNIEnv,
    objects::{JClass, JObjectArray, JString},
    sys::{jboolean, jint, jobjectArray, jstring},
};
use librespot_core::{SpotifyId, SpotifyUri};
use protobuf::Message;

use crate::{
    jni_utils::{optionable_string, throw_exception, vec_to_jstring_array}, outifyuri::{OutifyUri, UsernameCache}, spotify::{client::{SavedItemType, SpotifyClient}, error::SpotifyApiError},
};

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_username")]
pub extern "system" fn username(env: JNIEnv, _class: JClass) -> jstring {
    let username = match crate::spclient::get_username() {
        Ok(u) => u,
        Err(e) => {
            error!("failed to get username from session: {e}");
            return std::ptr::null_mut();
        }
    };

    match env.new_string(username) {
        Ok(u) => u.into_raw(),
        Err(e) => {
            error!("jni new_string failed for username: {e}");
            return std::ptr::null_mut();
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getCurrentUserProfile")]
pub extern "system" fn get_current_user(env: JNIEnv, _class: JClass) -> jstring {
    let client = SpotifyClient::get();

    let rt = crate::network_rt();

    let result = match rt.block_on(async { client.get_current_user().await }) {
        Ok(r) => match serde_json::to_string(&r) {
            Ok(j) => j,
            Err(e) => {
                error!("serde for playback current user failed: {e}");
                return std::ptr::null_mut();
            }
        },
        Err(e) => {
            error!("get_current_user_playback api call failed: {e}");
            return std::ptr::null_mut();
        }
    };

    match env.new_string(&result) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            error!("jni new_string failed for playback current user result: {e}");
            return std::ptr::null_mut();
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_search")]
pub extern "system" fn spotify_search(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
    jtype: JString,
    offset: jint,
    limit: jint,
) -> jobjectArray {
    let client = SpotifyClient::get();

    let query: String = match env.get_string(&query) {
        Ok(q) => q.into(),
        Err(e) => {
            error!("jni get_string failed for search query: {e}");
            return std::ptr::null_mut();
        }
    };

    let jtype: String = match env.get_string(&jtype) {
        Ok(q) => q.into(),
        Err(e) => {
            error!("jni get_string failed for search type: {e}");
            return std::ptr::null_mut();
        }
    };

    let rt = crate::network_rt();

    let limit = if limit == -1 { None } else { Some(limit) };
    let offset = if offset == -1 { None } else { Some(offset) };

    let uris_res = rt.block_on(async { client.search(&query, &jtype, limit, offset).await });

    let uris = match uris_res {
        Ok(u) => u,
        Err(e) => {
            error!("spotify search api failed: {e}");
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Spotify search failed: {e}"),
            );
            return std::ptr::null_mut();
        }
    };

    vec_to_jstring_array(&mut env, uris)
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getHomeFeed")]
pub extern "system" fn get_home_feed(
    mut env: JNIEnv,
    _class: JClass,
    expected: JString,
    zone: JString,
) -> jstring {
    crate::jni_utils::guard("SpClient.getHomeFeed", std::ptr::null_mut(), || {
        let result = (|| -> Result<String, librespot_core::Error> {
            let expected: String = env
                .get_string(&expected)
                .map_err(librespot_core::Error::invalid_argument)?
                .into();
            let expected =
                serde_json::from_str(&expected).map_err(librespot_core::Error::invalid_argument)?;
            let zone: String = env
                .get_string(&zone)
                .map_err(librespot_core::Error::invalid_argument)?
                .into();
            let session = crate::session::with_session(Clone::clone)?;
            let feed =
                crate::network_rt().block_on(crate::home::fetch(&session, &expected, &zone))?;
            serde_json::to_string(&feed).map_err(librespot_core::Error::internal)
        })();
        match result {
            Ok(json) => env
                .new_string(json)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    format!("Cannot load home: {error}"),
                );
                std::ptr::null_mut()
            }
        }
    })
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_saveItems")]
pub extern "system" fn save_item(mut env: JNIEnv, _class: JClass, uris: JObjectArray) -> jboolean {
    let client = SpotifyClient::get();

    let length = env.get_array_length(&uris).unwrap();
    let mut rust_uris = Vec::with_capacity(length as usize);

    for i in 0..length {
        let obj = env.get_object_array_element(&uris, i).unwrap();
        let jstr: JString = JString::from(obj);
        let rust_string: String = env.get_string(&jstr).unwrap().into();
        rust_uris.push(rust_string);
    }

    let rt = crate::network_rt();

    let result = rt.block_on(async { client.save_items(rust_uris).await });

    match result {
        Ok(status) => {
            let success = status.is_success();
            if !success {
                warn!("save_items returned {}", status.as_str());
            }

            success as jboolean
        }
        Err(e) => {
            error!("save_items api call failed: {e}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_deleteItems")]
pub extern "system" fn delete_items(
    mut env: JNIEnv,
    _class: JClass,
    uris: JObjectArray,
) -> jboolean {
    let client = SpotifyClient::get();

    let length = env.get_array_length(&uris).unwrap();
    let mut rust_uris = Vec::with_capacity(length as usize);

    for i in 0..length {
        let obj = env.get_object_array_element(&uris, i).unwrap();
        let jstr: JString = JString::from(obj);
        let rust_string: String = env.get_string(&jstr).unwrap().into();
        rust_uris.push(rust_string);
    }

    let rt = crate::network_rt();

    let result = rt.block_on(async { client.delete_items(rust_uris).await });

    match result {
        Ok(status) => {
            let success = status.is_success();
            if !success {
                warn!("delete_items returned {}", status.as_str());
            }

            success as jboolean
        }
        Err(e) => {
            error!("delete_items api call failed: {e}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getSavedItems")]
pub extern "system" fn get_saved_items(
    mut env: JNIEnv,
    _class: JClass,
    item_type: JString,
) -> jstring {
    let client = SpotifyClient::get();

    let rt = crate::network_rt();

    let item_type_raw: String = match env.get_string(&item_type) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("jni get_string failed for item_type: {e}");
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid item_type: {e}"),
            );
            return std::ptr::null_mut();
        }
    };

    let item_type: SavedItemType = match item_type_raw.parse() {
        Ok(t) => t,
        Err(e) => {
            error!("invalid SavedItemType: {e}");
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "item_type must be 'tracks', 'albums', 'artists', 'episodes', or 'shows'",
            );
            return std::ptr::null_mut();
        }
    };

    let result = match rt.block_on(client.get_saved(item_type)) {
        Ok(items) => items
            .items
            .into_iter()
            .map(|i| i.item.uri)
            .collect::<Vec<_>>()
            .join(","),

        Err(e) => {
            error!("get_saved_uris failed: {e}");
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Spotify request failed: {e}"),
            );
            return std::ptr::null_mut();
        }
    };

    match env.new_string(result) {
        Ok(jni_str) => jni_str.into_raw(),
        Err(e) => {
            error!("jni new_string failed: {e}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_isFollowingUser")]
pub extern "system" fn is_following_user(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jboolean {
    let uri: String = match env.get_string(&uri) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid user URI: {error}"),
            );
            return 0;
        }
    };
    let result = crate::network_rt().block_on(async {
        let session = crate::session::with_session(Clone::clone)?;
        crate::pathfinder::is_following(&session, &uri).await
    });
    match result {
        Ok(following) => following as jboolean,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Failed to read user follow state: {error}"),
            );
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getSavedEpisodeItems")]
pub extern "system" fn get_saved_episode_items(mut env: JNIEnv, _class: JClass) -> jstring {
    let client = SpotifyClient::get();

    let rt = crate::network_rt();

    let result = match rt.block_on(client.get_saved_episode_items()) {
        Ok(items) => {
            let pairs: Vec<String> = items
                .items
                .into_iter()
                .filter_map(|i| {
                    let ep_uri = i.item.uri;
                    let show_uri = i.item.show.map(|s| s.uri).unwrap_or_default();
                    Some(format!("{ep_uri},{show_uri}"))
                })
                .collect();
            pairs.join(";")
        }
        Err(e) => {
            error!("get_saved_episode_items failed: {e}");
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Spotify request failed: {e}"),
            );
            return std::ptr::null_mut();
        }
    };

    match env.new_string(result) {
        Ok(jni_str) => jni_str.into_raw(),
        Err(e) => {
            error!("jni new_string failed: {e}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getEpisodeDetails")]
pub extern "system" fn get_episode_details(
    mut env: JNIEnv,
    _class: JClass,
    episode_id: JString,
) -> jstring {
    let client = SpotifyClient::get();

    let rt = crate::network_rt();

    let episode_id: String = match env.get_string(&episode_id) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("jni get_string failed for episode_id: {e}");
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid episode_id: {e}"),
            );
            return std::ptr::null_mut();
        }
    };

    let result = match rt.block_on(client.get_episode_details(&episode_id)) {
        Ok(details) => match serde_json::to_string(&details) {
            Ok(json) => json,
            Err(e) => {
                error!("failed to serialize episode details: {e}");
                let _ = env.throw_new(
                    "java/lang/RuntimeException",
                    format!("Serialization failed: {e}"),
                );
                return std::ptr::null_mut();
            }
        },
        Err(SpotifyApiError::Http(_status, body)) => body,
        Err(e) => {
            error!("get_episode_details failed: {e}");
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Spotify request failed: {e}"),
            );
            return std::ptr::null_mut();
        }
    };

    match env.new_string(result) {
        Ok(jni_str) => jni_str.into_raw(),
        Err(e) => {
            error!("jni new_string failed: {e}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_transferPlaybackDevice")]
pub extern "system" fn transfer_playback_device(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JString,
    expected_session: JString,
) -> jboolean {
    let client = SpotifyClient::get();

    let device_id: String = match env.get_string(&device_id) {
        Ok(t) => t.into(),
        Err(e) => {
            error!("jni get_string failed for device id: {e}");
            return 0;
        }
    };

    let expected_session: String = match env.get_string(&expected_session) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Missing playback session identity: {error}"),
            );
            return 0;
        }
    };
    let expected = match serde_json::from_str::<crate::collection_session::CollectionSession>(
        &expected_session,
    ) {
        Ok(identity) => identity,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid playback session identity: {error}"),
            );
            return 0;
        }
    };
    let rt = crate::network_rt();

    let result = rt.block_on(async { client.transfer_playback(device_id, expected).await });

    match result {
        Ok(result) => result.is_success() as jboolean,
        Err(e) => {
            error!("transfer_playback api call failed: {e}");
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Connect transfer failed: {e}"),
            );
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getDevices")]
pub extern "system" fn get_devices(mut env: JNIEnv, _class: JClass) -> jstring {
    let client = SpotifyClient::get();

    let rt = crate::network_rt();

    let result = rt.block_on(async { client.get_devices().await });
    match result {
        Ok(devices) => match serde_json::to_string(&devices) {
            Ok(json) => match env.new_string(&json) {
                Ok(r) => r.into_raw(),
                Err(e) => {
                    error!("jni new_string failed for devices result: {e}");
                    std::ptr::null_mut()
                }
            },
            Err(e) => {
                error!("serde for devices result failed: {e}");
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            error!("get_devices api call failed: {e}");
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Connect device lookup failed: {e}"),
            );
            return std::ptr::null_mut();
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getConnectPlayback")]
pub extern "system" fn get_connect_playback(mut env: JNIEnv, _class: JClass) -> jstring {
    let result = crate::spirc::playback_snapshot()
        .and_then(|s| serde_json::to_string(&s).map_err(librespot_core::Error::internal));
    match result {
        Ok(json) => env
            .new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_sendConnectCommand")]
pub extern "system" fn send_connect_command(
    mut env: JNIEnv,
    _class: JClass,
    raw: JString,
) -> jboolean {
    let result = (|| {
        let raw: String = env
            .get_string(&raw)
            .map_err(librespot_core::Error::internal)?
            .into();
        let command =
            serde_json::from_str(&raw).map_err(librespot_core::Error::invalid_argument)?;
        crate::network_rt().block_on(crate::spirc::remote_command(command))
    })();
    match result {
        Ok(()) => 1,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Remote control failed: {error}. Check playback before retrying."),
            );
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_addToPlaylist")]
pub extern "system" fn add_to_playlist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_id: JString,
    uris: JObjectArray,
) -> jboolean {
    let client = SpotifyClient::get();

    let playlist_id: String = match env.get_string(&playlist_id) {
        Ok(p) => p.into(),
        Err(e) => {
            error!("jni get_string failed for add_to_playlist id: {e}");
            return 0 as jboolean;
        }
    };

    let length = env.get_array_length(&uris).unwrap();
    let mut rust_uris = Vec::with_capacity(length as usize);

    for i in 0..length {
        let obj = env.get_object_array_element(&uris, i).unwrap();
        let jstr: JString = JString::from(obj);
        let rust_string: String = env.get_string(&jstr).unwrap().into();
        rust_uris.push(rust_string);
    }

    let rt = crate::network_rt();

    let result = rt.block_on(async { client.add_to_playlist(playlist_id, rust_uris).await });

    match result {
        Ok(status) => {
            let success = status.is_success();
            if !success {
                warn!("add_to_playlist returned {}", status.as_str());
            }

            success as jboolean
        }
        Err(e) => {
            error!("add_to_playlist api call failed: {e}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_deleteFromPlaylist")]
pub extern "system" fn delete_from_playlist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_id: JString,
    uris: JObjectArray,
) -> jboolean {
    let client = SpotifyClient::get();

    let playlist_id: String = match env.get_string(&playlist_id) {
        Ok(p) => p.into(),
        Err(e) => {
            error!("jni get_string failed for delete_from_playlist id: {e}");
            return 0 as jboolean;
        }
    };

    let length = env.get_array_length(&uris).unwrap();
    let mut rust_uris = Vec::with_capacity(length as usize);

    for i in 0..length {
        let obj = env.get_object_array_element(&uris, i).unwrap();
        let jstr: JString = JString::from(obj);
        let rust_string: String = env.get_string(&jstr).unwrap().into();
        rust_uris.push(rust_string);
    }

    let rt = crate::network_rt();

    let result = rt.block_on(async { client.delete_from_playlist(playlist_id, rust_uris).await });

    match result {
        Ok(status) => {
            let success = status.is_success();
            if !success {
                warn!("delete_from_playlist returned {}", status.as_str());
            }

            success as jboolean
        }
        Err(e) => {
            error!("delete_from_playlist api call failed: {e}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_createPlaylist")]
pub extern "system" fn create_playlist(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    description: JString,
    public: jboolean,
    collaborative: jboolean,
) -> jstring {
    let client = SpotifyClient::get();

    let name: String = match env.get_string(&name) {
        Ok(n) => n.into(),
        Err(e) => {
            error!("jni get_string failed for create_playlist name: {e}");
            throw_exception(&mut env, format!("failed to get playlist name: {e}"));
            return std::ptr::null_mut();
        }
    };

    let description = optionable_string(&mut env, description);

    let rt = crate::network_rt();

    let result = rt.block_on(async {
        client
            .create_playlist(name, description, public != 0, collaborative != 0)
            .await
    });

    match result {
        Ok(response) => match env.new_string(&response.id) {
            Ok(id) => id.into_raw(),
            Err(e) => {
                throw_exception(&mut env, format!("failed to convert into jstring: {e}"));
                return std::ptr::null_mut();
            }
        },
        Err(e) => {
            error!("create_playlist api call failed: {e}");
            throw_exception(&mut env, format!("create_playlist failed: {e}"));
            return std::ptr::null_mut();
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_modifyPlaylist")]
pub extern "system" fn modify_playlist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_id: JString,
    name: JString,
    description: JString,
    public: jboolean,
    collaborative: jboolean,
) -> jint {
    let client = SpotifyClient::get();

    let playlist_id: String = match env.get_string(&playlist_id) {
        Ok(n) => n.into(),
        Err(e) => {
            error!("jni get_string failed for modify_playlist id: {e}");
            throw_exception(&mut env, format!("failed to get playlist id: {e}"));
            return 0;
        }
    };

    let name: String = match env.get_string(&name) {
        Ok(n) => n.into(),
        Err(e) => {
            error!("jni get_string failed for modify_playlist name: {e}");
            throw_exception(&mut env, format!("failed to get playlist name: {e}"));
            return 0;
        }
    };

    let description = optionable_string(&mut env, description);

    let rt = crate::network_rt();

    let result = rt.block_on(async {
        client
            .modify_playlist(
                playlist_id,
                name,
                description,
                public != 0,
                collaborative != 0,
            )
            .await
    });

    match result {
        Ok(status) => status.as_u16().into(),
        Err(e) => {
            error!("modify_playlist api call failed: {e}");
            throw_exception(&mut env, format!("modify_playlist failed: {e}"));
            return 0;
        }
    }
}

// Searches for tracks using context
#[unsafe(no_mangle)]
#[deprecated]
pub extern "system" fn Java_cc_tomko_outify_core_SpClient_searchContext(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
) -> jstring {
    let rt = crate::network_rt();

    let query: String = match env.get_string(&query) {
        Ok(q) => q.into(),
        Err(e) => {
            error!("jni get_string failed for search context query: {e}");
            return std::ptr::null_mut();
        }
    };

    let json_res = rt.block_on(async {
        let uri = format!("spotify:search:{}", query);

        match crate::spclient::get_context(uri.as_str()).await {
            Ok(context) => {
                let uris: Vec<String> = context
                    .pages
                    .iter()
                    .flat_map(|page| &page.tracks)
                    .filter_map(|track| track.uri.clone())
                    .collect();

                match serde_json::to_string(&uris) {
                    Ok(s) => Ok(s),
                    Err(e) => {
                        error!("serde for search context uris failed: {e}");
                        Err(())
                    }
                }
            }
            Err(e) => {
                error!("get_context for search failed: {e}");
                Err(())
            }
        }
    });

    let json = match json_res {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    match env.new_string(json) {
        Ok(jstr) => jstr.into_raw(),
        Err(e) => {
            error!("jni new_string failed for search context: {e:?}");
            std::ptr::null_mut()
        }
    }
}

// Gets user collection
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_SpClient_getCollectionSession(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    crate::jni_utils::guard(
        "SpClient.getCollectionSession",
        std::ptr::null_mut(),
        || {
            let result = crate::session::with_session(crate::collection_session::identity)
                .and_then(|identity| identity)
                .and_then(|identity| serde_json::to_string(&identity).map_err(Into::into));
            match result {
                Ok(json) => env
                    .new_string(json)
                    .map(|value| value.into_raw())
                    .unwrap_or(std::ptr::null_mut()),
                Err(error) => {
                    let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
                    std::ptr::null_mut()
                }
            }
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_SpClient_getSavedTrackCollection(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    crate::jni_utils::guard(
        "SpClient.getSavedTrackCollection",
        std::ptr::null_mut(),
        || {
            let result = (|| -> Result<String, librespot_core::Error> {
                let session = crate::session::with_session(|session| session.clone())?;
                let identity = crate::collection_session::identity(&session)?;
                let tracks =
                    crate::network_rt().block_on(crate::collection_api::saved_tracks(&session))?;
                crate::collection_session::ensure_current(&identity)?;
                Ok(serde_json::json!({"session": identity, "tracks": tracks}).to_string())
            })();
            match result {
                Ok(json) => env
                    .new_string(json)
                    .map(|value| value.into_raw())
                    .unwrap_or(std::ptr::null_mut()),
                Err(error) => {
                    let _ = env.throw_new(
                        "java/lang/IllegalStateException",
                        format!("Cannot read saved tracks: {error}"),
                    );
                    std::ptr::null_mut()
                }
            }
        },
    )
}

// Query can be used to get liked songs from artist
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_SpClient_getUserCollection(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
) -> jstring {
    let rt = crate::network_rt();

    let query: Option<String> = if query.is_null() {
        None
    } else {
        match env.get_string(&query) {
            Ok(js) => Some(js.into()),
            Err(e) => {
                error!("jni get_string failed for collection query: {e}");
                return std::ptr::null_mut();
            }
        }
    };

    let json_res = rt.block_on(async {
        match crate::spclient::get_collection_uris(query.as_deref()).await {
            Ok(uris) => match serde_json::to_string(&uris) {
                Ok(s) => Ok(s),
                Err(e) => {
                    error!("serde for collection uris failed: {e}");
                    Err(())
                }
            },
            Err(e) => {
                error!("get_context for collection failed: {e}");
                Err(())
            }
        }
    });

    let json = match json_res {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    match env.new_string(json) {
        Ok(jstr) => jstr.into_raw(),
        Err(e) => {
            error!("jni new_string failed for collection result: {e:?}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_SpClient_getRootlist(
    mut env: JNIEnv,
    _class: JClass,
) -> jobjectArray {
    let rt = crate::network_rt();

    let result = rt.block_on(async {
        let bytes = crate::spclient::get_rootlist().await?;
        let content =
            librespot_protocol::playlist4_external::SelectedListContent::parse_from_bytes(&bytes)?;
        Ok::<_, librespot_core::Error>(
            content
                .contents
                .items
                .iter()
                .filter_map(|item| crate::playlist_api::playlist_uri(item.uri()).ok())
                .collect::<Vec<_>>(),
        )
    });
    match result {
        Ok(uris) => vec_to_jstring_array(&mut env, uris),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Failed to read playlist library: {error}"),
            );
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getRadioForTrack")]
pub extern "system" fn get_radio_for_track(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let rt = crate::network_rt();

    let track_uri_raw: String = match env.get_string(&track_uri) {
        Ok(js) => js.into(),
        Err(e) => {
            error!("jni get_string failed for radio track_uri: {e}");
            return std::ptr::null_mut();
        }
    };

    let outify_uri = OutifyUri::from_uri(&track_uri_raw);
    let uri_string = match crate::outifyuri::resolve_uri(&outify_uri, &UsernameCache::new()) {
        Ok(uri) => uri,
        Err(()) => return std::ptr::null_mut(),
    };

    let track_uri = match SpotifyUri::from_uri(&uri_string.as_str()) {
        Ok(u) => u,
        Err(e) => {
            error!("SpotifyUri::from_uri failed for radio track: {e}");
            return std::ptr::null_mut();
        }
    };

    let json_opt = rt.block_on(async {
        match crate::spclient::get_radio_for_track(&track_uri).await {
            Ok(bytes) => match String::from_utf8(bytes.to_vec()) {
                Ok(s) => Some(s),
                Err(e) => {
                    error!("utf8 conversion for radio response failed: {e}");
                    None
                }
            },
            Err(e) => {
                error!("get_radio_for_track request failed: {e}");
                None
            }
        }
    });

    let json = match json_opt {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    match env.new_string(json) {
        Ok(j) => j.into_raw(),
        Err(e) => {
            error!("jni new_string failed for radio result: {e}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getLyrics")]
pub extern "system" fn get_lyrics_for_track(
    mut env: JNIEnv,
    _class: JClass,
    track_id: JString,
) -> jstring {
    let rt = crate::network_rt();

    let track_id_raw: String = match env.get_string(&track_id) {
        Ok(js) => js.into(),
        Err(e) => {
            error!("jni get_string failed for lyrics track_id: {e}");
            return std::ptr::null_mut();
        }
    };

    let track_id = match SpotifyId::from_base62(&track_id_raw.as_str()) {
        Ok(u) => u,
        Err(e) => {
            error!("SpotifyId::from_base62 failed for lyrics track: {e}");
            return std::ptr::null_mut();
        }
    };

    let json_opt = rt.block_on(async {
        match crate::spclient::get_lyrics(&track_id).await {
            Ok(bytes) => match String::from_utf8(bytes.to_vec()) {
                Ok(s) => Some(s),
                Err(e) => {
                    error!("utf8 conversion for lyrics response failed: {e}");
                    None
                }
            },
            Err(e) => {
                error!("get_lyrics request failed: {e}");
                None
            }
        }
    });

    let json = match json_opt {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    match env.new_string(json) {
        Ok(j) => j.into_raw(),
        Err(e) => {
            error!("jni new_string failed for lyrics result: {e}");
            std::ptr::null_mut()
        }
    }
}

fn spclient_make_error_json(env: &JNIEnv, error_type: &str, message: &str) -> jstring {
    let json = format!(
        r#"{{"error":{{"type":"{}","message":"{}"}}}}"#,
        error_type, message
    );
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn spclient_make_success_json(env: &JNIEnv) -> jstring {
    match env.new_string(r#"{"success":true}"#) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn classify_spclient_error(err: &crate::spotify::error::SpotifyApiError) -> &'static str {
    let msg = err.to_string().to_lowercase();
    if msg.contains("unavailable") || msg.contains("service") {
        "service_unavailable"
    } else if msg.contains("auth")
        || msg.contains("token")
        || msg.contains("credential")
        || msg.contains("unauthorized")
    {
        "authentication_error"
    } else if msg.contains("rate") {
        "rate_limit"
    } else {
        "unknown"
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_isPlaybackAuthenticated")]
pub extern "system" fn is_playback_authenticated(_env: JNIEnv, _class: JClass) -> jboolean {
    let client = SpotifyClient::get();

    let rt = crate::network_rt();

    let result = rt.block_on(async { client.is_playback_authenticated().await });

    result as jboolean
}
