/**
 * Temporary Honor QA audio diagnostics (logcat tag AirPlayAudio) + remote ship.
 * Not limited to RTSP FLUSH: system Screen Mirroring next-episode may never FLUSH.
 */
#ifndef AUDIO_FLUSH_DIAG_H
#define AUDIO_FLUSH_DIAG_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AIRPLAY_AUDIO_TAG "AirPlayAudio"

void audio_flush_diag_jni_init(void *jni_env);
void audio_flush_diag_jni_shutdown(void *jni_env);

/** Always ships (logcat + remote). */
void audio_flush_diag_emit(const char *fmt, ...)
    __attribute__((format(printf, 1, 2)));

/** Startup / session lifecycle. */
void audio_flush_diag_beacon(const char *version_name, int version_code);
void audio_flush_diag_session_start(int ct, unsigned sample_rate, unsigned short control_port,
                                    unsigned short data_port);
void audio_flush_diag_session_stop(const char *why);

void audio_flush_diag_rtsp_flush(const char *rtpinfo, int next_seq);
void audio_flush_diag_on_flush(int next_seq,
                               int buffer_flushed,
                               int was_empty,
                               unsigned first_seq_before,
                               unsigned last_seq_before,
                               int entry_count_before,
                               int hole_before,
                               int is_empty_after,
                               unsigned first_seq_after);

/** True while an interest window is open (FLUSH / seq-jump / hole / underrun). */
int audio_flush_diag_active(void);
int audio_flush_diag_ms_since_flush(void);
int audio_flush_diag_flush_seen(void);

/** Always called from raop_buffer (not gated). Summarizes holes; logs seq jumps. */
void audio_flush_diag_dequeue(int got_payload,
                              unsigned seq,
                              unsigned payload_bytes,
                              int entry_count,
                              int hole_at_head,
                              int resend_wait);

void audio_flush_diag_enqueue_auto_flush(unsigned incoming_seq, unsigned first_seq_before);

void audio_flush_diag_format(int ct, int spf, int sample_rate, int using_screen);
void audio_flush_diag_codec_reset(int ct, int spf, int ok);

void audio_flush_diag_pcm_written(unsigned samples, int64_t pts_ns);
void audio_flush_diag_underrun(int frames_needed, int frames_got);
void audio_flush_diag_oboe_pcm(const int16_t *pcm, int samples, int frames);
void audio_flush_diag_renderer_flush(void);

#ifdef __cplusplus
}
#endif

#endif /* AUDIO_FLUSH_DIAG_H */
