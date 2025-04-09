// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("tabletlink_android");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("tabletlink_android")
//      }
//    }

#include <jni.h>
#include <cstdint>

extern "C"
JNIEXPORT void JNICALL
Java_com_example_tabletlink_1android_Utils_00024Companion_xorFrames(
        JNIEnv *env,
        jobject clazz,
        jbyteArray dst_,
        jbyteArray frame_,
        jbyteArray delta_) {

    jbyte *dst = env->GetByteArrayElements(dst_, nullptr);
    jbyte *frame = env->GetByteArrayElements(frame_, nullptr);
    jbyte *delta = env->GetByteArrayElements(delta_, nullptr);

    jsize length = env->GetArrayLength(frame_);

    for (int i = 0; i < length; ++i) {
        dst[i] = frame[i] ^ delta[i];
    }

    env->ReleaseByteArrayElements(dst_, dst, 0);
    env->ReleaseByteArrayElements(frame_, frame, JNI_ABORT);
    env->ReleaseByteArrayElements(delta_, delta, JNI_ABORT);
}