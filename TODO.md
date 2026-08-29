# TODO

## Android media extraction

- [ ] Support `MediaDataSource`-backed extraction so Signal can generate voice-note waveforms.
  - Reuse the pinned AOSP `media/java/android/media/MediaDataSource.java` unchanged; add its path to the frameworks-base sparse checkout and curate it in `tools/java-srcs.py`.
  - Add an AOSP-compatible `android.media.MediaExtractor` host adapter backed by FFmpeg `libavformat`, rather than importing unavailable Stagefright, Binder, CAS/DRM, `media_jni`, or Android media-service infrastructure.
  - Connect FFmpeg AVIO read and seek callbacks to `MediaDataSource.readAt()` and `getSize()` while preserving AOSP callback serialization, cached-size behavior, `-1` EOF handling, close behavior, and permanent failure after a callback exception.
  - Implement the Signal waveform surface first: `setDataSource(MediaDataSource)`, `getTrackCount()`, `getTrackFormat()`, `selectTrack()`, `readSampleData()`, `getSampleTime()`, `advance()`, and `release()`.
  - Populate `MediaFormat` with Android MIME, duration, sample rate, channel count, maximum input size, and codec-specific data so the existing FFmpeg-backed `MediaCodec` can decode extracted packets.
  - Emit structured unsupported diagnostics for extractor operations that still require DRM/CAS or unavailable network-service behavior.
  - Add module tests using an in-memory `MediaDataSource`, including EOF, callback failure, malformed input, close, track metadata, sample ordering, and timestamps.
  - Run the Signal APK through the existing `tools/shot.sh`/`azk-test` harness and require successful waveform generation, correct UI output, and an empty `diagnostics.runtime.issues` list.
