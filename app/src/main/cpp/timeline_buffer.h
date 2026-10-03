#ifndef TIMELINE_BUFFER_H
#define TIMELINE_BUFFER_H

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>

#include "audio_time.h"
#include "audio_flush_diag.h"

/*
 * lock-free SPSC ring of int16 samples (interleaved frames); positions are
 * non-wrapping 64-bit counters so empty/full are unambiguous without spare slot
 */
class SpscRing {
public:
    explicit SpscRing(size_t capacitySamples)
        : mCapacity(capacitySamples), mBuf(new int16_t[capacitySamples]) {}

    size_t available() const {  // samples readable
        return mWrite.load(std::memory_order_acquire) - mRead.load(std::memory_order_acquire);
    }

    // producer: push up to n samples, drops what doesn't fit
    size_t write(const int16_t *src, size_t n) {
        const size_t w = mWrite.load(std::memory_order_relaxed);
        const size_t r = mRead.load(std::memory_order_acquire);
        const size_t freeSpace = mCapacity - (w - r);
        if (n > freeSpace) n = freeSpace;
        const size_t wi = w % mCapacity;
        const size_t first = (n < mCapacity - wi) ? n : (mCapacity - wi);
        memcpy(&mBuf[wi], src, first * sizeof(int16_t));
        if (n > first) memcpy(&mBuf[0], src + first, (n - first) * sizeof(int16_t));
        mWrite.store(w + n, std::memory_order_release);
        return n;
    }

    // consumer: pop up to n samples
    size_t read(int16_t *dst, size_t n) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = w - r;
        if (n > avail) n = avail;
        const size_t ri = r % mCapacity;
        const size_t first = (n < mCapacity - ri) ? n : (mCapacity - ri);
        memcpy(dst, &mBuf[ri], first * sizeof(int16_t));
        if (n > first) memcpy(dst + first, &mBuf[0], (n - first) * sizeof(int16_t));
        mRead.store(r + n, std::memory_order_release);
        return n;
    }

    // consumer: discard up to n oldest samples
    void skip(size_t n) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = w - r;
        if (n > avail) n = avail;
        mRead.store(r + n, std::memory_order_release);
    }

    // Monotonic write counter (not modulo capacity). Producer or any thread.
    size_t writePos() const { return mWrite.load(std::memory_order_acquire); }

    // Consumer: discard samples written strictly before absWritePos; keep anything
    // the producer queued after that point (post-FLUSH PCM).
    void skipThrough(size_t absWritePos) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        size_t target = absWritePos;
        if (target < r) target = r;
        if (target > w) target = w;
        mRead.store(target, std::memory_order_release);
    }

private:
    const size_t mCapacity;
    std::unique_ptr<int16_t[]> mBuf;
    std::atomic<size_t> mWrite{0};
    std::atomic<size_t> mRead{0};
};

/*
 * adaptive jitter cushion tuner, NetEQ-inspired but simplified: decaying histogram of
 * packet jitter, cushion = high percentile of it + 1 packet of margin.
 * observe() is producer-thread only; result published via atomic, consumer reads it live
 */
class DelayTracker {
public:
    // staticCushionMs > 0: fixed cushion, tuner disabled
    // staticCushionMs <= 0: tune between MIN_CUSHION_MS and MAX_CUSHION_MS
    DelayTracker(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate), mChannels(channels),
          mAdaptive(staticCushionMs <= 0),
          mFloor(msToSamples(sampleRate, channels, mAdaptive ? MIN_CUSHION_MS : staticCushionMs)),
          mCeil(msToSamples(sampleRate, channels, mAdaptive ? MAX_CUSHION_MS : staticCushionMs)),
          mPercentilePct(percentilePct),
          mTuned(mFloor) {}

