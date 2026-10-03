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

#define INTEREST_NS (5000000000LL)     /* 5s after arming event */
#define NULL_SUMMARY_NS (250000000LL)
#define BATCH_CAP (48 * 1024)
#define BATCH_FLUSH_NS (300000000LL)
#define DIAG_LINE_MAX 512

static atomic_llong g_interest_ns;     /* mono ns when interest armed; 0 = idle */
static atomic_llong g_flush_ns;        /* last RTSP/buffer FLUSH; 0 = never */
static atomic_int g_flush_seen;        /* 1 if any FLUSH this process/session */
static atomic_int g_next_seq;
static atomic_int g_null_deq;
static atomic_int g_ok_deq;
static atomic_int g_first_ok_logged;
static atomic_int g_pcm_after_gap_logged;
static atomic_llong g_last_null_summary_ns;

static atomic_int g_have_seq;
static atomic_uint g_last_seq;
static atomic_llong g_last_seq_jump_ns;
static atomic_llong g_last_audible_ns;
static atomic_int g_was_audible;       /* last Oboe callback had non-zero */
static atomic_llong g_silent_since_ns;
static atomic_int g_session_alive;

static pthread_mutex_t g_batch_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_batch_cv = PTHREAD_COND_INITIALIZER;
static char g_batch[BATCH_CAP];
static size_t g_batch_len;
static int64_t g_batch_first_ns;
static atomic_int g_ship_running;
static pthread_t g_ship_thread;
static atomic_int g_ship_started;

static JavaVM *g_vm;
static jclass g_shipper_cls;
static jmethodID g_enqueue_mid;

static int64_t mono_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

static void arm_interest(void) {
    atomic_store_explicit(&g_interest_ns, mono_ns(), memory_order_release);
}

static int interest_active(int64_t now) {
    int64_t t = atomic_load_explicit(&g_interest_ns, memory_order_acquire);
    return t != 0 && (now - t) >= 0 && (now - t) < INTEREST_NS;
}

int audio_flush_diag_active(void) {
    return interest_active(mono_ns());
}

int audio_flush_diag_ms_since_flush(void) {
    int64_t now = mono_ns();
    int64_t t = atomic_load_explicit(&g_flush_ns, memory_order_acquire);
    if (t == 0) return -1;
    return (int)((now - t) / 1000000LL);
}

int audio_flush_diag_flush_seen(void) {
    return atomic_load_explicit(&g_flush_seen, memory_order_relaxed);
}

static void batch_append_locked(const char *line) {
    size_t n = strlen(line);
    if (n == 0) return;
    size_t need = n + (g_batch_len ? 1 : 0);
    if (g_batch_len + need >= BATCH_CAP) {
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
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, jtext);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
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
        if (local_len > 0) jni_enqueue_batch(local, local_len);
    }
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
    char line[DIAG_LINE_MAX];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);

    __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG, "%s", line);

    ensure_shipper_thread();
    pthread_mutex_lock(&g_batch_mu);
    batch_append_locked(line);
    if (strstr(line, "FLUSH") || strstr(line, "FIRST_OK") || strstr(line, "AUDIBLE") ||
        strstr(line, "AUTO_FLUSH") || strstr(line, "SEQ_JUMP") || strstr(line, "BEACON") ||
        strstr(line, "SESSION") || strstr(line, "UNDERRUN") || strstr(line, "FORMAT") ||
        strstr(line, "CODEC")) {
        pthread_cond_signal(&g_batch_cv);
    }
    pthread_mutex_unlock(&g_batch_mu);
}

void audio_flush_diag_beacon(const char *version_name, int version_code) {
    audio_flush_diag_emit("BEACON hello AirPlayTV version=%s versionCode=%d",
                          version_name ? version_name : "?", version_code);
}

void audio_flush_diag_session_start(int ct, unsigned sample_rate, unsigned short control_port,
                                    unsigned short data_port) {
    atomic_store_explicit(&g_session_alive, 1, memory_order_relaxed);
    atomic_store_explicit(&g_have_seq, 0, memory_order_relaxed);
    atomic_store_explicit(&g_was_audible, 0, memory_order_relaxed);
    atomic_store_explicit(&g_flush_seen, 0, memory_order_relaxed);
    atomic_store_explicit(&g_flush_ns, 0, memory_order_relaxed);
    arm_interest();
    audio_flush_diag_emit(
            "SESSION_START ct=%d rate=%u cport=%u dport=%u flush_seen=0",
            ct, sample_rate, (unsigned)control_port, (unsigned)data_port);
}

void audio_flush_diag_session_stop(const char *why) {
    atomic_store_explicit(&g_session_alive, 0, memory_order_relaxed);
    audio_flush_diag_emit("SESSION_STOP why=%s flush_seen=%d",
                          why ? why : "?", audio_flush_diag_flush_seen());
}

