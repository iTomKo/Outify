use jni::{
    JNIEnv,
    objects::{JClass, JObject, JObjectArray, JString},
    sys::{jboolean, jint, jobjectArray, jstring},
};
use librespot_core::{SpotifyId, SpotifyUri};
use regex::Regex;

use crate::{
    jni_utils::{optionable_string, throw_exception, vec_to_jstring_array},
    outifyuri::OutifyUri,
    session::with_session,
    spotify::client::{SavedItemType, get_client},
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
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_current_user");
            return std::ptr::null_mut();
        }
    };

    let result = match rt.block_on(async { client.get_current_user().await }) {
        Ok(r) => match serde_json::to_string(&r) {
            Ok(j) => j,
            Err(e) => {
                error!("serde for current user failed: {e}");
                return std::ptr::null_mut();
            }
        },
        Err(e) => {
            error!("get_current_user api call failed: {e}");
            return std::ptr::null_mut();
        }
    };

    match env.new_string(&result) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            error!("jni new_string failed for current user result: {e}");
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
    let client = get_client();

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

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for search");
            return std::ptr::null_mut();
        }
    };

    let limit = if limit == -1 { None } else { Some(limit) };
    let offset = if offset == -1 { None } else { Some(offset) };

    let uris_res = rt.block_on(async { client.search(&query, &jtype, limit, offset).await });

    let uris = match uris_res {
        Ok(u) => u,
        Err(e) => {
            error!("spotify search api failed: {e}");
            return std::ptr::null_mut();
        }
    };

    vec_to_jstring_array(&mut env, uris)
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_saveItems")]
pub extern "system" fn save_item(mut env: JNIEnv, _class: JClass, uris: JObjectArray) -> jboolean {
    let client = get_client();

    let length = env.get_array_length(&uris).unwrap();
    let mut rust_uris = Vec::with_capacity(length as usize);

    for i in 0..length {
        let obj = env.get_object_array_element(&uris, i).unwrap();
        let jstr: JString = JString::from(obj);
        let rust_string: String = env.get_string(&jstr).unwrap().into();
        rust_uris.push(rust_string);
    }

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for save_items");
            return 0;
        }
    };

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
    let client = get_client();

    let length = env.get_array_length(&uris).unwrap();
    let mut rust_uris = Vec::with_capacity(length as usize);

    for i in 0..length {
        let obj = env.get_object_array_element(&uris, i).unwrap();
        let jstr: JString = JString::from(obj);
        let rust_string: String = env.get_string(&jstr).unwrap().into();
        rust_uris.push(rust_string);
    }

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for delete_items");
            return 0;
        }
    };

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
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_saved_uris");
            return std::ptr::null_mut();
        }
    };

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
                "item_type must be 'tracks' or 'albums'",
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
                "java/io/RuntimeException",
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

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getSavedEpisodeItems")]
pub extern "system" fn get_saved_episode_items(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_saved_episode_items");
            return std::ptr::null_mut();
        }
    };

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
                "java/io/RuntimeException",
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

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getEpisodeShowUri")]
pub extern "system" fn get_episode_show_uri(
    mut env: JNIEnv,
    _class: JClass,
    episode_id: JString,
) -> jstring {
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_episode_show_uri");
            return std::ptr::null_mut();
        }
    };

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

    let result = match rt.block_on(client.get_episode_show_uri(&episode_id)) {
        Ok(show_uri) => show_uri,
        Err(e) => {
            error!("get_episode_show_uri failed: {e}");
            let _ = env.throw_new(
                "java/io/RuntimeException",
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

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getUserTop")]
pub extern "system" fn get_user_top(
    mut env: JNIEnv,
    _class: JClass,
    r#type: JString,
    time_range: JString,
) -> jstring {
    let client = get_client();

    let request_type: Option<String> = if r#type.is_null() {
        None
    } else {
        match env.get_string(&r#type) {
            Ok(t) => Some(t.into()),
            Err(e) => {
                error!("jni get_string failed for top request type: {e}");
                return std::ptr::null_mut();
            }
        }
    };

    let time_range: String = match env.get_string(&time_range) {
        Ok(t) => t.into(),
        Err(e) => {
            error!("jni get_string failed for top time range: {e}");
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid time_range: {}", e),
            );
            return std::ptr::null_mut();
        }
    };

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_user_top");
            return std::ptr::null_mut();
        }
    };

    let result = rt.block_on(async { client.get_top(request_type, time_range).await });

    match result {
        Ok(result) => match serde_json::to_string(&result) {
            Ok(json) => match env.new_string(&json) {
                Ok(r) => r.into_raw(),
                Err(e) => {
                    error!("jni new_string failed for top result: {e}");
                    std::ptr::null_mut()
                }
            },
            Err(e) => {
                error!("serde for top result failed: {e}");
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            error!("get_user_top api call failed: {e}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_transferPlaybackDevice")]
pub extern "system" fn transfer_playback_device(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JString,
) -> jboolean {
    let client = get_client();

    let device_id: String = match env.get_string(&device_id) {
        Ok(t) => t.into(),
        Err(e) => {
            error!("jni get_string failed for device id: {e}");
            return 0;
        }
    };

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for transfer_playback");
            return 0;
        }
    };

    let result = rt.block_on(async { client.transfer_playback(device_id).await });

    match result {
        Ok(result) => result.is_success() as jboolean,
        Err(e) => {
            error!("transfer_playback api call failed: {e}");
            0
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getDevices")]
pub extern "system" fn get_devices(env: JNIEnv, _class: JClass) -> jstring {
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_devices");
            return std::ptr::null_mut();
        }
    };

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
            return std::ptr::null_mut();
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_isOAuthAuthenticated")]
pub extern "system" fn is_oauth_authenticated(_env: JNIEnv, _class: JClass) -> jboolean {
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for is_oauth_authenticated");
            return 0 as jboolean;
        }
    };

    let result = rt.block_on(async { client.is_oauth_authenticated().await });

    result as jboolean
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getOAuthScope")]
pub extern "system" fn get_oauth_scope(mut env: JNIEnv, _class: JClass) -> jstring {
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_oauth_scope");
            throw_exception(&mut env, "tokio runtime not available".to_string());
            return std::ptr::null_mut();
        }
    };

    let result = rt.block_on(async { client.get_scope().await });
    match result {
        Some(scope) => match env.new_string(&scope) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                error!("jni new_string failed for oauth scope: {e}");
                return std::ptr::null_mut();
            }
        },
        None => std::ptr::null_mut(),
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_addToPlaylist")]
pub extern "system" fn add_to_playlist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_id: JString,
    uris: JObjectArray,
) -> jboolean {
    let client = get_client();

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

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for add_to_playlist");
            return 0;
        }
    };

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
    let client = get_client();

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

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for delete_from_playlist");
            return 0;
        }
    };

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
    let client = get_client();

    let name: String = match env.get_string(&name) {
        Ok(n) => n.into(),
        Err(e) => {
            error!("jni get_string failed for create_playlist name: {e}");
            throw_exception(&mut env, format!("failed to get playlist name: {e}"));
            return std::ptr::null_mut();
        }
    };

    let description = optionable_string(&mut env, description);

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for create_playlist");
            return std::ptr::null_mut();
        }
    };

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
    let client = get_client();

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

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for modify_playlist");
            return 0;
        }
    };

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
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for search_context");
            return std::ptr::null_mut();
        }
    };

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
// Query can be used to get liked songs from artist
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_SpClient_getUserCollection(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
) -> jstring {
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_user_collection");
            return std::ptr::null_mut();
        }
    };

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

    let user_id = match with_session(|session| session.username()) {
        Ok(u) => u,
        Err(e) => {
            error!("with_session failed for collection user_id: {e}");
            return std::ptr::null_mut();
        }
    };

    let json_res = rt.block_on(async {
        let uri = match query {
            Some(ref q) => format!("spotify:user:{}:collection{}", user_id, q),
            None => format!("spotify:user:{}:collection", user_id),
        };

        match crate::spclient::get_context(&uri).await {
            Ok(context) => {
                let uris: Vec<String> = context
                    .pages
                    .iter()
                    .flat_map(|page| page.tracks.iter())
                    .map(|track| track.uri().to_string())
                    .collect();

                match serde_json::to_string(&uris) {
                    Ok(s) => Ok(s),
                    Err(e) => {
                        error!("serde for collection uris failed: {e}");
                        Err(())
                    }
                }
            }
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
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_rootlist");
            return std::ptr::null_mut();
        }
    };

    let uris: Vec<String> = rt.block_on(async {
        match crate::spclient::get_rootlist().await {
            Ok(bytes) => {
                let mut uris = Vec::new();
                let data = String::from_utf8_lossy(&bytes);
                let pattern = Regex::new(r"spotify:playlist:[A-Za-z0-9]+").unwrap();

                for mat in pattern.find_iter(&data) {
                    uris.push(mat.as_str().to_string());
                }

                uris
            }
            Err(_) => Vec::new(),
        }
    });

    let string_class = env.find_class("java/lang/String").unwrap();
    let array = env
        .new_object_array(uris.len() as i32, string_class, JObject::null())
        .unwrap();

    for (i, uri) in uris.iter().enumerate() {
        let jstr = env.new_string(uri).unwrap();
        env.set_object_array_element(&array, i as i32, jstr)
            .unwrap();
    }

    array.into_raw()
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_getRadioForTrack")]
pub extern "system" fn get_radio_for_track(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_radio");
            return std::ptr::null_mut();
        }
    };

    let track_uri_raw: String = match env.get_string(&track_uri) {
        Ok(js) => js.into(),
        Err(e) => {
            error!("jni get_string failed for radio track_uri: {e}");
            return std::ptr::null_mut();
        }
    };

    let outify_uri = OutifyUri::from_uri(&track_uri_raw);
    let uri_string = outify_uri.to_uri();

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
    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for get_lyrics");
            return std::ptr::null_mut();
        }
    };

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

