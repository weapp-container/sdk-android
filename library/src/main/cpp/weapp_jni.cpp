#include <jni.h>
#include <cstdint>
#include <limits>
#include <string>
#include "weapp_container_sdk.h"

namespace {
using Session = weapp_container_sdk_session;
Session* session(jlong handle) { return reinterpret_cast<Session*>(static_cast<uintptr_t>(handle)); }
struct Result {
  weapp_container_sdk_buffer value{nullptr, 0};
  ~Result() { weapp_container_sdk_buffer_free(&value); }
  std::string text() const { return value.data ? std::string(reinterpret_cast<const char*>(value.data), value.len) : "{\"ok\":false,\"error\":{\"code\":\"ERR_ABI\",\"message\":\"Missing native result\"}}"; }
};
// JNI's modified UTF-8 is not JSON UTF-8 (notably emoji and NUL). Use the Java
// standard UTF-8 encoder/decoder, preserving every JSON string code point.
std::string utf8(JNIEnv* env, jstring value) {
  if (!value) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "null JSON string"); return {}; }
  auto cls = env->FindClass("java/lang/String");
  auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B"), env->NewStringUTF("UTF-8")));
  if (env->ExceptionCheck() || !bytes) return {};
  std::string out(env->GetArrayLength(bytes), '\0');
  env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte*>(out.data()));
  env->DeleteLocalRef(bytes); env->DeleteLocalRef(cls);
  return out;
}
jstring javaString(JNIEnv* env, const std::string& value) {
  if (value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "native result too large"); return nullptr; }
  auto bytes = env->NewByteArray(static_cast<jsize>(value.size()));
  if (!bytes) return nullptr;
  env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
  auto cls = env->FindClass("java/lang/String");
  auto out = static_cast<jstring>(env->NewObject(cls, env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V"), bytes, env->NewStringUTF("UTF-8")));
  env->DeleteLocalRef(bytes); env->DeleteLocalRef(cls);
  return out;
}
void fail(JNIEnv* env, const Result& result) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), result.text().c_str()); }
}
#define JNI_METHOD(NAME) Java_com_sonofmagic_weapp_container_sdk_NativeBridge_##NAME
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env, jobject, jstring config) {
  auto input = utf8(env, config); if (env->ExceptionCheck()) return 0;
  Session* handle = nullptr; Result result;
  auto status = weapp_container_sdk_session_new(reinterpret_cast<const uint8_t*>(input.data()), input.size(), &handle, &result.value);
  if (status != WEAPP_CONTAINER_SDK_OK) { fail(env, result); return 0; }
  return static_cast<jlong>(reinterpret_cast<uintptr_t>(handle));
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(command)(JNIEnv* env, jobject, jlong handle, jstring operation, jstring payload) {
  auto op = utf8(env, operation); auto body = utf8(env, payload); if (env->ExceptionCheck()) return nullptr;
  Result result;
  weapp_container_sdk_session_command(session(handle), reinterpret_cast<const uint8_t*>(op.data()), op.size(), reinterpret_cast<const uint8_t*>(body.data()), body.size(), &result.value);
  return javaString(env, result.text());
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(destroy)(JNIEnv* env, jobject, jlong handle) {
  auto ptr = session(handle); Result result; weapp_container_sdk_session_destroy(&ptr, &result.value); return javaString(env, result.text());
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(glesAttach)(JNIEnv* env, jobject, jlong handle) {
  Result result; weapp_container_sdk_gles_attach(session(handle), &result.value); return javaString(env, result.text());
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(glesPresent)(JNIEnv* env, jobject, jlong handle, jint width, jint height) {
  Result result; weapp_container_sdk_gles_present(session(handle), 0, static_cast<uint32_t>(width), static_cast<uint32_t>(height), &result.value); return javaString(env, result.text());
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(glesDetach)(JNIEnv* env, jobject, jlong handle, jboolean abandon) {
  Result result;
  if (abandon) weapp_container_sdk_gles_abandon(session(handle), &result.value); else weapp_container_sdk_gles_detach(session(handle), &result.value);
  return javaString(env, result.text());
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(snapshot)(JNIEnv* env, jobject, jlong handle) {
  const weapp_container_sdk_frame* frame = nullptr;
  Result acquired;
  if (weapp_container_sdk_frame_acquire(session(handle), &frame, &acquired.value) != WEAPP_CONTAINER_SDK_OK) { fail(env, acquired); return nullptr; }
  struct FrameRelease { const weapp_container_sdk_frame** frame; ~FrameRelease() { Result r; weapp_container_sdk_frame_release(frame, &r.value); } } release{&frame};
  weapp_container_sdk_frame_info_data info{}; Result result;
  if (weapp_container_sdk_frame_info(frame, &info, &result.value) != WEAPP_CONTAINER_SDK_OK) { fail(env, result); return nullptr; }
  // Explicit diagnostic snapshot: 4 LE u32 values, one LE u64 revision, then premultiplied RGBA8.
  if (info.len > static_cast<size_t>(std::numeric_limits<jsize>::max() - 24)) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "snapshot too large"); return nullptr; }
  auto out = env->NewByteArray(static_cast<jsize>(info.len + 24)); if (!out) return nullptr;
  uint8_t header[24]{}; uint32_t fields[]{info.width, info.height, info.stride, 0};
  for (int i=0;i<4;i++) for(int b=0;b<4;b++) header[i*4+b]=static_cast<uint8_t>(fields[i]>>(b*8));
  for (int b=0;b<8;b++) header[16+b]=static_cast<uint8_t>(info.revision>>(b*8));
  env->SetByteArrayRegion(out, 0, 24, reinterpret_cast<const jbyte*>(header));
  env->SetByteArrayRegion(out, 24, static_cast<jsize>(info.len), reinterpret_cast<const jbyte*>(info.data));
  return out;
}
