/// Macro used for creating new Java CompletableFuture
#[macro_export]
macro_rules! make_completable_future {
    ($env:expr) => {{
        let cf = $env
            .new_object("java/util/concurrent/CompletableFuture", "()V", &[])
            .expect("failed to create CompletableFuture");

        let global = $env
            .new_global_ref(&cf)
            .expect("failed to create global ref for CompletableFuture");

        (cf, global)
    }};
}

#[macro_export]
macro_rules! complete_future_success_with_fn {
    // $make_fn is expected to be a function pointer: fn(&JNIEnv) -> jni::objects::GlobalRef
    ($jvm:expr, $global_ref:expr, $make_fn:expr) => {{
        let jvm_clone = $jvm.clone();
        let future_ref = $global_ref.clone();

        crate::jni_utils::futures::spawn_background(move || {
            let mut env = jvm_clone
                .attach_current_thread()
                .expect("failed to attach JVM");

            let global_value = ($make_fn)(&env);

            let _ = env.call_method(
                future_ref.as_obj(),
                "complete",
                "(Ljava/lang/Object;)Z",
                &[::jni::objects::JValue::Object(global_value.as_obj())],
            );

            std::mem::drop(global_value);
            std::mem::drop(future_ref);
        });
    }};
}

#[macro_export]
macro_rules! complete_future_exception {
    ($jvm:expr, $future_ref:expr, $msg:expr) => {{
        let jvm_clone = $jvm.clone();
        let future_ref = $future_ref.clone();
        let msg = $msg.to_string();

        crate::jni_utils::futures::spawn_background(move || {
            let mut env = jvm_clone
                .attach_current_thread()
                .expect("failed to attach JVM");

            // Java String
            let jmsg = env.new_string(msg).expect("failed to create JString");

            let jmsg_obj = ::jni::objects::JObject::from(jmsg);

            let ex = env
                .new_object(
                    "java/lang/RuntimeException",
                    "(Ljava/lang/String;)V",
                    &[::jni::objects::JValue::Object(&jmsg_obj)],
                )
                .expect("failed to create RuntimeException");

            let ex_global = env.new_global_ref(ex).expect("failed to create GlobalRef");

            let _ = env.call_method(
                future_ref.as_obj(),
                "completeExceptionally",
                "(Ljava/lang/Throwable;)Z",
                &[::jni::objects::JValue::Object(ex_global.as_obj())],
            );

            std::mem::drop(ex_global);
            std::mem::drop(future_ref);
        });
    }};
}

#[allow(unused)]
pub(crate) fn spawn_background<F>(job: F)
where
    F: FnOnce() + Send + 'static,
{
    if let Some(h) = crate::NETWORK_RUNTIME.get() {
        let _ = h.spawn_blocking(move || job());
    } else {
        // fallback: spawn a thread so background work still runs
        error!("tokio runtime not available, falling back to std::thread::spawn");
        std::thread::spawn(move || job());
    }
}
