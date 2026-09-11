use std::sync::Arc;

use jni::{
    JNIEnv,
    objects::{GlobalRef, JObject, JValue},
    sys::{jboolean, jvalue},
};
use log::error;
use once_cell::sync::OnceCell;
use tokio::sync::mpsc;

use crate::JVM;

/// Argument forwarded to a Java callback.
#[derive(Debug)]
pub enum BridgeArg {
    Bool(bool),
    Int(i32),
    Long(i64),
    Str(String),
}

/// A Java listener whose method IDs are resolved once at registration time.
pub struct JavaCallback {
    target: GlobalRef,
    method_ids: Vec<jni::sys::jmethodID>,
}

unsafe impl Send for JavaCallback {}
unsafe impl Sync for JavaCallback {}

impl JavaCallback {
    pub fn register(
        env: &mut JNIEnv,
        obj: JObject,
        methods: &[(&str, &str)],
    ) -> jni::errors::Result<Arc<Self>> {
        let class = env.get_object_class(&obj)?;
        let mut ids = Vec::with_capacity(methods.len());
        for (name, sig) in methods {
            let mid = env.get_method_id(&class, name, sig)?;
            ids.push(mid.into_raw());
        }
        let target = env.new_global_ref(obj)?;
        Ok(Arc::new(JavaCallback { target, method_ids: ids }))
    }

    fn call(&self, env: &mut JNIEnv, method_idx: usize, args: &[jvalue]) -> Result<(), jni::errors::Error> {
        let mid = self.method_ids[method_idx];
        let env_raw = env.get_native_interface();
        if env_raw.is_null() {
            return Err(jni::errors::Error::NullDeref("JNIEnv"));
        }

        // SAFETY: `env_raw` is the native JNIEnv for the current thread and
        // `mid` was resolved against `target`'s class at registration time.
        let iface = unsafe { &(**env_raw) };
        let call = iface
            .CallVoidMethodA
            .ok_or_else(|| jni::errors::Error::JNIEnvMethodNotFound("CallVoidMethodA"))?;
        unsafe {
            call(env_raw, self.target.as_obj().as_raw(), mid, args.as_ptr());
        }
        Ok(())
    }

    fn invoke(&self, env: &mut JNIEnv, method_idx: usize, args: &[BridgeArg]) {
        let result = env.with_local_frame(16, |env| {
            let mut raw: Vec<jvalue> = Vec::with_capacity(args.len());
            // Keep the JStrings rooted until the call completes; the raw jvalues
            // may point into them only for the duration of this frame.
            let mut js_refs = Vec::with_capacity(args.len());
            for arg in args {
                match arg {
                    BridgeArg::Bool(b) => raw.push(JValue::Bool(*b as jboolean).as_jni()),
                    BridgeArg::Int(i) => raw.push(JValue::Int(*i).as_jni()),
                    BridgeArg::Long(l) => raw.push(JValue::Long(*l).as_jni()),
                    BridgeArg::Str(s) => {
                        let js = env.new_string(s)?;
                        raw.push(JValue::Object(&js).as_jni());
                        js_refs.push(js);
                    }
                }
            }
            self.call(env, method_idx, &raw)
        });
        if let Err(e) = result {
            error!("Java callback (method {method_idx}) failed: {e}");
        }
    }
}

struct Msg {
    cb: Arc<JavaCallback>,
    method_idx: usize,
    args: Vec<BridgeArg>,
}

static DISPATCHER: OnceCell<mpsc::UnboundedSender<Msg>> = OnceCell::new();

/// Queue a callback invocation.
pub fn dispatch(cb: Arc<JavaCallback>, method_idx: usize, args: Vec<BridgeArg>) {
    if let Some(tx) = DISPATCHER.get() {
        let _ = tx.send(Msg { cb, method_idx, args });
    } else {
        error!("cannot dispatch Java callback: dispatcher not started");
    }
}

pub fn start_dispatcher() {
    DISPATCHER.get_or_init(|| {
        let jvm = match JVM.get() {
            Some(j) => j,
            None => {
                error!("JVM not set, cannot start callback dispatcher");
                let (tx, _rx) = mpsc::unbounded_channel::<Msg>();
                return tx;
            }
        };

        let (tx, mut rx) = mpsc::unbounded_channel::<Msg>();

        std::thread::Builder::new()
            .name("jni-callback-dispatch".into())
            .spawn(move || {
                if let Err(e) = jvm.attach_current_thread_permanently() {
                    error!("permanent JNI attach for dispatcher failed: {e}");
                    return;
                }

                while let Some(msg) = rx.blocking_recv() {
                    let mut env = match jvm.attach_current_thread() {
                        Ok(guard) => guard,
                        Err(e) => {
                            error!("JNI attach failed in dispatcher: {e}");
                            continue;
                        }
                    };
                    msg.cb.invoke(&mut env, msg.method_idx, &msg.args);
                }
            })
            .expect("failed to spawn jni-callback-dispatch thread");

        tx
    });
}
