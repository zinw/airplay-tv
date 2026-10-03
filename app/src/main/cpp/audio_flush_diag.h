/**
 * Next-episode / RTSP FLUSH diagnostics for Honor QA (logcat tag AirPlayAudio).
 * Active for ~3s after each FLUSH; otherwise no-ops stay cheap.
 * Also batches lines for temporary remote ingest (see DiagLogShipper).
 */
#ifndef AUDIO_FLUSH_DIAG_H
#define AUDIO_FLUSH_DIAG_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Tag for adb logcat -s AirPlayAudio:I */
#define AIRPLAY_AUDIO_TAG "AirPlayAudio"

/** Cache DiagLogShipper JNI handles; call once from nativeInit (pass JNIEnv*). */
void audio_flush_diag_jni_init(void *jni_env);

/** Drop JNI globals / stop shipper thread; call from nativeDestroy (pass JNIEnv*). */
void audio_flush_diag_jni_shutdown(void *jni_env);

/** Format + logcat + enqueue for remote ship (FLUSH-window lines only). */
void audio_flush_diag_emit(const char *fmt, ...)
    __attribute__((format(printf, 1, 2)));

/** RTSP FLUSH request (RTP-Info header as received). */
void audio_flush_diag_rtsp_flush(const char *rtpinfo, int next_seq);

/** Mark FLUSH boundary; starts the post-FLUSH capture window. */
void audio_flush_diag_on_flush(int next_seq,
                               int buffer_flushed,
                               int was_empty,
                               unsigned first_seq_before,
                               unsigned last_seq_before,
                               int entry_count_before,
                               int hole_before,
                               int is_empty_after,
                               unsigned first_seq_after);

/** True while within the post-FLUSH window. */
int audio_flush_diag_active(void);

/** Milliseconds since last FLUSH mark (or -1 if no active window). */
int audio_flush_diag_ms_since_flush(void);

void audio_flush_diag_dequeue(int got_payload,
                              unsigned seq,
                              unsigned payload_bytes,
                              int entry_count,
                              int hole_at_head,
                              int resend_wait);

void audio_flush_diag_enqueue_auto_flush(unsigned incoming_seq, unsigned first_seq_before);

void audio_flush_diag_pcm_written(unsigned samples, int64_t pts_ns);

/** First Oboe callback that delivers non-silent PCM after FLUSH. */
void audio_flush_diag_audible(const char *where, int non_zero_samples, int frames);

void audio_flush_diag_renderer_flush(void);

#ifdef __cplusplus
}
#endif

#endif /* AUDIO_FLUSH_DIAG_H */
