#include "audio_flush_diag.h"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define WINDOW_NS (3000000000LL) /* 3s after FLUSH */
#define NULL_SUMMARY_NS (250000000LL)
#define BATCH_CAP (48 * 1024)
#define BATCH_FLUSH_NS (300000000LL) /* ~300ms */
#define LINE_MAX 512

static atomic_llong g_flush_ns;
static atomic_int g_next_seq;
static atomic_int g_null_deq;
static atomic_int g_ok_deq;
static atomic_int g_first_ok_logged;
static atomic_int g_pcm_logged;
static atomic_int g_audible_logged;
static atomic_llong g_last_null_summary_ns;

static pthread_mutex_t g_batch_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_batch_cv = PTHREAD_COND_INITIALIZER;
static char g_batch[BATCH_CAP];
static size_t g_batch_len;
static int64_t g_batch_first_ns;
static atomic_int g_ship_running;
static pthread_t g_ship_thread;
static atomic_int g_ship_started;

static JavaVM *g_vm;
static jclass g_shipper_cls; /* global ref */
static jmethodID g_enqueue_mid;

static int64_t mono_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

static int window_active(int64_t now) {
    int64_t t = atomic_load_explicit(&g_flush_ns, memory_order_acquire);
    return t != 0 && (now - t) >= 0 && (now - t) < WINDOW_NS;
}

int audio_flush_diag_active(void) {
    return window_active(mono_ns());
}

int audio_flush_diag_ms_since_flush(void) {
    int64_t now = mono_ns();
    int64_t t = atomic_load_explicit(&g_flush_ns, memory_order_acquire);
    if (t == 0 || !window_active(now)) return -1;
    return (int)((now - t) / 1000000LL);
}

static void batch_append_locked(const char *line) {
    size_t n = strlen(line);
    if (n == 0) return;
    size_t need = n + (g_batch_len ? 1 : 0);
    if (g_batch_len + need >= BATCH_CAP) {
        /* leave room; shipper will take current batch */
        pthread_cond_signal(&g_batch_cv);
        return;
    }
    if (g_batch_len == 0) {
        g_batch_first_ns = mono_ns();
    } else {
        g_batch[g_batch_len++] = '\n';
    }
    memcpy(g_batch + g_batch_len, line, n);
    g_batch_len += n;
    g_batch[g_batch_len] = '\0';
    if (g_batch_len >= BATCH_CAP / 2) {
        pthread_cond_signal(&g_batch_cv);
    }
}

static void jni_enqueue_batch(const char *text, size_t len) {
    if (!g_vm || !g_shipper_cls || !g_enqueue_mid || len == 0) return;

    JNIEnv *env = NULL;
    int attached = 0;
    int rc = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return;
        attached = 1;
    } else if (rc != JNI_OK || !env) {
        return;
    }

    jstring jtext = (*env)->NewStringUTF(env, text);
    if (jtext) {
        (*env)->CallStaticVoidMethod(env, g_shipper_cls, g_enqueue_mid, jtext);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, jtext);
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static void *shipper_main(void *arg) {
    (void)arg;
    while (atomic_load_explicit(&g_ship_running, memory_order_acquire)) {
        char local[BATCH_CAP];
        size_t local_len = 0;

        pthread_mutex_lock(&g_batch_mu);
        while (atomic_load_explicit(&g_ship_running, memory_order_relaxed) && g_batch_len == 0) {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 300000000L;
            if (ts.tv_nsec >= 1000000000L) {
                ts.tv_sec += 1;
                ts.tv_nsec -= 1000000000L;
            }
            pthread_cond_timedwait(&g_batch_cv, &g_batch_mu, &ts);
            if (g_batch_len > 0) break;
        }
        if (g_batch_len > 0) {
            int64_t age = mono_ns() - g_batch_first_ns;
            /* Wait a bit to coalesce lines unless batch is large or shutting down */
            if (atomic_load_explicit(&g_ship_running, memory_order_relaxed) &&
                g_batch_len < BATCH_CAP / 2 && age < BATCH_FLUSH_NS) {
                struct timespec ts;
                clock_gettime(CLOCK_REALTIME, &ts);
                int64_t wait_ns = BATCH_FLUSH_NS - age;
                ts.tv_sec += (time_t)(wait_ns / 1000000000LL);
                ts.tv_nsec += (long)(wait_ns % 1000000000LL);
                if (ts.tv_nsec >= 1000000000L) {
                    ts.tv_sec += 1;
                    ts.tv_nsec -= 1000000000L;
                }
                pthread_cond_timedwait(&g_batch_cv, &g_batch_mu, &ts);
            }
            local_len = g_batch_len;
            memcpy(local, g_batch, local_len);
            local[local_len] = '\0';
            g_batch_len = 0;
            g_batch[0] = '\0';
        }
        pthread_mutex_unlock(&g_batch_mu);

        if (local_len > 0) {
            jni_enqueue_batch(local, local_len);
        }
    }

    /* Final drain */
    pthread_mutex_lock(&g_batch_mu);
    if (g_batch_len > 0) {
        char local[BATCH_CAP];
        size_t local_len = g_batch_len;
        memcpy(local, g_batch, local_len);
        local[local_len] = '\0';
        g_batch_len = 0;
        pthread_mutex_unlock(&g_batch_mu);
        jni_enqueue_batch(local, local_len);
    } else {
        pthread_mutex_unlock(&g_batch_mu);
    }
    return NULL;
}

