#include <jni.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <sys/stat.h>
#include <unistd.h>
#include <signal.h>
#include <setjmp.h>
#include <android/log.h>
#include <vector>

#define TAG "JDex2 Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)



/**
 * 通过 write + pipe 检测地址是否可读，如果直接读取不可读内存APP会崩溃
 */
static bool isAddressReadable(const void* addr, size_t len) {
    if (addr == nullptr) return false;
    if ((uintptr_t)addr < 0x10000) return false;

    // 限制单次检查大小，pipe 缓冲区有限
    if (len > 4096) len = 4096;

    int fd[2];
    // fd[0]为读句柄，fd[1]为写句柄
    if (pipe(fd) != 0) return false;

    ssize_t ret = write(fd[1], addr, len);

    close(fd[0]);
    close(fd[1]);

    return ret == (ssize_t)len;
}
static bool isDexRegionReadable(const uint8_t* begin, uint32_t fileSize) {
    // 整个检测会爆缓冲区，只抽样检查
    if (!isAddressReadable(begin, 0x70)) return false;
    if (fileSize > 4) {
        if (!isAddressReadable(begin + fileSize - 4, 4)) return false;
    }
    if (fileSize > 0x1000) {
        if (!isAddressReadable(begin + fileSize / 2, 4)) return false;
    }

    return true;
}
static bool isDexMagic(const uint8_t* data) {
    // 只脱dex，抽取壳不会优化成cdex的
    return (data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n');
}

static uint32_t getDexFileSize(const uint8_t* begin) {
    return *reinterpret_cast<const uint32_t*>(begin + 0x20);
}

static const uint8_t* findBegin(jlong dexFilePtr) {

    // 不一定获取到的所有指针都是可读的，读之前都要先做验证

    if (!isAddressReadable((const void*)dexFilePtr, 64)) {
        LOGE("dexFilePtr 0x%llx is not readable", (unsigned long long)dexFilePtr);
        return nullptr;
    }

    for (int offset = 0; offset < 64; offset += sizeof(void*)) {
        const uint8_t* candidate = *((const uint8_t**)((uint8_t*)dexFilePtr + offset));

        if (candidate == nullptr) continue;

        if ((uintptr_t)candidate < 0x10000) {
            LOGE("offset %d: candidate 0x%llx too small, skip",
                 offset, (unsigned long long)candidate);
            continue;
        }

        if (!isAddressReadable(candidate, 0x70)) {
            LOGE("offset %d: candidate 0x%llx not readable, skip",
                 offset, (unsigned long long)candidate);
            continue;
        }

        if (isDexMagic(candidate)) {
            LOGE("Found begin_ at offset %d, addr=0x%llx",
                 offset, (unsigned long long)candidate);
            return candidate;
        }
    }

    const uint8_t* candidate = *((const uint8_t**)((uint8_t*)dexFilePtr + sizeof(long)));
    LOGE("Force Dump From: 0x%llx", candidate);
    if ((uintptr_t)candidate < 0x10000) {
        LOGE("offset %d: candidate 0x%llx too small, skip",
             sizeof(long), (unsigned long long)candidate);
        return nullptr;
    }

    if (!isAddressReadable(candidate, 0x70)) {
        LOGE("offset %d: candidate 0x%llx not readable, skip",
             sizeof(long), (unsigned long long)candidate);
        return nullptr;
    }
    return candidate;
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsnow_jdex2_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF("Hello from JDex!");
}
extern "C"
JNIEXPORT void JNICALL
Java_com_jsnow_jdex2_JSHook_dumpDexByCookie(JNIEnv *env, jclass clazz, jlongArray cookie,
                                            jstring outDir) {

    if (cookie == nullptr) return;

    const char* outDirStr = env->GetStringUTFChars(outDir, nullptr);
    jsize cookieLen = env->GetArrayLength(cookie);
    jlong* cookieElements = env->GetLongArrayElements(cookie, nullptr);

    mkdir(outDirStr, 0755);

    LOGE("cookie length = %d", cookieLen);

    // 遍历DexFile去Dump Dex
    // 头部第一个pointer其实一个隐藏的 vptr 指针，为求保险也可以读一下
    for (int i = 0; i < cookieLen; i++) {
        jlong dexFilePtr = cookieElements[i];
        if (dexFilePtr == 0) continue;

        LOGE("Processing cookie[%d] = 0x%llx", i, (unsigned long long)dexFilePtr);
        // 通过dex头判断会更保险一点，避免dump下来一大堆乱七八糟的东西，而且测试了一下大部分的壳都没有抹去dex头
        const uint8_t* begin = findBegin(dexFilePtr);
        if (begin == nullptr) {
            LOGE("cookie[%d]: cannot find valid dex data, skip", i);
            continue;
        }

        uint32_t fileSize = getDexFileSize(begin);
        LOGE("cookie[%d]: file_size = %u", i, fileSize);
        if (fileSize < 0x70 || fileSize > 100 * 1024 * 1024) {
            LOGE("cookie[%d]: unreasonable file_size=%u, skip", i, fileSize);
            continue;
        }

        if (!isDexRegionReadable(begin, fileSize)) {
            LOGE("cookie[%d]: dex region not readable, skip", i);
            continue;
        }


        uint32_t checksum = *reinterpret_cast<const uint32_t*>(begin + 0x08);
        char outPath[100];
        snprintf(outPath, sizeof(outPath), "%s%u.dex",
                 outDirStr, fileSize);

        FILE* check = fopen(outPath, "r");
        if (check != nullptr) {
            fclose(check);
            LOGE("Already dumped: %s, skip", outPath);
            continue;
        }

        FILE* fp = fopen(outPath, "wb");
        if (fp) {
            // 将Dump下来的Dex写入对应目录
            size_t written = 0;
            size_t chunkSize = 4096;
            while (written < fileSize) {
                size_t toWrite = fileSize - written;
                if (toWrite > chunkSize) toWrite = chunkSize;
                size_t ret = fwrite(begin + written, 1, toWrite, fp);
                if (ret != toWrite) {
                    LOGE("cookie[%d]: fwrite error at offset %zu", i, written);
                    break;
                }
                written += ret;
            }
            fclose(fp);
            LOGE("Dumped: %s (size=%u, written=%zu)", outPath, fileSize, written);
        } else {
            LOGE("Failed to write: %s", outPath);
        }
    }
    env->ReleaseLongArrayElements(cookie, cookieElements, 0);
    env->ReleaseStringUTFChars(outDir, outDirStr);



}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_jsnow_jdex2_JSHook_getDexSizesByCookie(JNIEnv *env, jclass clazz, jlongArray cookie) {

    if (cookie == nullptr) return nullptr;

    jsize cookieLen = env->GetArrayLength(cookie);
    if (cookieLen < 1) return nullptr;

    jlong* cookieElements = env->GetLongArrayElements(cookie, nullptr);
    if (cookieElements == nullptr) return nullptr;

    // 收集所有有效的 fileSize
    std::vector<jlong> sizes;

    for (int i = 0; i < cookieLen; i++) {
        jlong dexFilePtr = cookieElements[i];
        if (dexFilePtr == 0) continue;

        const uint8_t* begin = findBegin(dexFilePtr);
        if (begin == nullptr) continue;

        uint32_t fileSize = getDexFileSize(begin);

        if (fileSize < 0x70 || fileSize > 100 * 1024 * 1024) {
            LOGE("getDexSizes: cookie[%d] unreasonable file_size=%u, skip", i, fileSize);
            continue;
        }

        if (!isDexRegionReadable(begin, fileSize)) {
            LOGE("getDexSizes: cookie[%d] dex region not readable, skip", i);
            continue;
        }

        sizes.push_back((jlong)fileSize);
    }

    env->ReleaseLongArrayElements(cookie, cookieElements, 0);

    // 构造 jlongArray 返回
    jlongArray result = env->NewLongArray((jsize)sizes.size());
    if (result != nullptr && !sizes.empty()) {
        env->SetLongArrayRegion(result, 0, (jsize)sizes.size(), sizes.data());
    }

    LOGE("getDexSizes: found %zu valid dex files", sizes.size());
    return result;
}