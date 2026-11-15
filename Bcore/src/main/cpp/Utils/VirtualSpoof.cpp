#include <sys/system_properties.h>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>
#include <jni.h>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"

/**
 * created by alex5402 on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * TFNQw5HgWUS33Ke1eNmSFTwoQySGU7XNsK (USDT TRC20)
 */
#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct SpoofedProp {
    const char* key;
    const char* value;
};

/**
 * Compile-time fallback table.
 *
 * This is what the process reports when no per-clone identity has been installed. It is
 * deliberately kept so a guest that binds before the host has pushed an identity still gets
 * a coherent (if shared) device rather than the real one. The runtime table below takes
 * precedence as soon as it is populated.
 */
static SpoofedProp spoofed_props[] = {
        {"ro.product.model", "Pixel 6"},
        {"ro.product.brand", "google"},
        {"ro.product.manufacturer", "Google"},
        {"ro.product.device", "oriole"},
        {"ro.build.fingerprint", "google/oriole/oriole:12/SP1A.210812.015/7679548:user/release-keys"},
        {"ro.build.version.release", "12"},
        {"ro.build.version.security_patch", "2022-01-05"},
        {"ro.serialno", "1A2B3C4D5E6F"},
        {"ro.hardware", "qcom"},
        {"ro.boot.hardware", "qcom"},
        {"ro.product.board", "lahaina"},
        {"ro.product.cpu.abi", "arm64-v8a"},
        {"ro.build.type", "user"},
        {"ro.build.tags", "release-keys"},
        {"ro.kernel.qemu", "0"},
        {"ro.kernel.android.qemud", ""},
        {"ro.hardware.egl", "adreno"},
        {"ro.boot.qemu", "0"},
    {nullptr, nullptr} // Sentinel
};

/**
 * Per-process identity installed by the host at guest bind time.
 *
 * Guarded by g_spoof_lock. Reads copy the value out under the lock and release it before
 * returning, so the lock is never held across the fallback call into libc.
 */
static std::vector<std::pair<std::string, std::string>> g_runtime_props;
static std::mutex g_spoof_lock;

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;

/**
 * Copy [src] into the caller's property buffer.
 *
 * __system_property_get callers pass a buffer of PROP_VALUE_MAX (92) bytes. The original
 * code used an unbounded strcpy, so any spoofed value longer than 91 characters wrote past
 * the end of that buffer. Bound the copy and always NUL-terminate.
 */
static int copy_prop_value(const char *src, char *value) {
    if (value == nullptr) {
        return 0;
    }
    if (src == nullptr) {
        value[0] = '\0';
        return 0;
    }
    strncpy(value, src, PROP_VALUE_MAX - 1);
    value[PROP_VALUE_MAX - 1] = '\0';
    return static_cast<int>(strlen(value));
}

int my_system_property_get(const char *name, char *value) {
    if (name == nullptr) {
        return copy_prop_value("", value);
    }

    // Runtime table first: this is the per-clone identity.
    {
        std::lock_guard<std::mutex> guard(g_spoof_lock);
        for (const auto &entry : g_runtime_props) {
            if (entry.first == name) {
                // Copy while holding the lock, then release before logging or returning.
                int written = copy_prop_value(entry.second.c_str(), value);
                return written;
            }
        }
    }

    // Compile-time fallback.
    for (int i = 0; spoofed_props[i].key != nullptr; ++i) {
        if (strcmp(name, spoofed_props[i].key) == 0) {
            return copy_prop_value(spoofed_props[i].value, value);
        }
    }

    // Not spoofed: defer to the real implementation. The lock is not held here.
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

/**
 * Install the per-process identity.
 *
 * Called from the Java layer once a guest process knows which clone it is. Replaces any
 * previously installed table, so a process that is reused for a different clone does not
 * keep the old identity.
 */
static void set_spoofed_props(const std::vector<std::pair<std::string, std::string>> &props) {
    std::lock_guard<std::mutex> guard(g_spoof_lock);
    g_runtime_props = props;
}

extern "C" JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_core_NativeCore_setSpoofedProps(
        JNIEnv *env, jclass clazz, jobjectArray keys, jobjectArray values) {
    if (keys == nullptr || values == nullptr) {
        return;
    }
    jsize keyCount = env->GetArrayLength(keys);
    jsize valueCount = env->GetArrayLength(values);
    if (keyCount <= 0 || keyCount != valueCount) {
        LOGE("setSpoofedProps: mismatched arrays (%d keys, %d values)", keyCount, valueCount);
        return;
    }

    std::vector<std::pair<std::string, std::string>> props;
    props.reserve(static_cast<size_t>(keyCount));

    for (jsize i = 0; i < keyCount; ++i) {
        auto keyString = reinterpret_cast<jstring>(env->GetObjectArrayElement(keys, i));
        auto valueString = reinterpret_cast<jstring>(env->GetObjectArrayElement(values, i));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            if (keyString != nullptr) env->DeleteLocalRef(keyString);
            if (valueString != nullptr) env->DeleteLocalRef(valueString);
            LOGE("setSpoofedProps: exception reading entry %d", i);
            return;
        }
        if (keyString == nullptr || valueString == nullptr) {
            if (keyString != nullptr) env->DeleteLocalRef(keyString);
            if (valueString != nullptr) env->DeleteLocalRef(valueString);
            continue;
        }

        const char *keyChars = env->GetStringUTFChars(keyString, JNI_FALSE);
        const char *valueChars = env->GetStringUTFChars(valueString, JNI_FALSE);
        if (keyChars != nullptr && valueChars != nullptr) {
            props.emplace_back(std::string(keyChars), std::string(valueChars));
        }
        if (keyChars != nullptr) env->ReleaseStringUTFChars(keyString, keyChars);
        if (valueChars != nullptr) env->ReleaseStringUTFChars(valueString, valueChars);
        env->DeleteLocalRef(keyString);
        env->DeleteLocalRef(valueString);
    }

    set_spoofed_props(props);
    LOGD("setSpoofedProps: installed %zu properties", props.size());
}

void install_property_get_hook() {
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    void* target = xdl_dsym(handle, "__system_property_get", nullptr);
    if (target) {
        if (DobbyHook(target, (void*)my_system_property_get, (void**)&orig_system_property_get) == 0) {
            LOGD("Spoof installed successfully");
        } else {
            LOGD("Spoof hook failed");
        }
        xdl_close(handle);
    } else{
        xdl_close(handle);
    }

}

// Initialization function to ensure our hook is loaded
__attribute__((constructor)) void init_virtual_spoof()
{
    install_property_get_hook();
    LOGD("VirtualSpoof: __system_property_get hook loaded");
}
