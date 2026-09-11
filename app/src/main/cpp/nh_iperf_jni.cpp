#include <jni.h>
#include <string>
#include <android/log.h>
#include <setjmp.h>
#include <stdint.h>
#include <mutex>
#include <atomic>
#include <thread>
#include <fcntl.h>

#include <sys/socket.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <memory>

extern "C" {
#include "iperf_api.h"
#include "iperf.h"
}

#define TAG "NH-IperfJNI"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------
// iperf 全局依赖：env / i_errno
// ---------------------------
extern "C" jmp_buf env = {};
extern "C" int i_errno;
extern "C" char *iperf_strerror(int);

static std::mutex g_singleton_mu;
static std::atomic<bool> g_busy{false};

// ---------------------------
// JavaVM：用于子线程回调
// ---------------------------
static void ensure_stdio_open();

static JavaVM* g_vm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    signal(SIGPIPE, SIG_IGN);

    ensure_stdio_open();

    return JNI_VERSION_1_6;
}


// ---------------------------
// global运行状态：用于 stop()
// ---------------------------
static std::mutex g_mu;
static iperf_test* g_running_test = nullptr;

static void set_running_test(iperf_test* t) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_running_test = t;
}
static iperf_test* get_running_test_snapshot() {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_running_test;
}

// ---------------------------
// 工具：最小 JSON 转义
// ---------------------------
static std::string json_escape(const char* s) {
    if (!s) return "";
    std::string out;
    for (const char* p = s; *p; ++p) {
        const char c = *p;
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"':  out += "\\\""; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;      break;
        }
    }
    return out;
}

static jstring json_error(JNIEnv* e, const char* msg) {
    std::string m = json_escape(msg);
    std::string s = std::string("{\"error\":true,\"message\":\"") + m + "\"}";
    return e->NewStringUTF(s.c_str());
}

static void ensure_stdio_open() {
    // 确保 0/1/2 永远有效，避免 socket 复用到 1/2 然后被误 close -> EBADF
    for (int fd = 0; fd <= 2; fd++) {
        errno = 0;
        if (fcntl(fd, F_GETFD) == -1 && errno == EBADF) {
            int devnull = open("/dev/null", O_RDWR);
            if (devnull >= 0) {
                dup2(devnull, fd);
                if (devnull > 2) close(devnull);
            }
        }
    }
}


// RAII：确保 host 释放
struct JStringUtf {
    JNIEnv* env;
    jstring js;
    const char* cstr;
    explicit JStringUtf(JNIEnv* e, jstring s) : env(e), js(s), cstr(nullptr) {
        if (js) cstr = env->GetStringUTFChars(js, nullptr);
    }
    ~JStringUtf() {
        if (js && cstr) env->ReleaseStringUTFChars(js, cstr);
    }
    const char* get() const { return cstr ? cstr : ""; }
};

// ---------------------------
// 回调：cb.onLine(String)
// ---------------------------
static void callback_line(jobject cbGlobal, const std::string& line) {
    if (!g_vm || !cbGlobal) return;

    JNIEnv* envLocal = nullptr;
    bool didAttach = false;

    if (g_vm->GetEnv((void**)&envLocal, JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&envLocal, nullptr) != JNI_OK) return;
        didAttach = true;
    }

    jclass cbCls = envLocal->GetObjectClass(cbGlobal);
    if (cbCls) {
        jmethodID mid = envLocal->GetMethodID(cbCls, "onLine", "(Ljava/lang/String;)V");
        if (mid) {
            jstring jline = envLocal->NewStringUTF(line.c_str());
            envLocal->CallVoidMethod(cbGlobal, mid, jline);
            envLocal->DeleteLocalRef(jline);
        }
        envLocal->DeleteLocalRef(cbCls);
    }

    if (didAttach) g_vm->DetachCurrentThread();
}

