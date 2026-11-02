use jni::{
    JNIEnv,
    objects::{JClass, JObject},
    sys::jboolean,
};

use crate::{
    jam_runtime::{self, Operation},
    jni_utils::{
        guard,
        jni_bridge::{self, BridgeArg, JavaCallback},
    },
};

const LISTENER_METHODS: [(&str, &str); 2] = [
    ("onChanged", "(Ljava/lang/String;)V"),
    ("onError", "(Ljava/lang/String;)V"),
];
const REQUEST_METHODS: [(&str, &str); 2] = [
    ("onSuccess", "(Ljava/lang/String;)V"),
    ("onFailure", "(Ljava/lang/String;)V"),
];

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_jam_JamNative_registerListener(
    mut env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    guard("Jam.registerListener", 0, || {
        if crate::TOKIO_RUNTIME.get().is_none() {
            return 0;
        }
        match JavaCallback::register(&mut env, callback, &LISTENER_METHODS) {
            Ok(callback) => {
                jam_runtime::register_listener(callback);
                1
            }
            Err(error) => {
                error!("cannot register Jam listener: {error}");
                0
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_jam_JamNative_unregisterListener(
    _env: JNIEnv,
    _this: JClass,
) {
    guard(
        "Jam.unregisterListener",
        (),
        jam_runtime::unregister_listener,
    );
}

fn request(mut env: JNIEnv, callback: JObject, operation: Operation) -> jboolean {
    guard("Jam.request", 0, || {
        let Some(runtime) = crate::TOKIO_RUNTIME.get() else {
            return 0;
        };
        let callback = match JavaCallback::register(&mut env, callback, &REQUEST_METHODS) {
            Ok(callback) => callback,
            Err(error) => {
                error!("cannot register Jam request callback: {error}");
                return 0;
            }
        };
        let request = jam_runtime::request(operation);
        runtime.spawn(async move {
            let result = match request.await {
                Ok(jam) => serde_json::to_string(&jam).map_err(|error| error.to_string()),
                Err(error) => Err(error),
            };
            let (method, value) = match result {
                Ok(json) => (0, json),
                Err(error) => (1, error),
            };
            jni_bridge::dispatch(callback, method, vec![BridgeArg::Str(value)]);
        });
        1
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_jam_JamNative_start(
    env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    request(env, callback, Operation::Start)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_jam_JamNative_current(
    env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    request(env, callback, Operation::Current)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_core_jam_JamNative_leave(
    env: JNIEnv,
    _this: JClass,
    callback: JObject,
) -> jboolean {
    request(env, callback, Operation::Leave)
}