    // ptsNs: packet presentation time; arrivalNs: local arrival time; durNs: packet duration
    void observe(int64_t ptsNs, int64_t arrivalNs, int64_t durNs) {
        if (!mAdaptive) return;

        const int64_t raw = arrivalNs - ptsNs; // arrival latency
        // jitter = latency relative to min latency seen so far (mBaseNs)
        if (!mHaveBase) { mBaseNs = raw; mHaveBase = true; }
        else if (raw < mBaseNs) mBaseNs = raw;
        // creep mBaseNs up slowly: counters clock drift and one freak fast packet
        else mBaseNs += (raw - mBaseNs) >> BASE_CREEP_SHIFT;
        int64_t rel = raw - mBaseNs;
        if (rel < 0) rel = 0;

        int bin = (int)(rel / BUCKET_NS);
        if (bin >= NBUCKETS) bin = NBUCKETS - 1;

        // decay: each bin bleeds 1/2^FORGET_SHIFT per packet, current bin topped up by
        // ONE/2^FORGET_SHIFT, so total converges to ONE
        int64_t sum = 0;
        for (int i = 0; i < NBUCKETS; i++) {
            mHist[i] -= (mHist[i] + (1 << FORGET_SHIFT) - 1) >> FORGET_SHIFT;
            sum += mHist[i];
        }
        mHist[bin] += ONE >> FORGET_SHIFT;
        sum += ONE >> FORGET_SHIFT;

        // cushion = smallest delay whose cumulative histogram share reaches percentile
        const int64_t thresh = sum * mPercentilePct / 100;
        int64_t cum = 0; int b = 0;
        for (; b < NBUCKETS - 1; b++) { cum += mHist[b]; if (cum >= thresh) break; }
        // bin is lower bound on delay: take top edge + margin. margin must keep output
        // fed for one data callback and tide us over until next packet
        const int64_t marginNs = std::max(durNs, mOutputBufferNs.load(std::memory_order_relaxed));
        const int64_t targetNs = (int64_t)(b + 1) * BUCKET_NS + marginNs;
        size_t samples = (size_t)(targetNs * mSampleRate / NS_PER_SEC) * mChannels;
        if (samples < mFloor) samples = mFloor;
        if (samples > mCeil) samples = mCeil;
        mTuned.store(samples, std::memory_order_relaxed);
    }

    // reset clock base after discontinuity or clock shift/resync so it doesn't poison
    // jitter calculations; producer-thread only (same as observe())
    void reanchor() { mHaveBase = false; }

    // RAOP FLUSH / seek / next-episode: drop historical jitter so cushion does not stay
    // pinned near the adaptive ceiling (can be ~1s) while video presents ASAP.
    void resetToFloor() {
        mHaveBase = false;
        mBaseNs = 0;
        for (int i = 0; i < NBUCKETS; i++) mHist[i] = 0;
        mTuned.store(mFloor, std::memory_order_relaxed);
    }

    // latest tuned cushion in samples; any thread
    size_t target() const { return mTuned.load(std::memory_order_relaxed); }

    void noteOutputBufferFrames(int frames) {
        mOutputBufferNs.store((int64_t)frames * NS_PER_SEC / mSampleRate, std::memory_order_relaxed);
    }

    // largest cushion tuner can reach, in samples
    size_t ceil() const { return mCeil; }

private:
    static size_t msToSamples(int sampleRate, int channels, int ms) {
        return (size_t)sampleRate * ms / 1000 * channels;
    }

    static constexpr int MIN_CUSHION_MS = 20;            // floor prevents chronic underruns on TV SoCs
    // Music-only adaptive ceiling. Screen-mirror paths force a short fixed cushion in Kotlin
    // (AudioConfig.forScreenMirror) so lip-sync does not track a growing music buffer.
    static constexpr int MAX_CUSHION_MS = 1000;
    static constexpr int BUCKET_MS = 5;                  // histogram granularity
    static constexpr int NBUCKETS = MAX_CUSHION_MS / BUCKET_MS;
    static constexpr int64_t BUCKET_NS = (int64_t)BUCKET_MS * 1000000LL;
    static constexpr int FORGET_SHIFT = 8;               // ~1/256 decay per packet (~6s @ 23ms)
    static constexpr int BASE_CREEP_SHIFT = 10;          // mBaseNs creep speed
    static constexpr int64_t ONE = 1 << 20;              // histogram fixed-point unit