void audio_flush_diag_rtsp_flush(const char *rtpinfo, int next_seq) {
    atomic_store_explicit(&g_flush_seen, 1, memory_order_relaxed);
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
    atomic_store_explicit(&g_flush_seen, 1, memory_order_relaxed);
    atomic_store_explicit(&g_next_seq, next_seq, memory_order_relaxed);
    atomic_store_explicit(&g_null_deq, 0, memory_order_relaxed);
    atomic_store_explicit(&g_ok_deq, 0, memory_order_relaxed);
    atomic_store_explicit(&g_first_ok_logged, 0, memory_order_relaxed);
    atomic_store_explicit(&g_pcm_after_gap_logged, 0, memory_order_relaxed);
    atomic_store_explicit(&g_last_null_summary_ns, now, memory_order_relaxed);
    atomic_store_explicit(&g_have_seq, 0, memory_order_relaxed);
    arm_interest();

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
    const int flush_seen = audio_flush_diag_flush_seen();
    const int ms_flush = audio_flush_diag_ms_since_flush();

    if (!got_payload) {
        if (resend_wait || hole_at_head > 0) {
            arm_interest();
            int n = atomic_fetch_add_explicit(&g_null_deq, 1, memory_order_relaxed) + 1;
            int64_t last = atomic_load_explicit(&g_last_null_summary_ns, memory_order_relaxed);
            if (n == 1 || (now - last) >= NULL_SUMMARY_NS) {
                atomic_store_explicit(&g_last_null_summary_ns, now, memory_order_relaxed);
                audio_flush_diag_emit(
                        "dequeue NULL count=%d entry_count=%d hole=%d resend_wait=%d "
                        "flush_seen=%d ms_since_flush=%d",
                        n, entry_count, hole_at_head, resend_wait, flush_seen, ms_flush);
            }
        } else if (interest_active(now)) {
            int n = atomic_fetch_add_explicit(&g_null_deq, 1, memory_order_relaxed) + 1;
            int64_t last = atomic_load_explicit(&g_last_null_summary_ns, memory_order_relaxed);
            if (n == 1 || (now - last) >= NULL_SUMMARY_NS) {
                atomic_store_explicit(&g_last_null_summary_ns, now, memory_order_relaxed);
                audio_flush_diag_emit(
                        "dequeue EMPTY count=%d entry_count=%d flush_seen=%d ms_since_flush=%d",
                        n, entry_count, flush_seen, ms_flush);
            }
        }
        return;
    }

    /* Seq discontinuity (mirror next-ep may skip FLUSH but jump seq). */
    if (atomic_load_explicit(&g_have_seq, memory_order_relaxed)) {
        unsigned prev = atomic_load_explicit(&g_last_seq, memory_order_relaxed);
        short delta = (short)(seq - prev);
        if (delta != 1 && delta != 0) {
            atomic_store_explicit(&g_last_seq_jump_ns, now, memory_order_relaxed);
            arm_interest();
            atomic_store_explicit(&g_null_deq, 0, memory_order_relaxed);
            atomic_store_explicit(&g_ok_deq, 0, memory_order_relaxed);
            atomic_store_explicit(&g_first_ok_logged, 0, memory_order_relaxed);
            atomic_store_explicit(&g_pcm_after_gap_logged, 0, memory_order_relaxed);
            audio_flush_diag_emit(
                    "SEQ_JUMP prev=%u now=%u delta=%d flush_seen=%d ms_since_flush=%d "
                    "ms_since_audible=%d",
                    prev, seq, (int)delta, flush_seen, ms_flush,
                    atomic_load_explicit(&g_last_audible_ns, memory_order_relaxed) == 0
                            ? -1
                            : (int)((now - atomic_load_explicit(&g_last_audible_ns,
                                                               memory_order_relaxed)) /
                                    1000000LL));
        }
    }
    atomic_store_explicit(&g_last_seq, seq, memory_order_relaxed);
    atomic_store_explicit(&g_have_seq, 1, memory_order_relaxed);

    now = mono_ns();
    if (!interest_active(now)) return;

    int ok = atomic_fetch_add_explicit(&g_ok_deq, 1, memory_order_relaxed) + 1;
    int first = atomic_exchange_explicit(&g_first_ok_logged, 1, memory_order_relaxed);
    if (!first) {
        audio_flush_diag_emit(
                "dequeue FIRST_OK seq=%u bytes=%u entry_count=%d prior_null=%d "
                "flush_seen=%d ms_since_flush=%d",
                seq, payload_bytes, entry_count,
                atomic_load_explicit(&g_null_deq, memory_order_relaxed), flush_seen, ms_flush);
        return;
    }
    if (ok <= 8 || (ok % 25) == 0) {
        audio_flush_diag_emit("dequeue OK n=%d seq=%u bytes=%u flush_seen=%d",
                              ok, seq, payload_bytes, flush_seen);
    }
}

void audio_flush_diag_enqueue_auto_flush(unsigned incoming_seq, unsigned first_seq_before) {
    arm_interest();
    audio_flush_diag_emit(
            "enqueue AUTO_FLUSH incoming_seq=%u first_before=%u flush_seen=%d "
            "ms_since_flush=%d (256-slot overrun)",
            incoming_seq, first_seq_before, audio_flush_diag_flush_seen(),
            audio_flush_diag_ms_since_flush());
}