// ---------------------------
// 通用：配置 test
// ---------------------------
static bool config_test(
        iperf_test* test,
        const char* host,
        int port,
        int seconds,
        int parallel,
        bool reverse,
        bool udp,
        long long udpBitrateBps,
        bool jsonStream
) {
    if (iperf_defaults(test) != 0) {
        const char* err = iperf_strerror(i_errno);
        ALOGE("iperf_defaults failed i_errno=%d err=%s", i_errno, err ? err : "null");
        return false;
    }

    iperf_set_test_role(test, 'c');
    iperf_set_test_server_hostname(test, host);
    iperf_set_test_server_port(test, port);
    iperf_set_test_duration(test, seconds);
    iperf_set_test_num_streams(test, parallel);
    iperf_set_test_reverse(test, reverse ? 1 : 0);

    iperf_set_test_json_output(test, 1);
    iperf_set_test_json_stream(test, jsonStream ? 1 : 0);

    if (udp) {
        if (set_protocol(test, Pudp) != 0) {
            const char* err = iperf_strerror(i_errno);
            ALOGE("set_protocol(Pudp) failed i_errno=%d err=%s", i_errno, err ? err : "null");
            return false;
        }
        if (udpBitrateBps > 0) {
            iperf_set_test_rate(test, (uint64_t)udpBitrateBps);
        }
    } else {
        (void)set_protocol(test, Ptcp);
    }

    return true;
}

// ---------------------------
// runJson（非流式）
// ---------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_nvmex_networkhelper_iperf_IperfNative_runJson(
        JNIEnv* e,
        jclass,
        jstring host_,
        jint port,
        jint seconds,
        jint parallel,
        jboolean reverse,
        jboolean udp,
        jlong udpBitrateBps
) {
    JStringUtf host(e, host_);
    if (g_busy.exchange(true)) {
        return json_error(e, "iperf is already running");
    }
    auto busyGuard = std::unique_ptr<void, void(*)(void*)>(
            (void*)1, [](void*){ g_busy.store(false); }
    );

    if (!host_ || std::string(host.get()).empty()) return json_error(e, "host is empty");
    if (port <= 0 || port > 65535) return json_error(e, "port out of range");
    if (seconds <= 0) seconds = 10;
    if (parallel <= 0) parallel = 1;
    ensure_stdio_open();

    iperf_test* test = iperf_new_test();
    if (!test) return json_error(e, "iperf_new_test failed");

    set_running_test(test);

    if (setjmp(env) != 0) {
        const char* err = iperf_strerror(i_errno);
        ALOGE("iperf fatal exit i_errno=%d err=%s", i_errno, err ? err : "null");
        iperf_free_test(test);
        set_running_test(nullptr);
        return json_error(e, err ? err : "iperf fatal exit");
    }

    if (!config_test(
            test, host.get(), (int)port,
            (int)seconds, (int)parallel,
            reverse != 0, udp != 0,
            (long long)udpBitrateBps,
            false
    )) {
        const char* err = iperf_strerror(i_errno);
        iperf_free_test(test);
        set_running_test(nullptr);
        return json_error(e, err ? err : "config_test failed");
    }

    int rc = iperf_run_client(test);
    if (rc != 0) {
        const char* err = iperf_strerror(i_errno);
        ALOGE("iperf_run_client failed rc=%d i_errno=%d err=%s", rc, i_errno, err ? err : "null");
        iperf_free_test(test);
        set_running_test(nullptr);
        return json_error(e, err ? err : "iperf_run_client failed");
    }

    char* json = iperf_get_test_json_output_string(test);
    jstring out = e->NewStringUTF(json ? json : "{}");

    iperf_free_test(test);
    set_running_test(nullptr);
    return out;
}