    const int mSampleRate;
    const int mChannels;
    const bool mAdaptive;                // false = fixed cushion (tuner disabled)
    const size_t mFloor;
    const size_t mCeil;
    const int mPercentilePct;            // arrival-delay percentile cushion targets
    std::atomic<size_t> mTuned;
    std::atomic<int64_t> mOutputBufferNs{0};  // output->producer: oboe output buffer duration
    int64_t mHist[NBUCKETS] = {};
    int64_t mBaseNs = 0;
    bool mHaveBase = false;
};

// cumulative diagnostic counters, bumped from producer/consumer, read from stats thread
class TimelineMetrics {
public:
    // packed: nests into AudioDebugData with fixed layout
    struct __attribute__((packed)) Debug {
        uint32_t trims, drops, silences, underruns;  // cumulative; 32-bit to avoid wrap
    };

    void countTrim()     { mTrims.fetch_add(1, std::memory_order_relaxed); }
    void countDrop()     { mDrops.fetch_add(1, std::memory_order_relaxed); }
    void countSilence()  { mSilences.fetch_add(1, std::memory_order_relaxed); }
    void countUnderrun() { mUnderruns.fetch_add(1, std::memory_order_relaxed); }

    // any thread
    Debug debugInfo() const {
        Debug d{};
        d.trims = mTrims.load(std::memory_order_relaxed);
        d.drops = mDrops.load(std::memory_order_relaxed);
        d.silences = mSilences.load(std::memory_order_relaxed);
        d.underruns = mUnderruns.load(std::memory_order_relaxed);
        return d;
    }

private:
    std::atomic<uint32_t> mTrims{0};      // backlog exceeded cap and was trimmed
    std::atomic<uint32_t> mDrops{0};      // late packets dropped, pts slot already passed
    std::atomic<uint32_t> mSilences{0};   // gaps filled with silence
    std::atomic<uint32_t> mUnderruns{0};  // ring ran dry, output padded with silence
};

/*
 * Timeline-synchronized playout buffer: absorbs jitter, clock drift and clock resyncs,
 * syncs audio to its presentation-time tag.
 *
 * One concurrent reader + one concurrent writer OK; multiple readers or writers NOT safe.
 * samples = frames * channels
 */
class TimelineBuffer {
public:
    // cushion policy lives in mTracker; timeline reads mTracker.target() live
    TimelineBuffer(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate),
          mChannels(channels),
          mTracker(sampleRate, channels, staticCushionMs, percentilePct),
          // ring can't be reallocated mid-stream: size for worst-case backlog, i.e. cap
          // of largest reachable cushion + one throttle window of overshoot (trims are
          // throttled), 2x slack
          mRing(2 * capOf(mTracker.ceil()) + (size_t)sampleRate * TRIM_THROTTLE_MS / 1000 * channels) {}

    void noteOutputBufferFrames(int frames) { mTracker.noteOutputBufferFrames(frames); }

    int channels() const { return mChannels; }

