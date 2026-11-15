//
// updated by alex5402 on 3/8/21.
//

#include <jni.h>
#include <string>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    unsigned int art_field_size;
    int art_field_flags_offset;

    unsigned int art_method_size;
    int art_method_flags_offset;
    int art_method_native_offset;

    int class_flags_offset;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;

} HookEnv;

// Calls one of the MethodUtils static helpers and copies the returned Java string into a
// std::string. The jstring local ref is always released and a pending exception is always
// cleared, so a failed lookup degrades to an empty string instead of aborting the process on a
// null jstring. Returning by value also means the caller owns the buffer: the old code returned
// a pinned UTF-8 pointer that was never released, leaking a copy per hook for the life of the
// process.
static std::string CallMethodUtilsString(JNIEnv *env, jmethodID methodId, jobject javaMethod) {
    if (methodId == nullptr || HookEnv.method_utils_class == nullptr) {
        return std::string();
    }
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      methodId,
                                                                      javaMethod));
    if (env->ExceptionCheck()) {
        // The return value of a JNI call that raised is undefined, so it must not be used or
        // released here. Clear the exception so the next JNI call does not see it pending.
        env->ExceptionClear();
        return std::string();
    }
    if (desc == nullptr) {
        return std::string();
    }
    std::string result;
    const char *chars = env->GetStringUTFChars(desc, JNI_FALSE);
    if (chars != nullptr) { // null on OOM
        result = chars;
        env->ReleaseStringUTFChars(desc, chars);
    }
    env->DeleteLocalRef(desc);
    return result;
}

static std::string GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    return CallMethodUtilsString(env, HookEnv.get_method_desc_id, javaMethod);
}

static std::string GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    return CallMethodUtilsString(env, HookEnv.get_method_declaring_class_id, javaMethod);
}

static std::string GetMethodName(JNIEnv *env, jobject javaMethod) {
    return CallMethodUtilsString(env, HookEnv.get_method_name_id, javaMethod);
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    ALOGD("AccessFlag:flags = 0x%x,flag = 0x%x",flags,flag);
    return (flags & flag) == flag;
}

// Add error handling for method flag checking
inline static bool IsNativeMethod(char *art_method) {
    try {
        return HasAccessFlag(art_method, kAccNative);
    } catch (...) {
        ALOGD("NativeCore: Error checking native method flag, assuming not native");
        return false;
    }
}

inline static bool ClearFastNativeFlag(char *art_method) {
    // FastNative
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        if (executable == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        jobject method = env->ToReflectedMethod(clazz, methodId, true);
        void *artMethod = nullptr;
        if (artId != nullptr && method != nullptr) {
            artMethod = reinterpret_cast<void *>(env->GetLongField(method, artId));
        } else {
            env->ExceptionClear();
        }
        // Both are local refs created here; this runs once per installed hook, so leaving them
        // to the frame pop leaks a slot per hook on the calling thread.
        if (method != nullptr) {
            env->DeleteLocalRef(method);
        }
        env->DeleteLocalRef(executable);
        return artMethod;
    } else {
        return methodId;
    }
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        if (fieldClass == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        void *artField = nullptr;
        if (getArtField != nullptr) {
            artField = reinterpret_cast<void *>(env->CallLongMethod(field, getArtField));
        } else {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(fieldClass);
        return artField;
    } else {
        return env->FromReflectedField(field);
    }
}

bool CheckFlags(void *artMethod) {
    if (artMethod == nullptr) {
        ALOGD("art method is null, skipping hook");
        return false;
    }
    char *method = static_cast<char *>(artMethod);
    
    // Add error handling for flag checking
    try {
        if (!HasAccessFlag(method, kAccNative)) {
            ALOGD("Method is not native, skipping hook");
            return false;
        }
        ClearFastNativeFlag(method);
        return true;
    } catch (...) {
        ALOGD("Error checking method flags, assuming not native");
        return false;
    }
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                         void **orig_fun, bool is_static) {
    // These must stay alive until HookJniFun returns: the const char* overload below only
    // borrows the pointers, so a temporary std::string here would dangle.
    std::string class_name = GetMethodDeclaringClass(env, java_method);
    std::string method_name = GetMethodName(env, java_method);
    std::string sign = GetMethodDesc(env, java_method);
    if (class_name.empty() || method_name.empty() || sign.empty()) {
        ALOGE("jni hook error. failed to resolve method info");
        return;
    }
    HookJniFun(env, class_name.c_str(), method_name.c_str(), sign.c_str(), new_fun, orig_fun,
               is_static);
}

void
JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
                    void *new_fun, void **orig_fun, bool is_static) {
    if (HookEnv.art_method_native_offset == 0) {
        return;
    }
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }
    jmethodID method = nullptr;
    if (is_static) {
        method = env->GetStaticMethodID(clazz, method_name, sign);
    } else {
        method = env->GetMethodID(clazz, method_name, sign);
    }
    if (!method) {
        env->ExceptionClear();
        ALOGD("get method id fail: %s %s", class_name, method_name);
        env->DeleteLocalRef(clazz);
        return;
    }
    JNINativeMethod gMethods[] = {
            {method_name, sign, (void *) new_fun},
    };

    auto artMethod = reinterpret_cast<uintptr_t *>(GetArtMethod(env, clazz, method));
    if (!CheckFlags(artMethod)) {
        ALOGD("Skipping hook for non-native method: %s.%s", class_name, method_name);
        env->DeleteLocalRef(clazz);
        return;
    }
    *orig_fun = reinterpret_cast<void *>(artMethod[HookEnv.art_method_native_offset]);
    if (env->RegisterNatives(clazz, gMethods, 1) < 0) {
        ALOGE("jni hook error. class：%s, method：%s", class_name, method_name);
        env->DeleteLocalRef(clazz);
        return;
    }
    // FastNative
    if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
        AddAccessFlag((char *) artMethod, kAccFastNative);
    }
    ALOGD("register class：%s, method：%s success!", class_name, method_name);
    // FindClass hands back a fresh local ref on every call; this runs once per installed hook.
    env->DeleteLocalRef(clazz);
}

