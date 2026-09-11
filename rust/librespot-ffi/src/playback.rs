use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JByteBuffer, JObject, JValue},
    sys::{jint, jmethodID},
};
use librespot_playback::{audio_backend::AndroidSink, config::AudioFormat};
use log::{error, warn};
use once_cell::sync::OnceCell;
use std::sync::Mutex;

static JAVA_VM: OnceCell<JavaVM> = OnceCell::new();
static PCM_CALLBACK: OnceCell<Mutex<Option<GlobalRef>>> = OnceCell::new();

#[derive(Clone, Copy)]
struct JniMethodId(jmethodID);

unsafe impl Send for JniMethodId {}
unsafe impl Sync for JniMethodId {}

// Method ID is resolved once at registration; per-frame calls use the cached ID.
static PCM_METHOD: OnceCell<JniMethodId> = OnceCell::new();

/// Player related
static BUFFER_CAPACITY: OnceCell<usize> = OnceCell::new();
static BUFFER_PTR: OnceCell<usize> = OnceCell::new();

static BUFFER_GLOBAL: OnceCell<Mutex<Option<GlobalRef>>> = OnceCell::new();

// Flags the (single, long-lived) audio thread as permanently JNI-attached so
// subsequent frames skip the expensive attach/detach round trip.
thread_local! {
    static PCM_ATTACHED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

extern "C" fn rust_pcm_trampoline(
    data: *const u8,
    len: usize,
    sample_rate: u32,
    channels: u8,
    format: AudioFormat,
) {
    if format != AudioFormat::S16 {
        return;
    }

    if data.is_null() || len == 0 {
        return;
    }

    let buffer_ptr = match BUFFER_PTR.get() {
        Some(p) => *p,
        None => return,
    };

    let buffer_capacity = match BUFFER_CAPACITY.get() {
        Some(c) => *c,
        None => return,
    };

    if len > buffer_capacity {
        warn!("pcm frame exceeds buffer capacity");
        return;
    }

    let dst = buffer_ptr as *mut u8;
    unsafe {
        // Copying PCM to buffer
        std::ptr::copy_nonoverlapping(data, dst, len);
    }

    let cb_mutex = match PCM_CALLBACK.get() {
        Some(c) => c,
        None => return,
    };

    let guard = match cb_mutex.lock() {
        Ok(g) => g,
        Err(_) => return,
    };

    let cb_ref = match &*guard {
        Some(r) => r,
        None => return,
    };

    let method_id = match PCM_METHOD.get() {
        Some(m) => m.0,
        None => return,
    };

    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => return,
    };

    // Attach this audio thread permanently exactly once.
    if !PCM_ATTACHED.with(|t| t.get()) {
        match jvm.attach_current_thread_permanently() {
            Ok(_) => PCM_ATTACHED.with(|t| t.set(true)),
            Err(_) => return,
        }
    }

    // Cheap: GetEnv for an already-attached thread.
    let env = match jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };

    let args = [
        JValue::Int(len as jint).as_jni(),
        JValue::Int(sample_rate as jint).as_jni(),
        JValue::Int(channels as jint).as_jni(),
    ];

    let env_raw = env.get_native_interface();
    if !env_raw.is_null() {
        let iface = unsafe { &(**env_raw) };
        if let Some(call) = iface.CallVoidMethodA {
            unsafe {
                call(env_raw, cb_ref.as_obj().as_raw(), method_id, args.as_ptr());
            }
        } else {
            error!("CallVoidMethodA not available in JNI interface");
        }
    }

    if let Ok(true) = env.exception_check() {
        env.exception_clear().ok();
    }
}

/// JNI registration function — called from Java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_playback_AudioEngine_registerPcmCallback(
    mut env: JNIEnv,
    _class: JObject,
    callback: JObject,
    buffer: JByteBuffer,
) {
    match env.get_java_vm() {
        Ok(jvm) => {
            let _ = JAVA_VM.set(jvm);
        }
        Err(e) => {
            error!("jni get_java_vm failed in register_pcm_callback: {e}");
            return;
        }
    }

    let global_ref = match env.new_global_ref(&callback) {
        Ok(g) => g,
        Err(e) => {
            error!("jni new_global_ref failed for pcm callback: {e}");
            return;
        }
    };

    PCM_CALLBACK.get_or_init(|| Mutex::new(None));
    if let Some(mutex) = PCM_CALLBACK.get() {
        match mutex.lock() {
            Ok(mut guard) => {
                *guard = Some(global_ref);
            }
            Err(e) => {
                error!("lock of pcm_callback mutex failed: {e}");
                return;
            }
        }
    }

    // Cache onPcmReady's method ID once.
    let class = match env.get_object_class(&callback) {
        Ok(c) => c,
        Err(e) => {
            error!("jni get_object_class failed for pcm callback: {e}");
            return;
        }
    };
    let method_id = match env.get_method_id(&class, "onPcmReady", "(III)V") {
        Ok(mid) => mid.into_raw(),
        Err(e) => {
            error!("jni get_method_id failed for onPcmReady: {e}");
            return;
        }
    };
    let _ = PCM_METHOD.set(JniMethodId(method_id));

    let ptr = match env.get_direct_buffer_address(&buffer) {
        Ok(p) => p,
        Err(e) => {
            error!("jni get_direct_buffer_address failed for pcm: {e}");
            return;
        }
    };

    let buffer_capacity = match env.get_direct_buffer_capacity(&buffer) {
        Ok(c) => c,
        Err(e) => {
            error!("jni get_direct_buffer_capacity failed for pcm: {e}");
            return;
        }
    };

    let buf_obj = JObject::from(buffer);
    let buf_global = match env.new_global_ref(buf_obj) {
        Ok(g) => g,
        Err(e) => {
            error!("jni new_global_ref failed for pcm buffer: {e}");
            return;
        }
    };

    BUFFER_GLOBAL.get_or_init(|| Mutex::new(None));
    if let Some(mutex) = BUFFER_GLOBAL.get() {
        match mutex.lock() {
            Ok(mut guard) => *guard = Some(buf_global),
            Err(e) => {
                error!("lock of buffer_global mutex failed: {e}");
                return;
            }
        }
    }

    BUFFER_PTR.set(ptr as usize).ok();
    BUFFER_CAPACITY.set(buffer_capacity).ok();

    AndroidSink::set_callback(rust_pcm_trampoline);
    info!("pcm callback registered");
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cc_tomko_outify_playback_AudioEngine_unregisterPcmCallback(
    _env: JNIEnv,
    _class: JObject,
) {
    if let Some(mutex) = PCM_CALLBACK.get() {
        if let Ok(mut guard) = mutex.lock() {
            if let Some(global) = guard.take() {
                drop(global);
            }
        }
    }

    if let Some(mutex) = BUFFER_GLOBAL.get() {
        if let Ok(mut guard) = mutex.lock() {
            if let Some(global) = guard.take() {
                drop(global);
            }
        }
    }

    info!("pcm callback unregistered");
}
