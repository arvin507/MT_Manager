// 只放热点：大文件拷贝、字节搜索、DEX 扫描。其余逻辑留在 Kotlin，便于维护。
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/sendfile.h>
#include <errno.h>
#include <cstring>
#include <cstdlib>
#include <string>

extern "C" {

/* ---------- DEX 扫描 ---------- */

namespace {

struct Dex {
    const uint8_t *data;
    jint len;
};

uint32_t rd32(const Dex &d, uint32_t off) {
    if (off == 0 || off + 4 > static_cast<uint32_t>(d.len)) return 0;
    return static_cast<uint32_t>(d.data[off]) |
           (static_cast<uint32_t>(d.data[off + 1]) << 8) |
           (static_cast<uint32_t>(d.data[off + 2]) << 16) |
           (static_cast<uint32_t>(d.data[off + 3]) << 24);
}

// 读 uleb128，失败返回 false。
bool uleb(const Dex &d, uint32_t &pos, uint32_t &value) {
    value = 0;
    int shift = 0;
    while (pos < static_cast<uint32_t>(d.len)) {
        uint8_t b = d.data[pos++];
        value |= static_cast<uint32_t>(b & 0x7F) << shift;
        if ((b & 0x80) == 0) return true;
        shift += 7;
        if (shift > 28) return false;
    }
    return false;
}

// 把 type_ids[typeIdx] 指向的描述符（MUTF-8，到 NUL 为止）追加到 out。
bool appendType(const Dex &d, uint32_t stringIdsOff, uint32_t stringIdsSize,
                uint32_t typeIdsOff, uint32_t typeIdsSize, uint32_t typeIdx,
                std::string &out) {
    if (typeIdx >= typeIdsSize) { out += "-"; return true; }
    uint32_t strIdx = rd32(d, typeIdsOff + typeIdx * 4);
    if (strIdx >= stringIdsSize) { out += "-"; return true; }
    uint32_t dataOff = rd32(d, stringIdsOff + strIdx * 4);
    if (dataOff == 0 || dataOff >= static_cast<uint32_t>(d.len)) { out += "-"; return true; }
    uint32_t value;
    if (!uleb(d, dataOff, value)) return false;  // 跳过 uleb 长度头
    while (dataOff < static_cast<uint32_t>(d.len) && d.data[dataOff] != 0) {
        out += static_cast<char>(d.data[dataOff++]);
    }
    return true;
}

}  // namespace

/**
 * 批量扫描 DEX：一次 JNI 调用产出全部类表，避免在 Java 侧分配数万个对象。
 * 返回制表符分隔文本；首行 "#<strings>\t<types>\t<methodIds>\t<classDefs>\t<totalMethods>"，
 * 之后每类一行 "<descriptor>\t<super|->\t<methods>\t<fields>"。
 * 解析失败返回 null，调用方回退纯 Kotlin 实现。
 */
JNIEXPORT jstring JNICALL
Java_dev_fileforge_ndk_NativeBridge_scanDex(JNIEnv *env, jobject /*thiz*/,
                                            jbyteArray jDex) {
    jsize len = env->GetArrayLength(jDex);
    if (len < 112) return nullptr;

    jbyte *raw = env->GetByteArrayElements(jDex, nullptr);
    if (raw == nullptr) return nullptr;
    // 统一在退出点释放
    struct Guard {
        JNIEnv *env;
        jbyteArray arr;
        jbyte *ptr;
        ~Guard() { env->ReleaseByteArrayElements(arr, ptr, JNI_ABORT); }
    } guard{env, jDex, raw};

    Dex d{reinterpret_cast<const uint8_t *>(raw), len};
    if (memcmp(d.data, "dex\n", 4) != 0) return nullptr;

    uint32_t stringIdsSize = rd32(d, 56), stringIdsOff = rd32(d, 60);
    uint32_t typeIdsSize = rd32(d, 64), typeIdsOff = rd32(d, 68);
    uint32_t methodIdsSize = rd32(d, 88);
    uint32_t classDefsSize = rd32(d, 96), classDefsOff = rd32(d, 100);

    std::string head, body;
    head.reserve(64);
    body.reserve(static_cast<size_t>(classDefsSize) * 48);

    head = "#";
    head += std::to_string(stringIdsSize);
    head += '\t'; head += std::to_string(typeIdsSize);
    head += '\t'; head += std::to_string(methodIdsSize);
    head += '\t'; head += std::to_string(classDefsSize);
    head += '\t';

    uint64_t totalMethods = 0;
    for (uint32_t i = 0; i < classDefsSize; i++) {
        uint32_t base = classDefsOff + i * 32;
        if (base + 32 > static_cast<uint32_t>(d.len)) return nullptr;
        uint32_t classIdx = rd32(d, base);
        uint32_t superIdx = rd32(d, base + 8);
        uint32_t classDataOff = rd32(d, base + 24);

        if (!appendType(d, stringIdsOff, stringIdsSize, typeIdsOff, typeIdsSize, classIdx, body))
            return nullptr;
        body += '\t';
        if (superIdx == 0xFFFFFFFFu) body += '-';
        else if (!appendType(d, stringIdsOff, stringIdsSize, typeIdsOff, typeIdsSize, superIdx, body))
            return nullptr;
        body += '\t';

        uint32_t methods = 0, fields = 0;
        if (classDataOff != 0 && classDataOff < static_cast<uint32_t>(d.len)) {
            uint32_t pos = classDataOff, v;
            bool ok = uleb(d, pos, v); fields += v;
            ok = ok && uleb(d, pos, v); fields += v;
            ok = ok && uleb(d, pos, v); methods += v;
            ok = ok && uleb(d, pos, v); methods += v;
            if (!ok) return nullptr;
            // 字段项 2 个 uleb，方法项 3 个 uleb，全部跳过
            for (uint32_t k = 0; k < fields && ok; k++) {
                ok = uleb(d, pos, v) && uleb(d, pos, v);
            }
            for (uint32_t k = 0; k < methods && ok; k++) {
                ok = uleb(d, pos, v) && uleb(d, pos, v) && uleb(d, pos, v);
            }
            if (!ok) return nullptr;
        }

        body += std::to_string(methods);
        body += '\t';
        body += std::to_string(fields);
        body += '\n';
        totalMethods += methods;
    }

    head += std::to_string(totalMethods);
    head += '\n';

    return env->NewStringUTF((head + body).c_str());
}

/**
 * 用 sendfile 走内核零拷贝拷贝文件，返回拷贝字节数；失败返回负的 errno。
 */
JNIEXPORT jlong JNICALL
Java_dev_fileforge_ndk_NativeBridge_copyFile(JNIEnv *env, jobject /*thiz*/,
                                             jstring jSrc, jstring jDst) {
    const char *src = env->GetStringUTFChars(jSrc, nullptr);
    const char *dst = env->GetStringUTFChars(jDst, nullptr);
    if (src == nullptr || dst == nullptr) {
        if (src) env->ReleaseStringUTFChars(jSrc, src);
        if (dst) env->ReleaseStringUTFChars(jDst, dst);
        return -ENOMEM;
    }

    int in = open(src, O_RDONLY);
    if (in < 0) {
        jlong err = -errno;
        env->ReleaseStringUTFChars(jSrc, src);
        env->ReleaseStringUTFChars(jDst, dst);
        return err;
    }

    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0) {
        jlong err = -errno;
        close(in);
        env->ReleaseStringUTFChars(jSrc, src);
        env->ReleaseStringUTFChars(jDst, dst);
        return err;
    }

    struct stat st {};
    jlong total = 0;
    if (fstat(in, &st) == 0) {
        off_t offset = 0;
        off_t remaining = st.st_size;
        while (remaining > 0) {
            ssize_t written = sendfile(out, in, &offset, static_cast<size_t>(remaining));
            if (written <= 0) {
                if (errno == EINTR) continue;
                break;
            }
            remaining -= written;
            total += written;
        }
    }

    close(out);
    close(in);
    env->ReleaseStringUTFChars(jSrc, src);
    env->ReleaseStringUTFChars(jDst, dst);
    return total;
}