// ---------------------------
// runJsonStream（流式 NDJSON 回调）
// 关键修复：不要在成功收尾时 readerStop + close(pipeRead) 把尾巴掐掉
// ---------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_nvmex_networkhelper_iperf_IperfNative_runJsonStream(
        JNIEnv* e,
        jclass,
        jstring host_,
        jint port,
        jint seconds,
        jint parallel,
        jboolean reverse,
        jboolean udp,
        jlong udpBitrateBps,
        jobject cb
) {
    if (g_busy.exchange(true)) {
        return json_error(e, "iperf is already running");
    }
    auto busyGuard = std::unique_ptr<void, void(*)(void*)>(
            (void*)1, [](void*){ g_busy.store(false); }
    );

    if (!cb) return json_error(e, "callback is null");
    ensure_stdio_open();
    JStringUtf host(e, host_);
    if (!host_ || std::string(host.get()).empty()) return json_error(e, "host is empty");
    if (port <= 0 || port > 65535) return json_error(e, "port out of range");
    if (seconds <= 0) seconds = 10;
    if (parallel <= 0) parallel = 1;

    jobject cbGlobal = e->NewGlobalRef(cb);
    if (!cbGlobal) return json_error(e, "NewGlobalRef(callback) failed");

    int fds[2] = {-1, -1};
    if (pipe(fds) != 0) {
        e->DeleteGlobalRef(cbGlobal);
        return json_error(e, "pipe() failed");
    }
    int pipeRead = fds[0];
    int pipeWrite = fds[1];

    int savedStdout = dup(STDOUT_FILENO);
    if (savedStdout < 0) {
        close(pipeRead); close(pipeWrite);
        e->DeleteGlobalRef(cbGlobal);
        return json_error(e, "dup(STDOUT) failed");
    }

    // stdout -> pipeWrite
    if (dup2(pipeWrite, STDOUT_FILENO) < 0) {
        close(savedStdout);
        close(pipeRead); close(pipeWrite);
        e->DeleteGlobalRef(cbGlobal);
        return json_error(e, "dup2(pipeWrite, STDOUT) failed");
    }
    close(pipeWrite);
    pipeWrite = -1;

    // ✅ readerStop 只用于“异常/提前失败”时让线程尽快退出
    std::atomic<bool> readerStop{false};

    std::thread reader([&]() {
        std::string buf;
        buf.reserve(8192);

        char tmp[2048];
        while (!readerStop.load(std::memory_order_relaxed)) {
            ssize_t n = read(pipeRead, tmp, sizeof(tmp));
            if (n > 0) {
                buf.append(tmp, tmp + n);

                while (true) {
                    size_t nl = buf.find('\n');
                    if (nl == std::string::npos) break;
                    std::string line = buf.substr(0, nl);
                    buf.erase(0, nl + 1);
                    if (!line.empty()) callback_line(cbGlobal, line);
                }
            } else if (n == 0) {
                // ✅ EOF：说明 stdout 已恢复/写端关闭，正常结束
                break;
            } else {
                if (errno == EINTR) continue;
                break;
            }
        }

        if (!buf.empty()) callback_line(cbGlobal, buf);
    });

    auto cleanup_stream = [&]() {
        if (savedStdout >= 0) {
            if (dup2(savedStdout, STDOUT_FILENO) < 0) {
                ALOGE("restore STDOUT failed errno=%d", errno);
            }
            close(savedStdout);
            savedStdout = -1;
        }

        if (reader.joinable()) reader.join();
        if (pipeRead >= 0) { close(pipeRead); pipeRead = -1; }
        e->DeleteGlobalRef(cbGlobal);
    };





    iperf_test* test = iperf_new_test();
    if (!test) {
        // 失败：让 reader 尽快退出
        readerStop.store(true);
        // 先恢复 stdout，让 reader EOF
        cleanup_stream();

        return json_error(e, "iperf_new_test failed");
    }

    set_running_test(test);

    if (setjmp(env) != 0) {
        const char* err = iperf_strerror(i_errno);
        ALOGE("iperf fatal exit i_errno=%d err=%s", i_errno, err ? err : "null");

        iperf_free_test(test);
        set_running_test(nullptr);

        // 异常：让 reader 退出 + 恢复 stdout
        readerStop.store(true);
        cleanup_stream();


        return json_error(e, err ? err : "iperf fatal exit");
    }

    if (!config_test(
            test, host.get(), (int)port,
            (int)seconds, (int)parallel,
            reverse != 0, udp != 0,
            (long long)udpBitrateBps,
            true /* jsonStream */
    )) {
        const char* err = iperf_strerror(i_errno);

        iperf_free_test(test);
        set_running_test(nullptr);

        readerStop.store(true);
        cleanup_stream();


        return json_error(e, err ? err : "config_test failed");
    }

    ALOGI("start client (runJsonStream) host=%s port=%d t=%d P=%d R=%d udp=%d bps=%lld",
          host.get(), (int)port, (int)seconds, (int)parallel,
          (int)(reverse ? 1 : 0), (int)(udp ? 1 : 0),
          (long long)udpBitrateBps);

    int rc = iperf_run_client(test);

    // 注意：stream 模式下 iperf_get_test_json_output_string 可能是 {}
    char* json = iperf_get_test_json_output_string(test);
    jstring out = e->NewStringUTF(json ? json : "{}");

    iperf_free_test(test);
    set_running_test(nullptr);

    if (rc != 0) {
        const char* err = iperf_strerror(i_errno);
        ALOGE("iperf_run_client failed rc=%d i_errno=%d err=%s", rc, i_errno, err ? err : "null");

        // rc!=0：依然要先 restore stdout 让 reader drain
        readerStop.store(true); // 失败时允许它尽快停
        cleanup_stream();

        return json_error(e, err ? err : "iperf_run_client failed");
    }

    // ✅成功路径：关键修复点
    // 1) 先恢复 stdout（关闭写端）-> reader 会读到 EOF
    // 2) 不要 readerStop，不要 close(pipeRead)，让 reader 把尾巴读完再 join
    cleanup_stream();


    return out;
}

