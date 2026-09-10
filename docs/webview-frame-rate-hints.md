# Bundled WebView frame-rate hint defect

Status (2026-09-10): resolved by upgrading the bundled provider to the unchanged
official AOSP Chromium 140.0.7339.15 APK. The provider is independently pinned;
the framework reference remains Android 16 r1. See [packaging and provenance](../aosp/README.md#webview-provider).
The diagnosis below describes the previously bundled 133.0.6943.137 APK.

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

The adopted [140 implementation](https://chromium.googlesource.com/chromium/src/+/refs/tags/140.0.7339.15/android_webview/browser/gfx/overlay_processor_webview.cc)
checks for zero seconds before calculating the reciprocal and supplies the
unspecified frame rate of zero. Framework invalid-rate validation remains intact.

Upstream 140 disables WebView SurfaceControl overlays by default. Production uses
those defaults. The overlay regression enables only `WebViewSurfaceControl`
through Chromium's original developer UI, service and content-provider readback
before initializing WebView. Its Android data and cache are private to that
test and removed afterward. Frame-rate hints remain enabled, and native overlay,
transaction acknowledgement and playing/paused pixel assertions remain required.

Validation passes with the adopted APK: original WebView Zygote preload and
isolated launch, renderer lifetime, HTML/JavaScript/cookies/input, graphical media,
overlay callbacks/pixels, all 28 APK cases (88 clean actions), JVM, system, native,
EGL and build audits. The two graphical WebView cases capture 15 additional clean
actions, including the original developer UI setup. The gate is recorded in
`/tmp/stock-provider-140-gate-1.log` (exit 0); provider packaging reproducibility
and isolated probes are retained under `/tmp/stock-provider-update/`.

The final fresh-worktree acceptance at `34691109` also passes with this APK:
all 28 APK cases and 323 captured UI actions are clean, including the optional
overlay path with its original native assertions. See the
[complete acceptance record](stock-client-completion.md#final-acceptance-with-all-three-packages-2026-09-10).