/// Starts the OAuth flow for SpotifyClient and returns the authorization URL
#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_startOAuthFlow")]
pub extern "system" fn start_oauth_flow(env: JNIEnv, _class: JClass) -> jstring {
    let client = get_client();

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for start_oauth_flow");
            return std::ptr::null_mut();
        }
    };

    let auth_url_result = rt.block_on(async { client.start_oauth_flow().await });

    let auth_url = match auth_url_result {
        Ok(url) => url,
        Err(e) => {
            error!("start_oauth_flow api call failed: {e}");
            return std::ptr::null_mut();
        }
    };

    match env.new_string(auth_url) {
        Ok(java_str) => java_str.into_raw(),
        Err(e) => {
            error!("jni new_string failed for oauth url: {e:?}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_completeOAuthFlow")]
pub extern "system" fn complete_oauth_flow(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
) -> jstring {
    let client = get_client();

    let code: String = match env.get_string(&code) {
        Ok(js) => js.into(),
        Err(e) => {
            error!("jni get_string failed for oauth code: {e}");
            return spclient_make_error_json(&env, "authentication", "Failed to read OAuth code");
        }
    };

    let rt = match crate::TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            error!("tokio runtime not available for complete_oauth_flow");
            return spclient_make_error_json(&env, "unknown", "Tokio runtime not initialized");
        }
    };

    let result = rt.block_on(async { client.complete_oauth_flow(code).await });

    match result {
        Ok(_) => {
            debug!("oauth flow completed via jni");
            spclient_make_success_json(&env)
        }
        Err(e) => {
            error!("complete_oauth_flow api call failed: {e}");
            let err_type = classify_spclient_error(&e);
            spclient_make_error_json(&env, err_type, &e.to_string())
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

#[unsafe(export_name = "Java_cc_tomko_outify_core_SpClient_logout")]
pub extern "system" fn logout(_env: JNIEnv, _class: JClass) -> jboolean {
    let client = get_client();
    match client.remove_token() {
        Ok(_) => {
            info!("spotify oauth token removed");
            1
        }
        Err(_) => 0,
    }
}
