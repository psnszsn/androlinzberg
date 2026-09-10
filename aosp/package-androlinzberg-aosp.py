#!/usr/bin/env python3
"""Build the patched, pruned AOSP archive consumed by Androlinzberg."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys


HERE = Path(__file__).resolve().parent
IMPLEMENTATION = HERE / "package-artzig-aosp.py"
SPEC = importlib.util.spec_from_file_location("aosp_packager", IMPLEMENTATION)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load packager implementation: {IMPLEMENTATION}")
packager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packager)

patch_root = packager.PROJECT_ROOT / "artzig" / "patches"
framework_patches = tuple(
    ("frameworks/base", patch_root / "frameworks-base" / f"{number:04d}{suffix}")
    for number, suffix in (
        (3, "-jni-honor-CriticalNative-on-host-under-real-ART-ARTZ.patch"),
        (6, "-androidfw-replace-CombinedIterator-merge-in-Theme-Ap.patch"),
        (7, "-host-fixes-for-app-playback-static-Display-null-Disp.patch"),
        (9, "-add-direct-Wayland-EGL-presentation-to-host-HWUI.patch"),
        (11, "-CursorWindow-always-parcel-as-shared-memory-on-the-a.patch"),
        (13, "-host-hwui-view-uncommitted-working-tree-changes-pres.patch"),
        (14, "-fixup-drop-duplicated-Bitmap-compress-declaration-fr.patch"),
        (17, "-Process-broker-assigned-uid-no-zygote-on-the-host-ar.patch"),
        (23, "-resources-make-early-RRO-constraint-flag-read-only.patch"),
        (28, "-GLES-release-original-heap-buffer-array-pointers.patch"),
        (29, "-HWUI-preserve-hardware-buffer-RGBA-byte-order.patch"),
        (30, "-OverlayProperties-marshal-LUT-sampling-keys.patch"),
        (31, "-HWUI-reject-pixel-copy-without-native-window.patch"),
        (32, "-legacy-GLES-retain-JNI-array-bases-and-typed-buffer-bounds.patch"),
        (33, "-camera-metadata-use-hosted-vendor-service-boundary.patch"),
        (34, "-permission-service-use-hosted-policy-at-startup.patch"),
        (35, "-init-thread-pool-use-hosted-failure-diagnostics.patch"),
        (36, "-media-metrics-use-portable-JNI-registration-table.patch"),
        (37, "-HWUI-run-WebView-functors-without-SurfaceFlinger-overlays.patch"),
        (30, "-Surface-use-stock-client-JNI-on-host.patch"),
        (31, "-ReliableSurface-use-stock-buffer-recovery-on-host.patch"),
        (32, "-RenderThread-restore-stock-vsync-scheduling.patch"),
        (33, "-UriMetricsHelper-allow-absent-host-statistics-service.patch"),
        (34, "-PackageUserState-match-nullable-splash-theme-contract.patch"),
        (35, "-WM-route-process-helpers-through-host-authority.patch"),
        (37, "-ImageWriter-use-host-surface-query-adapter.patch"),
        (38, "-ProtoLog-use-stock-host-logging-backend.patch"),
        (39, "-WM-use-compositor-input-service-boundary.patch"),
        (40, "-PendingIntent-use-host-session-authorities.patch"),
        (41, "-WM-match-host-SF-capability-properties.patch"),
        (43, "-ATMS-use-the-session-user-authority.patch"),
        (44, "-HWUI-initialize-host-context-for-offscreen-work.patch"),
        (45, "-HWUI-use-host-EGL-fences-for-stock-texture-layers.patch"),
        (46, "-WM-reuse-stock-session-diagnostics.patch"),
        (47, "-Watchdog-use-host-session-diagnostics.patch"),
        (48, "-WM-use-policy-interface-and-optional-local-services.patch"),
        (49, "-ATMS-use-host-SDK-sandbox-capability.patch"),
        (50, "-ColorDisplayService-use-hosted-user-client.patch"),
        (51, "-SaferIntentUtils-use-host-receiver-metadata.patch"),
        (52, "-WallpaperCropper-use-host-image-record.patch"),
        (53, "-GLUtils-normalize-host-bitmap-upload-byte-order.patch"),
        (54, "-WM-bind-optional-services-to-hosted-authorities.patch"),
        (55, "-WM-release-FPS-callbacks-on-transport-failure.patch"),
        (56, "-DeviceStatePolicy-use-desktop-posture-provider.patch"),
        (57, "-Tunnel-listener-release-on-compositor-disconnect.patch"),
        (58, "-HWUI-record-hosted-texture-layers.patch"),
        (59, "-HWUI-initialize-layer-geometry.patch"),
        (60, "-HWUI-detach-host-worker-threads.patch"),
        (61, "-HWUI-process-hosted-texture-updates.patch"),
        (62, "-ATMS-complete-host-permission-operations.patch"),
        (63, "-WM-observe-host-task-presentation-after-layout.patch"),
        (64, "-Clipboard-connect-host-desktop-monitor.patch"),
        (65, "-WM-own-native-drag-lifecycle.patch"),
        (66, "-WM-transfer-stock-drag-to-native-presentation.patch"),
        (67, "-WM-present-local-drag-fallback-in-source-task.patch"),
        (68, "-HWUI-convert-host-GL-functor-origin.patch"),
        (69, "-HWUI-and-NDK-stock-SurfaceControl-on-host.patch"),
        (70, "-ToneGenerator-use-host-audio-volume-conversion.patch"),
        (71, "-AssetsProvider-resolve-platform-archive-below-client.patch"),
        (72, "-package-resolvers-use-captured-state-and-user-authority.patch"),
        (73, "-package-settings-diagnostics-without-pms-stub.patch"),
        (74, "-stock-package-archiver-with-hosted-authority.patch"),
    )
)

packager.SOURCE_MANIFEST = HERE / "androlinzberg-files.txt"
packager.PACKAGE_NAME = "androlinzberg"
packager.PATCH_SERIES = framework_patches + (
    ("frameworks/av", patch_root / "frameworks-av/0003-ToneGenerator-use-existing-host-audio-output.patch"),
    ("frameworks/av", patch_root / "frameworks-av/0002-NDK-image-reader-without-deprecated-HIDL-token-service.patch"),
    ("system/core/libcutils", patch_root / "system/libcutils/0001-use-stock-memfd-operations-for-host-ndk-shared-memory.patch"),
    ("bionic", patch_root / "bionic/0001-reuse-missing-fortify-entry-points-on-host.patch"),
    (
        "frameworks/av",
        patch_root / "frameworks-av/0001-register-host-linked-stock-extractors.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native/0001-namespace-native-binder-assertion-macro.patch",
    ),
    (
        "packages/providers/DownloadProvider",
        patch_root / "packages/providers/DownloadProvider/0001-classify-hosted-external-volume-paths.patch",
    ),
    (
        "packages/providers/DownloadProvider",
        patch_root / "packages/providers/DownloadProvider/0002-skip-scanning-unindexed-private-download-deletions.patch",
    ),
    (
        "packages/providers/DownloadProvider",
        patch_root / "packages/providers/DownloadProvider/0003-retain-download-inode-for-close-size-accounting.patch",
    ),
    (
        "packages/modules/AdServices",
        patch_root / "packages-modules-AdServices" / "0001-name-optional-verifier-keep-target.patch",
    ),
    (
        "system/core",
        patch_root / "system" / "core-host" / "0001-expose-host-thread-priority.patch",
    ),
    (
        "system/core",
        patch_root / "system" / "core-host" / "0002-expose-host-timed-mutex.patch",
    ),
    (
        "system/core",
        patch_root / "system" / "core-host" / "0003-host-looper-poll-state.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0001-libgui-host-binder-transport.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0011-libgui-retire-host-composer-clients-after-death.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0002-renderengine-host-egl.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0003-surfaceflinger-host-build.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0004-surfaceflinger-host-service-boundaries.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0005-inputdispatcher-host-crypto.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0006-surfacetexture-host-egl.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0007-nativewindow-link-stock-parcel-operations.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0008-inputdispatcher-host-task-focus.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0009-inputdispatcher-host-native-drag.patch",
    ),
    (
        "frameworks/native",
        patch_root / "frameworks-native" / "0010-inputdispatcher-host-drag-handoff.patch",
    ),
    (
        "system/libbase",
        patch_root / "system" / "libbase" / "0001-host-prop_info_cmp-operator-must-be-const-libc-std-s.patch",
    ),
    (
        "system/libbase",
        patch_root / "system" / "libbase-host" / "0001-share-native-properties-with-AZKB.patch",
    ),
    (
        "system/incremental_delivery",
        patch_root / "incremental_delivery" / "0001-map_ptr-complete-the-random-access-iterator-operator.patch",
    ),
    (
        "external/skia",
        patch_root / "external" / "skia" / "0001-linux-SkUserConfig-no-GIF-RAW-codecs-on-the-artzig-h.patch",
    ),
    (
        "external/skia",
        patch_root / "external" / "skia-host" / "0001-ahardwarebuffer-use-host-dmabuf-import.patch",
    ),
    (
        "external/skia",
        patch_root / "external" / "skia-host" / "0002-enable-stock-AHardwareBuffer-image-factories-on-host.patch",
    ),
    (
        "external/skia",
        patch_root / "external" / "skia-host" / "0003-use-2D-textures-for-host-DMA-BUF-images.patch",
    ),
    (
        "frameworks/minikin",
        patch_root / "minikin" / "0001-Hyphenator-always-use-the-C-implementation-on-host-n.patch",
    ),
)


if __name__ == "__main__":
    try:
        raise SystemExit(packager.main())
    except (packager.PackagingError, subprocess.CalledProcessError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