__attribute__((section (".mytext")))  JNICALL void native_offset
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void native_offset2
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void set_method_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject method) {
    jmethodID methodId = env->FromReflectedMethod(method);
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));
    if (art_method == nullptr) {
        return;
    }
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext")))  JNICALL void set_field_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    if (artField == nullptr) {
        return;
    }
    AddAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(artField, kAccPublicApi);
    }
    ClearAccessFlag(artField, kAccFinal);
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (clazz == nullptr) {
        ALOGE("jni register error. JniHook class not found!");
        env->ExceptionClear();
        return;
    }
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        ALOGE("jni register error.");
    }
    env->DeleteLocalRef(clazz);
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    registerNative(env);
    HookEnv.api_level = api_level;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (clazz == nullptr) {
        ALOGE("init jni hook error. JniHook class not found!");
        env->ExceptionClear();
        return;
    }
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");

    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");

    jobject nativeOffsetFieldObj = env->ToReflectedField(clazz, nativeOffsetFieldId, true);
    void *nativeOffsetField = nativeOffsetFieldObj == nullptr
                              ? nullptr
                              : GetFieldMethod(env, nativeOffsetFieldObj);
    env->DeleteLocalRef(nativeOffsetFieldObj);
    jobject nativeOffsetField2Obj = env->ToReflectedField(clazz, nativeOffsetField2Id, true);
    void *nativeOffsetField2 = nativeOffsetField2Obj == nullptr
                               ? nullptr
                               : GetFieldMethod(env, nativeOffsetField2Obj);
    env->DeleteLocalRef(nativeOffsetField2Obj);
    HookEnv.art_field_size = (size_t) nativeOffsetField2 - (size_t) nativeOffsetField;

    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    if (nativeOffsetField == nullptr || nativeOffsetField2 == nullptr ||
        nativeOffset == nullptr || nativeOffset2 == nullptr) {
        ALOGE("init jni hook error. failed to resolve art method/field offsets!");
        env->DeleteLocalRef(clazz);
        return;
    }
    HookEnv.art_method_size = (size_t) nativeOffset2 - (size_t) nativeOffset;

    int i = 0;
    // calc native offset
    auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    for (i = 0; i < HookEnv.art_method_size; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
            HookEnv.art_method_native_offset = i;
            break;
        }
    }
    if(i == HookEnv.art_method_size){
        ALOGE("init jni hook error. art_method_native_offset not found!");
        env->DeleteLocalRef(clazz);
        return;
    }

    uint32_t flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccNative;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {//android 10
        flags = flags | kAccPublicApi;
    }
    if (api_level >= __ANDROID_API_S__) {//android 12
        flags = flags | kAccNterpInvokeFastPathFlag;
    }

    char *start = reinterpret_cast<char *>(artMethod);
    for (i = 1; i < HookEnv.art_method_size; ++i) {
        auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
//        ALOGD("art_method_size search:0x%x",value);
        if (value == flags) {
            HookEnv.art_method_flags_offset = i * sizeof(uint32_t);
            break;
        }
    }
    if(i == HookEnv.art_method_size){
        ALOGE("init jni hook error. art_method_flags_offset not found!");
        env->DeleteLocalRef(clazz);
        return;
    }

    flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        flags = flags | kAccPublicApi;
    }
    char *fieldStart = reinterpret_cast<char *>(nativeOffsetField);
    for (i = 1; i < HookEnv.art_field_size; ++i) {
        auto value = *(int32_t *) (fieldStart + i * sizeof(int32_t));
        if (value == flags) {
            HookEnv.art_field_flags_offset = i * sizeof(int32_t);
            break;
        }
    }
    if(i == HookEnv.art_field_size){
        ALOGE("init jni hook error. art_field_flags_offset not found!");
        env->DeleteLocalRef(clazz);
        return;
    }

    // NewGlobalRef, not the FindClass local ref: this jclass is cached in HookEnv and used by
    // CallMethodUtilsString long after InitJniHook's frame has been popped, and possibly from a
    // different thread. A local ref there would be invalid.
    if (HookEnv.method_utils_class != nullptr) {
        // Re-init: drop the previous global ref instead of leaking it.
        env->DeleteGlobalRef(HookEnv.method_utils_class);
        HookEnv.method_utils_class = nullptr;
    }
    jclass methodUtilsClass = env->FindClass("top/niunaijun/jnihook/MethodUtils");
    HookEnv.method_utils_class = methodUtilsClass == nullptr
                                 ? nullptr
                                 : static_cast<jclass>(env->NewGlobalRef(methodUtilsClass));
    if (methodUtilsClass != nullptr) {
        env->DeleteLocalRef(methodUtilsClass);
    }
    if (HookEnv.method_utils_class == nullptr) {
        ALOGE("init jni hook error. MethodUtils not found!");
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return;
    }
    HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class,
                                                                   "getDeclaringClass",
                                                                   "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    env->DeleteLocalRef(clazz);
}

