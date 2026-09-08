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
        (4, "-icu-compat-use-createCanonical-String-in-InputDevice.patch"),
        (5, "-SystemFonts-env-overridable-font-config-paths-for-ho.patch"),
        (6, "-androidfw-replace-CombinedIterator-merge-in-Theme-Ap.patch"),
        (7, "-host-fixes-for-app-playback-static-Display-null-Disp.patch"),
        (8, "-Surface-no-op-setFrameRate-on-artzig-fake-released-s.patch"),
        (9, "-add-direct-Wayland-EGL-presentation-to-host-HWUI.patch"),
        (11, "-CursorWindow-always-parcel-as-shared-memory-on-the-a.patch"),
        (12, "-AssetManager-env-overridable-framework-res.apk-path-.patch"),
        (13, "-host-hwui-view-uncommitted-working-tree-changes-pres.patch"),
        (14, "-fixup-drop-duplicated-Bitmap-compress-declaration-fr.patch"),
        (16, "-MessageQueue-host-pump-slice-for-the-Wayland-owner-l.patch"),
        (17, "-Process-broker-assigned-uid-no-zygote-on-the-host-ar.patch"),
        (19, "-Environment-session-data-root-from-ARTZIG_DATA_DIR-o.patch"),
        (20, "-ClassLoaderFactory-the-hosted-APK-class-loader-on-th.patch"),
        (21, "-ApplicationLoaders-the-system-class-loader-is-the-ba.patch"),
        (22, "-AlarmManager-time-zone-validation-without-ZoneInfoDb.patch"),
        (23, "-resources-make-early-RRO-constraint-flag-read-only.patch"),
        (24, "-WindowOrganizer-tolerate-host-SurfaceControl-apply-t.patch"),
        (26, "-StorageManager-cut-unavailable-vold-OBB-and-AppFuse-.patch"),
        (27, "-DevicePolicyManager-cut-unavailable-host-backends.patch"),
        (28, "-GLES-release-original-heap-buffer-array-pointers.patch"),
        (29, "-HWUI-preserve-hardware-buffer-RGBA-byte-order.patch"),
        (30, "-OverlayProperties-marshal-LUT-sampling-keys.patch"),
        (31, "-HWUI-reject-pixel-copy-without-native-window.patch"),
        (32, "-legacy-GLES-retain-JNI-array-bases-and-typed-buffer-bounds.patch"),
        (33, "-camera-metadata-use-hosted-vendor-service-boundary.patch"),
        (34, "-permission-service-use-hosted-policy-at-startup.patch"),
        (35, "-init-thread-pool-use-hosted-failure-diagnostics.patch"),
        (36, "-media-metrics-use-portable-JNI-registration-table.patch"),
    )
)

packager.SOURCE_MANIFEST = HERE / "androlinzberg-files.txt"
packager.PACKAGE_NAME = "androlinzberg"
packager.PATCH_SERIES = framework_patches + (
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
        "system/libbase",
        patch_root / "system" / "libbase" / "0001-host-prop_info_cmp-operator-must-be-const-libc-std-s.patch",
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
        "frameworks/minikin",
        patch_root / "minikin" / "0001-Hyphenator-always-use-the-C-implementation-on-host-n.patch",
    ),
    (
        "packages/modules/Wifi",
        patch_root
        / "packages-modules-Wifi"
        / "0001-WifiSsid-keep-legacy-unknown-SSID-without-WifiManage.patch",
    ),
)


if __name__ == "__main__":
    try:
        raise SystemExit(packager.main())
    except (packager.PackagingError, subprocess.CalledProcessError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