    // producer: push samples
    void write(const int16_t *pcm, size_t samples, int64_t ptsNs) {
        if (samples == 0) return;
        // Do NOT drop while a flush is pending: post-FLUSH mirror audio often arrives
        // before the Oboe callback applies the discard. Dropping it caused 1–2s of
        // silence at next-episode start. Consumer skipThrough() keeps only post-flush PCM.
        // pts 0 = sender clock not NTP synced yet
        if (ptsNs == 0) {
            mRing.write(pcm, samples);
            audio_flush_diag_pcm_written((unsigned)samples, ptsNs);
            return;
        }
        const int64_t durNs = samples * NS_PER_SEC / mChannels / mSampleRate;
        const bool grace = inPostFlushGrace();

        // consumer underran since last write: it already played the gap as real-time
        // silence, re-anchor so we don't also insert it here
        if (mUnderran.exchange(false, std::memory_order_relaxed)) {
            mExpectedPtsNs = ptsNs;
        }

        // first packet after start/flush: establish timeline head (do not treat as gap)
        if (mExpectedPtsNs == 0) {
            mTracker.observe(ptsNs, monoNs(), durNs);
            mRing.write(pcm, samples);
            mExpectedPtsNs = ptsNs + durNs;
            audio_flush_diag_pcm_written((unsigned)samples, ptsNs);
            return;
        }

        const int64_t gapNs = ptsNs - mExpectedPtsNs;

        // large gap: discontinuity (seek / next episode) or clock shift.
        // Outside the post-FLUSH grace window, discard stale backlog via consumer flush.
        // Inside grace (screen-mirror next-episode), cascading FLUSH + drop loops mute
        // audio for ~1–2s while video already presents — re-anchor and keep PCM instead.
        if (gapNs > MAX_GAP_NS || gapNs < -MAX_GAP_NS) {
            if (grace) {
                mTracker.resetToFloor();
                mExpectedPtsNs = 0;
                mTracker.observe(ptsNs, monoNs(), durNs);
                mRing.write(pcm, samples);
                mExpectedPtsNs = ptsNs + durNs;
                audio_flush_diag_pcm_written((unsigned)samples, ptsNs);
                return;
            }
            requestFlush();
            return;
        }

        // no-op in static-cushion mode
        mTracker.observe(ptsNs, monoNs(), durNs);

        if (gapNs > SLACK_NS) {
            if (!grace) {
                // sender left gap: reproduce as silence so timing is exact
                const size_t silenceFrames = (size_t)(gapNs * mSampleRate / NS_PER_SEC);
                writeSilenceFrames(silenceFrames);
                mMetrics.countSilence();
            }
            // During grace, skip synthetic silence — it delays real PCM behind video.
        } else if (gapNs < -SLACK_NS) {
            // slot already passed (late/overlap): drop
            mMetrics.countDrop();
            return;
        }
        mRing.write(pcm, samples);
        mExpectedPtsNs = ptsNs + durNs;
        audio_flush_diag_pcm_written((unsigned)samples, ptsNs);
    }

    // consumer: pop frames*channels samples into out, silence-padded on underrun
    void read(int16_t *out, int32_t frames) {
        const size_t need = (size_t)frames * mChannels;
        const int64_t now = monoNs();

        // apply producer-requested flush on the consumer side (skip is consumer-only)
        if (mFlushRequested.exchange(false, std::memory_order_acq_rel)) {
            // Discard only pre-FLUSH samples; keep PCM written after requestFlush().
            mRing.skipThrough(mFlushThroughWrite.load(std::memory_order_acquire));
            // UxPlay-style playthrough: do NOT enter silent priming after FLUSH — keep
            // feeding the output callback. Brief underrun pads one callback if empty.
            mPriming = false;
            mPostFlushPrime = false;
            mPostFlushCatchUpUntilNs = now + POST_FLUSH_CATCHUP_NS;
            mPrimeSilenceFrames = 0;
            mAboveCapSinceNs = 0;
            mUnderran.store(false, std::memory_order_relaxed);
        }

        const size_t tuned = mTracker.target();
        const size_t cap = capOf(tuned);
        const bool postFlushCatchUp = now < mPostFlushCatchUpUntilNs;

        // bound latency: cap tracks tuned cushion live so a calmed link sheds latency
        // without an underrun. each trim costs a glitch, so be conservative: trim only
        // after backlog stayed above cap for TRIM_SUSTAIN (skip transient spikes), at
        // most once per TRIM_THROTTLE, and not within that window of an underrun
        // (trimming while rebuilding cushion is counterproductive).
        // Exception: right after RAOP FLUSH, discontinuity bursts must shed immediately
        // or A/V stays 0.5–2s apart until the sustain timer fires. Leave `cap` (not bare
        // tuned) so we do not underrun into another silent prime.
        const size_t avail = mRing.available();
        if (postFlushCatchUp) {
            if (avail > cap) {
                mRing.skip(avail - cap);
                mMetrics.countTrim();
                mAboveCapSinceNs = 0;
            }
        } else {
            if (avail > cap) {
                if (mAboveCapSinceNs == 0) mAboveCapSinceNs = now;
            } else {
                mAboveCapSinceNs = 0;
            }
            if (mAboveCapSinceNs != 0 && now - mAboveCapSinceNs >= TRIM_SUSTAIN_NS
                    && now - mLastTrimBlockNs >= TRIM_THROTTLE_NS) {
                mRing.skip(avail - tuned);
                mMetrics.countTrim();
                mLastTrimBlockNs = now;
                mAboveCapSinceNs = 0;
            }
        }

        // prebuffer: hold output until cushion fills, builds jitter headroom.
        // After RAOP FLUSH / next-episode, play as soon as any post-flush PCM arrives
        // (primeTarget may be 0) so we do not add another tens–hundreds of ms behind video.
        if (mPriming) {
            const size_t buffered = mRing.available();
            const size_t primeTarget = mPostFlushPrime
                    ? std::min(tuned, postFlushPrimeSamples())
                    : tuned;
            if (buffered == 0) mPrimeSilenceFrames = 0;
            else mPrimeSilenceFrames += (uint32_t)frames;
            const uint32_t starveFrames = (uint32_t)(2 * std::max(primeTarget, (size_t)mChannels) / mChannels);
            const bool starved = buffered > 0 && mPrimeSilenceFrames >= starveFrames;
            if (buffered == 0 || (primeTarget > 0 && buffered < primeTarget && !starved)) {
                memset(out, 0, need * sizeof(int16_t));
                return;
            }
            // Priming does not drain the ring. A post-FLUSH RTP/decode burst can therefore
            // grow far past primeTarget before this callback runs. Shed down to `cap`
            // (~2× cushion, ~80ms mirror) — tight enough for lip-sync, loose enough to
            // avoid underrun→silent reprime.
            if (mPostFlushPrime) {
                const size_t have = mRing.available();
                const size_t want = std::max(primeTarget, cap);
                if (have > want) {
                    mRing.skip(have - want);
                    mMetrics.countTrim();
                }
            }
            mPriming = false;
            mPostFlushPrime = false;
            mPrimeSilenceFrames = 0;
        }

        const size_t got = mRing.read(out, need);
        if (got < need) {
            memset(out + got, 0, (need - got) * sizeof(int16_t));
            mPriming = true;
            // Stay on the short post-FLUSH prime during catch-up; full adaptive reprime
            // here was a common path into multi-hundred-ms silence after hard-trim.
            mPostFlushPrime = postFlushCatchUp;
            mLastTrimBlockNs = now;  // hold off trims while rebuilding
            mUnderran.store(true, std::memory_order_relaxed);  // producer re-anchors
            mMetrics.countUnderrun();
        }
    }

