# WebView video fixture

`assets/index.html` is packaged by aapt2. Its default video is an embedded,
locally generated, silent two-second VP8 WebM (320×180, 15 fps, RGB `#3ab16f`).
It exercises the selected Chromium provider's ordinary media-player path with
no camera or network. The headless test requires frame callbacks, actual playing
pixels, and a retained visible frame after pause. The check requires at least
10,000 green video pixels, then compares every green baseline pixel after pause
with a two-code-value tolerance. Black/red frames and capture-path color shifts
fail; the blue heading also has an independent color check.

The data URI was generated with FFmpeg 9.0:

```sh
ffmpeg -f lavfi -i 'color=c=0x3ab16f:s=320x180:r=15:d=2,colorspace=ispace=smpte170m:iprimaries=bt709:itrc=srgb:irange=tv:all=smpte170m:format=yuv420p' -an \
  -c:v libvpx -threads 1 -b:v 40k -pix_fmt yuv420p \
  -color_primaries smpte170m -color_trc smpte170m -colorspace smpte170m -color_range tv \
  -fflags +bitexact -flags:v +bitexact -map_metadata -1 green.webm
```

The colorspace filter converts the sRGB color into the BT.601/SMPTE 170M space
reported by this provider's VP8 decoder. Merely tagging sRGB samples changes
their displayed color and is not an equivalent fixture.

This generates test data; it does not add a runtime media backend.
WebM SHA-256: `e145643e957fd13162d16cbc4aeaec8f7e0f84d6273ff086c7f7d38851515adc`.

`zig build -j10 webview-overlay-test` uses the H.264 button and the same playing
and paused pixel checks. Its source is 1280×720, displayed at 320×180: the pinned
Chromium decoder selector prefers software decoding below 360 pixels high.
The fixture uses stock WebView TracingController to capture platform decoding,
overlay creation, buffer transactions and acknowledgements. The test rejects
traces that merely attempt overlay selection without creating/updating an overlay.
Artifacts, including `rendering-trace.json`, are retained under
`zig-out/test-results/webview-overlay/`. Set `AZK_WEBVIEW_TRACE_OUT` to capture
the same trace manually with `webview-ui-test`.

The silent H.264 MP4 was generated with FFmpeg 9.0:

```sh
ffmpeg -nostdin -hide_banner -loglevel error -f lavfi \
  -i 'color=c=0x3ab16f:s=1280x720:r=15:d=2,colorspace=ispace=smpte170m:iprimaries=bt709:itrc=srgb:irange=tv:all=bt709:format=yuv420p' \
  -an -c:v libx264 -threads 1 -profile:v baseline -pix_fmt yuv420p \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 -color_range tv \
  -movflags +faststart -fflags +bitexact -flags:v +bitexact -map_metadata -1 green.mp4
```

MP4 SHA-256: `c4bc34f18f476e3c66acc69be01e93d1c1c8886be54e72a2fabe237cd5471190`.

The I420 and canvas buttons are optional capture-stream diagnostics. Set
`AZK_WEBVIEW_VIDEO_SOURCE=i420` or `canvas` when invoking the fixture manually.
These modes are outside the acceptance matrix; the encoded VP8 and H.264 paths
above are the maintained playback regressions.

Both graphical build targets use temporary Android data and cache directories.
The overlay target enables `WebViewSurfaceControl` through the bundled provider's
original developer UI and service, then waits for its content-provider readback
before initializing WebView. This test-only setting cannot persist into user
application data; normal WebView runs retain upstream defaults.