// ---------------------------
// runServerStream（服务端流式 NDJSON 回调）
// ---------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_nvmex_networkhelper_iperf_IperfNative_runServerStream(
        JNIEnv* e,
        jclass,
        jint port,
        jobject /*cb*/
) {
    if (g_busy.exchange(true)) {
        return json_error(e, "iperf is already running");
    }
    auto busyGuard = std::unique_ptr<void, void(*)(void*)>(
            (void*)1, [](void*){ g_busy.store(false); }
    );

    if (port <= 0 || port > 65535) return json_error(e, "port out of range");
    ensure_stdio_open();
    // 防 SIGPIPE
    signal(SIGPIPE, SIG_IGN);

    iperf_test* test = iperf_new_test();
    if (!test) return json_error(e, "iperf_new_test failed");

    set_running_test(test);

    if (setjmp(env) != 0) {
        const char* err = iperf_strerror(i_errno);
        ALOGE("iperf fatal exit (server) i_errno=%d err=%s", i_errno, err ? err : "null");

        iperf_free_test(test);
        set_running_test(nullptr);
        return json_error(e, err ? err : "iperf fatal exit");
    }

    if (iperf_defaults(test) != 0) {
        const char* err = iperf_strerror(i_errno);
        iperf_free_test(test);
        set_running_test(nullptr);
        return json_error(e, err ? err : "iperf_defaults failed");
    }

    // server role
    iperf_set_test_role(test, 's');
    iperf_set_test_server_port(test, (int)port);

    // ✅ 先不要 json_stream，不要 hook stdout/stderr
    iperf_set_test_json_output(test, 1);
    iperf_set_test_json_stream(test, 0);

    // ✅ 打开 verbose，方便从 logcat 看更具体原因
    // 如果编译不过，就删掉这行
//    iperf_set_verbose(test, 1);

    ALOGI("start server (minimal) port=%d", (int)port);
    ALOGI("server about to run: port=%d", (int)port);

    int rc = iperf_run_server(test);

    char* json = iperf_get_test_json_output_string(test);
    jstring out = e->NewStringUTF(json ? json : "{}");

    if (rc != 0) {
        const char* err = iperf_strerror(i_errno);
        ALOGE("iperf_run_server failed rc=%d i_errno=%d err=%s", rc, i_errno, err ? err : "null");
        iperf_free_test(test);
        set_running_test(nullptr);
        return json_error(e, err ? err : "iperf_run_server failed");
    }

    iperf_free_test(test);
    set_running_test(nullptr);
    return out;
}



// ---------------------------
// stop：best-effort，不阻塞
// ---------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_nvmex_networkhelper_iperf_IperfNative_stop(JNIEnv*, jclass) {
    iperf_test* t = get_running_test_snapshot();
    if (!t) {
        ALOGI("stop(): no running test");
        return;
    }

    int fd = iperf_get_control_socket(t);
    if (fd > 0) {
        shutdown(fd, SHUT_RDWR);
        ALOGI("stop(): shutdown control socket fd=%d", fd);
    } else {
        ALOGI("stop(): no control socket yet");
    }
}