    // call after discontinuity or clock shift/resync; producer-side, must not run
    // concurrently with write()
    void reanchorTracker() { mTracker.resetToFloor(); }

    // producer (or same thread as write): request consumer to discard pre-FLUSH backlog.
    // UxPlay-style: consumer clears the ring without entering a silent priming gate.
    void requestFlush() {
        mExpectedPtsNs = 0;
        mTracker.resetToFloor();
        mPostFlushGraceUntilNs.store(monoNs() + POST_FLUSH_CATCHUP_NS, std::memory_order_relaxed);
        mFlushThroughWrite.store(mRing.writePos(), std::memory_order_release);
        mFlushRequested.store(true, std::memory_order_release);
    }

    // rebuild prebuffer cushion before resuming, e.g. after output stream restart;
    // must not run concurrently with read()
    void reprime() { mPriming = true; mPostFlushPrime = false; }

    // drop buffered audio + rebuild cushion, so resume after pause doesn't play stale
    // tail; only while output callback is stopped (skip() is consumer-side).
    // postFlushPrime: treat like RAOP FLUSH / next-episode (short prime + catch-up window)
    // rather than a cold start that waits on the full adaptive cushion.
    void flushAndReprime(bool postFlushPrime = false) {
        mRing.skip(mRing.available());
        mExpectedPtsNs = 0;
        mTracker.resetToFloor();
        mFlushRequested.store(false, std::memory_order_relaxed);
        mFlushThroughWrite.store(mRing.writePos(), std::memory_order_relaxed);
        mPriming = true;
        mPostFlushPrime = postFlushPrime;
        const int64_t graceUntil = postFlushPrime ? monoNs() + POST_FLUSH_CATCHUP_NS : 0;
        mPostFlushCatchUpUntilNs = graceUntil;
        mPostFlushGraceUntilNs.store(graceUntil, std::memory_order_relaxed);
        mPrimeSilenceFrames = 0;
        mAboveCapSinceNs = 0;
        mUnderran.store(false, std::memory_order_relaxed);
    }