static void ensure_shipper_thread(void) {
    if (atomic_exchange_explicit(&g_ship_started, 1, memory_order_acq_rel)) return;
    atomic_store_explicit(&g_ship_running, 1, memory_order_release);
    if (pthread_create(&g_ship_thread, NULL, shipper_main, NULL) != 0) {
        atomic_store_explicit(&g_ship_running, 0, memory_order_relaxed);
        atomic_store_explicit(&g_ship_started, 0, memory_order_relaxed);
    }
}

void audio_flush_diag_jni_init(void *jni_env) {
    JNIEnv *env = (JNIEnv *)jni_env;
    if (!env) return;
    (*env)->GetJavaVM(env, &g_vm);
    jclass local = (*env)->FindClass(env, "com/flymop/airplaytv/diag/DiagLogShipper");
    if (!local) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        __android_log_print(ANDROID_LOG_WARN, AIRPLAY_AUDIO_TAG,
                            "DiagLogShipper class not found; remote ship disabled");
        return;
    }
    g_shipper_cls = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    g_enqueue_mid = (*env)->GetStaticMethodID(env, g_shipper_cls, "enqueueBatch",
                                              "(Ljava/lang/String;)V");
    if (!g_enqueue_mid) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        __android_log_print(ANDROID_LOG_WARN, AIRPLAY_AUDIO_TAG,
                            "DiagLogShipper.enqueueBatch missing; remote ship disabled");
        (*env)->DeleteGlobalRef(env, g_shipper_cls);
        g_shipper_cls = NULL;
        return;
    }
    ensure_shipper_thread();
}

void audio_flush_diag_jni_shutdown(void *jni_env) {
    JNIEnv *env = (JNIEnv *)jni_env;
    if (atomic_exchange_explicit(&g_ship_started, 0, memory_order_acq_rel)) {
        atomic_store_explicit(&g_ship_running, 0, memory_order_release);
        pthread_mutex_lock(&g_batch_mu);
        pthread_cond_broadcast(&g_batch_cv);
        pthread_mutex_unlock(&g_batch_mu);
        pthread_join(g_ship_thread, NULL);
    }
    if (env && g_shipper_cls) {
        (*env)->DeleteGlobalRef(env, g_shipper_cls);
        g_shipper_cls = NULL;
    }
    g_enqueue_mid = NULL;
    g_vm = NULL;
}

void audio_flush_diag_emit(const char *fmt, ...) {
    char line[LINE_MAX];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);

    __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG, "%s", line);

    ensure_shipper_thread();
    pthread_mutex_lock(&g_batch_mu);
    batch_append_locked(line);
    /* Flush sooner for milestone lines */
    if (strstr(line, "FLUSH") || strstr(line, "FIRST_OK") || strstr(line, "AUDIBLE") ||
        strstr(line, "AUTO_FLUSH") || strstr(line, "FIRST_WRITE")) {
        pthread_cond_signal(&g_batch_cv);
    }
    pthread_mutex_unlock(&g_batch_mu);
}

void audio_flush_diag_rtsp_flush(const char *rtpinfo, int next_seq) {
    audio_flush_diag_emit("RTSP_FLUSH RTP-Info=%s next_seq=%d",
                          rtpinfo && rtpinfo[0] ? rtpinfo : "(none)", next_seq);
}