/**
 * 字节串查找（memchr 跳跃），返回首次出现下标，未找到返回 -1。
 */
JNIEXPORT jint JNICALL
Java_dev_fileforge_ndk_NativeBridge_indexOf(JNIEnv *env, jobject /*thiz*/,
                                            jbyteArray jHaystack, jbyteArray jNeedle) {
    jsize hayLen = env->GetArrayLength(jHaystack);
    jsize needleLen = env->GetArrayLength(jNeedle);
    if (needleLen <= 0 || hayLen < needleLen) return -1;

    jbyte *hay = env->GetByteArrayElements(jHaystack, nullptr);
    jbyte *needle = env->GetByteArrayElements(jNeedle, nullptr);
    if (hay == nullptr || needle == nullptr) {
        if (hay) env->ReleaseByteArrayElements(jHaystack, hay, JNI_ABORT);
        if (needle) env->ReleaseByteArrayElements(jNeedle, needle, JNI_ABORT);
        return -1;
    }

    jint result = -1;
    const char *base = reinterpret_cast<const char *>(hay);
    const char first = reinterpret_cast<const char *>(needle)[0];
    jint limit = hayLen - needleLen;

    jint i = 0;
    while (i <= limit) {
        const char *hit = static_cast<const char *>(
            memchr(base + i, first, static_cast<size_t>(limit - i + 1)));
        if (hit == nullptr) break;
        jint pos = static_cast<jint>(hit - base);
        if (memcmp(hit, needle, static_cast<size_t>(needleLen)) == 0) {
            result = pos;
            break;
        }
        i = pos + 1;
    }

    env->ReleaseByteArrayElements(jHaystack, hay, JNI_ABORT);
    env->ReleaseByteArrayElements(jNeedle, needle, JNI_ABORT);
    return result;
}

}
