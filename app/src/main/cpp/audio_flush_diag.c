#include "audio_flush_diag.h"

#include <android/log.h>
#include <stdatomic.h>
#include <stdint.h>
#include <time.h>

#define WINDOW_NS (3000000000LL) /* 3s after FLUSH */
#define NULL_SUMMARY_NS (250000000LL)

static atomic_llong g_flush_ns;
static atomic_int g_next_seq;
static atomic_int g_null_deq;
static atomic_int g_ok_deq;
static atomic_int g_first_ok_logged;
static atomic_int g_pcm_logged;
static atomic_int g_audible_logged;
static atomic_llong g_last_null_summary_ns;

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

void audio_flush_diag_rtsp_flush(const char *rtpinfo, int next_seq) {
    __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
                        "RTSP_FLUSH RTP-Info=%s next_seq=%d",
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

    __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
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
            __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
                                "dequeue NULL ms=%d count=%d entry_count=%d hole=%d resend_wait=%d",
                                ms, n, entry_count, hole_at_head, resend_wait);
        }
        return;
    }

    int ok = atomic_fetch_add_explicit(&g_ok_deq, 1, memory_order_relaxed) + 1;
    int first = atomic_exchange_explicit(&g_first_ok_logged, 1, memory_order_relaxed);
    if (!first) {
        __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
                            "dequeue FIRST_OK ms=%d seq=%u bytes=%u entry_count=%d "
                            "prior_null=%d",
                            ms, seq, payload_bytes, entry_count,
                            atomic_load_explicit(&g_null_deq, memory_order_relaxed));
        return;
    }
    /* Keep volume low: first 8, then every 25th */
    if (ok <= 8 || (ok % 25) == 0) {
        __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
                            "dequeue OK ms=%d n=%d seq=%u bytes=%u",
                            ms, ok, seq, payload_bytes);
    }
}

void audio_flush_diag_enqueue_auto_flush(unsigned incoming_seq, unsigned first_seq_before) {
    int64_t now = mono_ns();
    int ms = audio_flush_diag_ms_since_flush();
    __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
                        "enqueue AUTO_FLUSH ms=%d incoming_seq=%u first_before=%u "
                        "(256-slot overrun)",
                        ms, incoming_seq, first_seq_before);
    (void)now;
}

void audio_flush_diag_pcm_written(unsigned samples, int64_t pts_ns) {
    int64_t now = mono_ns();
    if (!window_active(now)) return;
    if (atomic_exchange_explicit(&g_pcm_logged, 1, memory_order_relaxed)) return;
    int ms = (int)((now - atomic_load_explicit(&g_flush_ns, memory_order_relaxed)) / 1000000LL);
    __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
                        "pcm FIRST_WRITE ms=%d samples=%u pts_ns=%lld",
                        ms, samples, (long long)pts_ns);
}

void audio_flush_diag_audible(const char *where, int non_zero_samples, int frames) {
    int64_t now = mono_ns();
    if (!window_active(now)) return;
    if (atomic_exchange_explicit(&g_audible_logged, 1, memory_order_relaxed)) return;
    int ms = (int)((now - atomic_load_explicit(&g_flush_ns, memory_order_relaxed)) / 1000000LL);
    __android_log_print(ANDROID_LOG_INFO, AIRPLAY_AUDIO_TAG,
                        "AUDIBLE_START where=%s ms=%d non_zero=%d frames=%d",
                        where ? where : "?", ms, non_zero_samples, frames);
}
