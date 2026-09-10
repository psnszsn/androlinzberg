# Bundled WebView frame-rate hint defect

Status (2026-09-10): diagnosed, not repaired in the bundled Chromium
133.0.6943.137 APK. Framework source restoration does not fix this provider
defect. Later overlay runs pass, but the failing run remains evidence of an
intermittent stall.

The retained failing `webview-overlay-test` run reached its expected playing
pixels and completed all five UI actions without structured runtime issues.
Its separate native trace assertion failed: Chromium submitted an overlay
buffer transaction but never received its completion acknowledgement. The
application log reported `Transaction::setFrameRate failed - invalid frame rate inf`.
Pinned AOSP SurfaceComposerClient rejects that value and marks the transaction
invalid; the missing acknowledgement therefore has a concrete failure path.

The exact Chromium source explains how a valid unknown cadence can produce
this invalid hint:

- VideoRendererAlgorithm initializes its average duration to zero and retains
  an unknown duration when it lacks enough frames or duration metadata. Its
  render path explicitly handles that state. See the pinned
  [algorithm implementation](https://chromium.googlesource.com/chromium/src/+/refs/tags/133.0.6943.137/media/filters/video_renderer_algorithm.cc).
- VideoRendererImpl, VideoFrameCompositor and the compositor video-layer
  provider forward that duration. LayerTreeHostImpl accepts an engaged optional
  interval even when it is zero and publishes it as content cadence. See the
  pinned [layer-tree implementation](https://chromium.googlesource.com/chromium/src/+/refs/tags/133.0.6943.137/cc/trees/layer_tree_host_impl.cc).
- OverlayProcessorWebView converts the interval directly with `ToHz()`, without
  guarding zero, then sends the resulting rate to SurfaceControl. See the
  pinned [overlay implementation](https://chromium.googlesource.com/chromium/src/+/refs/tags/133.0.6943.137/android_webview/browser/gfx/overlay_processor_webview.cc).
- Chromium 139.0.7258.66 explicitly converts a zero interval to the unspecified
  frame rate of zero. See its
  [corrected implementation](https://chromium.googlesource.com/chromium/src/+/refs/tags/139.0.7258.66/android_webview/browser/gfx/overlay_processor_webview.cc).

This trace establishes a provider defect that does not require invalid host
timestamps. The historical capture lacks the internal cadence value, so it
does not identify which unknown-duration transition triggered that specific
run. A passing repeat does not prove repair.

Preserved development evidence is in
`/tmp/stock-service-lifecycle-overlay-failure/`: the playing/content screenshots,
step records, application log and rendering trace. The failing gate is
`/tmp/stock-completion-service-lifecycle-gate.log`; that run also contained a
separate service lifecycle regression subsequently fixed in the service code.
The provider investigation and source copies are under `/tmp/stock-frame-interval/`.

The framework's invalid-rate validation must remain intact. The provider has a
supported developer flag for disabling frame-rate hints, but enabling it also
enables its developer mode and foreground notification. That is not a silent
production fix. A provider update needs its own pin and full preload, renderer,
HTML/input, media, overlay and APK validation.
