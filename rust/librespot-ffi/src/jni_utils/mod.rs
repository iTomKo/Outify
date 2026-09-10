pub mod exceptions;
pub mod folders;
pub mod futures;
pub mod logger;
pub mod playback;

pub fn vec_to_jstring_array(env: &mut jni::JNIEnv, vec: Vec<String>) -> jni::sys::jobjectArray {
    let string_class = env.find_class("java/lang/String").unwrap();
    let array = env
        .new_object_array(
            vec.len() as i32,
            string_class,
            jni::objects::JObject::null(),
        )
        .unwrap();

    for (i, s) in vec.iter().enumerate() {
        let jstr = env.new_string(s).unwrap();
        env.set_object_array_element(&array, i as i32, jstr)
            .unwrap();
    }

    array.into_raw()
}

pub fn throw_exception(env: &mut jni::JNIEnv, message: String) {
    match env.throw_new("java/lang/IllegalArgumentException", message) {
        Ok(_) => {}
        Err(e) => {
            error!("jni throw_new failed: {e}");
        }
    };
}

pub fn optionable_string(
    mut env: &mut jni::JNIEnv,
    string: jni::objects::JString,
) -> Option<String> {
    if string.is_null() {
        None
    } else {
        match env.get_string(&string) {
            Ok(n) => Some(n.into()),
            Err(e) => {
                error!("jni get_string failed for optionable_string: {e}");
                throw_exception(&mut env, format!("Failed to get optional string: {e}"));
                return None;
            }
        }
    }
}