void audio_flush_diag_format(int ct, int spf, int sample_rate, int using_screen) {
    arm_interest();
    audio_flush_diag_emit("FORMAT ct=%d spf=%d rate=%d screen=%d flush_seen=%d",
                          ct, spf, sample_rate, using_screen, audio_flush_diag_flush_seen());
}

void audio_flush_diag_codec_reset(int ct, int spf, int ok) {
    arm_interest();
    audio_flush_diag_emit("CODEC_RESET ct=%d spf=%d ok=%d flush_seen=%d",
                          ct, spf, ok, audio_flush_diag_flush_seen());
}

void audio_flush_diag_pcm_written(unsigned samples, int64_t pts_ns) {
    int64_t now = mono_ns();
    if (!interest_active(now)) return;
    if (atomic_exchange_explicit(&g_pcm_after_gap_logged, 1, memory_order_relaxed)) return;
    int64_t jump = atomic_load_explicit(&g_last_seq_jump_ns, memory_order_relaxed);
    audio_flush_diag_emit(
            "pcm FIRST_WRITE_AFTER_GAP samples=%u pts_ns=%lld flush_seen=%d "
            "ms_since_flush=%d ms_since_seq_jump=%d",
            samples, (long long)pts_ns, audio_flush_diag_flush_seen(),
            audio_flush_diag_ms_since_flush(),
            jump == 0 ? -1 : (int)((now - jump) / 1000000LL));
}

void audio_flush_diag_underrun(int frames_needed, int frames_got) {
    arm_interest();
    atomic_store_explicit(&g_pcm_after_gap_logged, 0, memory_order_relaxed);
    audio_flush_diag_emit(
            "UNDERRUN need=%d got=%d flush_seen=%d ms_since_flush=%d ms_since_audible=%d",
            frames_needed, frames_got, audio_flush_diag_flush_seen(),
            audio_flush_diag_ms_since_flush(),
            atomic_load_explicit(&g_last_audible_ns, memory_order_relaxed) == 0
                    ? -1
                    : (int)((mono_ns() - atomic_load_explicit(&g_last_audible_ns,
                                                             memory_order_relaxed)) /
                            1000000LL));
}

void audio_flush_diag_oboe_pcm(const int16_t *pcm, int samples, int frames) {
    if (!pcm || samples <= 0) return;
    int nz = 0;
    for (int i = 0; i < samples; ++i) {
        if (pcm[i] != 0) {
            ++nz;
            if (nz >= 4) break;
        }
    }
    const int audible = nz > 0;
    int64_t now = mono_ns();
    int was = atomic_load_explicit(&g_was_audible, memory_order_relaxed);

    if (!audible) {
        if (was) {
            atomic_store_explicit(&g_silent_since_ns, now, memory_order_relaxed);
            atomic_store_explicit(&g_was_audible, 0, memory_order_relaxed);
            /* Do not spam; silence entry is implied by later AUDIBLE_RESUME. */
        } else if (atomic_load_explicit(&g_silent_since_ns, memory_order_relaxed) == 0) {
            atomic_store_explicit(&g_silent_since_ns, now, memory_order_relaxed);
        }
        return;
    }

    int64_t last_aud = atomic_load_explicit(&g_last_audible_ns, memory_order_relaxed);
    int64_t silent_since = atomic_load_explicit(&g_silent_since_ns, memory_order_relaxed);
    const int resume = !was && (silent_since != 0 || last_aud != 0);
    const int long_gap = last_aud != 0 && (now - last_aud) > 400000000LL; /* >400ms */

    atomic_store_explicit(&g_last_audible_ns, now, memory_order_relaxed);
    atomic_store_explicit(&g_was_audible, 1, memory_order_relaxed);
    atomic_store_explicit(&g_silent_since_ns, 0, memory_order_relaxed);

    if (resume || long_gap || interest_active(now)) {
        if (resume || long_gap) arm_interest();
        int64_t jump = atomic_load_explicit(&g_last_seq_jump_ns, memory_order_relaxed);
        audio_flush_diag_emit(
                "AUDIBLE_RESUME where=oboe frames=%d non_zero=%d flush_seen=%d "
                "ms_since_flush=%d ms_since_audible=%d ms_since_seq_jump=%d "
                "ms_silent=%d",
                frames, nz, audio_flush_diag_flush_seen(),
                audio_flush_diag_ms_since_flush(),
                last_aud == 0 ? -1 : (int)((now - last_aud) / 1000000LL),
                jump == 0 ? -1 : (int)((now - jump) / 1000000LL),
                silent_since == 0 ? 0 : (int)((now - silent_since) / 1000000LL));
    }
}

void audio_flush_diag_renderer_flush(void) {
    arm_interest();
    audio_flush_diag_emit("renderer_flush light codec+ring requestFlush flush_seen=%d",
                          audio_flush_diag_flush_seen());
}