    // debug snapshot: backlog + tuned cushion (ms) + counters; reads only atomics, any thread
    struct __attribute__((packed)) Debug {
        uint16_t backlogMs;         // bounded by ring (few seconds)
        uint16_t tunedCushionMs;    // bounded by MAX_CUSHION_MS
        TimelineMetrics::Debug metrics;
    };
    Debug debugInfo() const {
        Debug d{};
        d.backlogMs = (uint16_t)(mRing.available() / mChannels * 1000 / mSampleRate);
        d.tunedCushionMs = (uint16_t)(mTracker.target() / mChannels * 1000 / mSampleRate);
        d.metrics = mMetrics.debugInfo();
        return d;
    }

private:

    // latency ceiling for cushion: 2x, but never below TRIM_FLOOR_MS so tiny cushion
    // still gets slack before a trim (trim costs a glitch)
    size_t capOf(size_t cushionSamples) const {
        return std::max(cushionSamples * 2, (size_t)mSampleRate * TRIM_FLOOR_MS / 1000 * mChannels);
    }

    size_t postFlushPrimeSamples() const {
        return (size_t)mSampleRate * POST_FLUSH_PRIME_MS / 1000 * mChannels;
    }

    bool inPostFlushGrace() const {
        return monoNs() < mPostFlushGraceUntilNs.load(std::memory_order_relaxed);
    }

    void writeSilenceFrames(size_t frames) {
        int16_t zeros[512] = {0};  // 256 stereo frames per chunk
        size_t samples = frames * mChannels;
        while (samples > 0) {
            const size_t n = std::min<size_t>(samples, 512);
            mRing.write(zeros, n);
            samples -= n;
        }
    }

    static constexpr int64_t SLACK_NS = 10'000'000LL;    // ignore <10ms gaps (NTP shift, rounding)
    static constexpr int64_t MAX_GAP_NS = 750'000'000LL; // >750ms = discontinuity, re-anchor
    static constexpr int TRIM_FLOOR_MS = 30;             // min trim point even for tiny cushion
    static constexpr int TRIM_THROTTLE_MS = 2000;        // min spacing between trims, and after underrun
    static constexpr int64_t TRIM_THROTTLE_NS = (int64_t)TRIM_THROTTLE_MS * 1'000'000LL;
    static constexpr int TRIM_SUSTAIN_MS = 1000;         // backlog must exceed cap this long before trim
    static constexpr int64_t TRIM_SUSTAIN_NS = (int64_t)TRIM_SUSTAIN_MS * 1'000'000LL;
    static constexpr int POST_FLUSH_PRIME_MS = 0;        // play ASAP after FLUSH (any PCM)
    static constexpr int POST_FLUSH_CATCHUP_MS = 3000;   // aggressive trim window after FLUSH
    static constexpr int64_t POST_FLUSH_CATCHUP_NS =
            (int64_t)POST_FLUSH_CATCHUP_MS * 1'000'000LL;

    const int mSampleRate;
    const int mChannels;
    DelayTracker mTracker;               // owns cushion policy; read live each read()
    SpscRing mRing;
    TimelineMetrics mMetrics;
    bool mPriming = true;                // stream start: buffer output to build cushion
    bool mPostFlushPrime = false;        // intentional flush: use short prime, not full cushion
    uint32_t mPrimeSilenceFrames = 0;    // consumer-only: silence frames output while priming with partial data
    int64_t mLastTrimBlockNs = 0;        // consumer-only: last trim/underrun time (trim throttle)
    int64_t mAboveCapSinceNs = 0;        // consumer-only: when backlog first exceeded cap (0 = under)
    int64_t mPostFlushCatchUpUntilNs = 0; // consumer-only: immediate-trim deadline after FLUSH
    std::atomic<int64_t> mPostFlushGraceUntilNs{0}; // producer: suppress cascade flush / gap silence
    std::atomic<bool> mUnderran{false};  // consumer->producer: underrun happened
    std::atomic<bool> mFlushRequested{false};  // producer->consumer: discard pre-FLUSH backlog
    std::atomic<size_t> mFlushThroughWrite{0}; // writePos at requestFlush; consumer skipThrough

    int64_t mExpectedPtsNs = 0;          // presentation time expected at write head
};

#endif  // TIMELINE_BUFFER_H