void audio_flush_diag_on_flush(int next_seq,
                               int buffer_flushed,
                               int was_empty,
                               unsigned first_seq_before,
                               unsigned last_seq_before,
                               int entry_count_before,
                               int hole_before,
                               int is_empty_after,
                               unsigned first_seq_after) {
    int64_t now = mono_ns();
    atomic_store_explicit(&g_flush_ns, now, memory_order_release);
    atomic_store_explicit(&g_next_seq, next_seq, memory_order_relaxed);
    atomic_store_explicit(&g_null_deq, 0, memory_order_relaxed);
    atomic_store_explicit(&g_ok_deq, 0, memory_order_relaxed);
    atomic_store_explicit(&g_first_ok_logged, 0, memory_order_relaxed);
    atomic_store_explicit(&g_pcm_logged, 0, memory_order_relaxed);
    atomic_store_explicit(&g_audible_logged, 0, memory_order_relaxed);
    atomic_store_explicit(&g_last_null_summary_ns, now, memory_order_relaxed);

    audio_flush_diag_emit(
            "FLUSH next_seq=%d buffer_flushed=%d before={empty=%d first=%u last=%u "
            "entry_count=%d hole=%d} after={empty=%d first=%u}",
            next_seq, buffer_flushed, was_empty, first_seq_before, last_seq_before,
            entry_count_before, hole_before, is_empty_after, first_seq_after);
}

void audio_flush_diag_dequeue(int got_payload,
                              unsigned seq,
                              unsigned payload_bytes,
                              int entry_count,
                              int hole_at_head,
                              int resend_wait) {
    int64_t now = mono_ns();
    if (!window_active(now)) return;

    int ms = (int)((now - atomic_load_explicit(&g_flush_ns, memory_order_relaxed)) / 1000000LL);

    if (!got_payload) {
        int n = atomic_fetch_add_explicit(&g_null_deq, 1, memory_order_relaxed) + 1;
        int64_t last = atomic_load_explicit(&g_last_null_summary_ns, memory_order_relaxed);
        if (n == 1 || (now - last) >= NULL_SUMMARY_NS) {
            atomic_store_explicit(&g_last_null_summary_ns, now, memory_order_relaxed);
            audio_flush_diag_emit(
                    "dequeue NULL ms=%d count=%d entry_count=%d hole=%d resend_wait=%d",
                    ms, n, entry_count, hole_at_head, resend_wait);
        }
        return;
    }

    int ok = atomic_fetch_add_explicit(&g_ok_deq, 1, memory_order_relaxed) + 1;
    int first = atomic_exchange_explicit(&g_first_ok_logged, 1, memory_order_relaxed);
    if (!first) {
        audio_flush_diag_emit(
                "dequeue FIRST_OK ms=%d seq=%u bytes=%u entry_count=%d prior_null=%d",
                ms, seq, payload_bytes, entry_count,
                atomic_load_explicit(&g_null_deq, memory_order_relaxed));
        return;
    }
    if (ok <= 8 || (ok % 25) == 0) {
        audio_flush_diag_emit("dequeue OK ms=%d n=%d seq=%u bytes=%u",
                              ms, ok, seq, payload_bytes);
    }
}

void audio_flush_diag_enqueue_auto_flush(unsigned incoming_seq, unsigned first_seq_before) {
    int ms = audio_flush_diag_ms_since_flush();
    audio_flush_diag_emit(
            "enqueue AUTO_FLUSH ms=%d incoming_seq=%u first_before=%u (256-slot overrun)",
            ms, incoming_seq, first_seq_before);
}

void audio_flush_diag_pcm_written(unsigned samples, int64_t pts_ns) {
    int64_t now = mono_ns();
    if (!window_active(now)) return;
    if (atomic_exchange_explicit(&g_pcm_logged, 1, memory_order_relaxed)) return;
    int ms = (int)((now - atomic_load_explicit(&g_flush_ns, memory_order_relaxed)) / 1000000LL);
    audio_flush_diag_emit("pcm FIRST_WRITE ms=%d samples=%u pts_ns=%lld",
                          ms, samples, (long long)pts_ns);
}

void audio_flush_diag_audible(const char *where, int non_zero_samples, int frames) {
    int64_t now = mono_ns();
    if (!window_active(now)) return;
    if (atomic_exchange_explicit(&g_audible_logged, 1, memory_order_relaxed)) return;
    int ms = (int)((now - atomic_load_explicit(&g_flush_ns, memory_order_relaxed)) / 1000000LL);
    audio_flush_diag_emit("AUDIBLE_START where=%s ms=%d non_zero=%d frames=%d",
                          where ? where : "?", ms, non_zero_samples, frames);
}

void audio_flush_diag_renderer_flush(void) {
    audio_flush_diag_emit("renderer_flush light codec+ring requestFlush");
}
