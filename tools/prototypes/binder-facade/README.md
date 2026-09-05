# C++ Binder facade prototype

This standalone experiment tests source-built AOSP graphics code over the Zig
Binder runtime. It is not linked into the application. The production runtime
sources and the stock-client migration worktree are not modified.

The experiment retains Zig Parcel/object/transport code, supplies a limited C++
API, and directly compiles the pinned AOSP RefBase, StrongPointer, String16,
String8, IInterface, GraphicBuffer and Fence implementations. The Bp/Bn interface
is generated from the real `android.gui.IFpsListener.aidl`. There are no copied
or hand-written generated interface classes in this directory.

## Run

Run from the repository root with the full pinned `android-16.0.0_r1` checkout
available. The example uses the existing checkout in the main worktree, read-only.
The extracted application package alone may omit the native sources needed here.

```sh
prototype_dir=/tmp/azk-binder-facade-v2
prototype_aosp=/home/vlad/proj/androlinzberg/aosp
prototype_jobs=$(( $(nproc) > 2 ? $(nproc) - 2 : 1 ))
if [ "$prototype_jobs" -gt 6 ]; then prototype_jobs=6; fi

python3 tools/prototypes/binder-facade/prepare-runtime.py \
  "$prototype_dir/runtime" "$prototype_aosp"
nix develop ./artzig -c bash -c \
  'cd "$1" && zig build -j"$2" && zig build -j"$2" ndk-test' \
  _ "$prototype_dir/runtime" "$prototype_jobs"
nix develop ./artzig -c python3 tools/prototypes/binder-facade/build-probe.py \
  "$prototype_dir" "$prototype_aosp"
python3 tools/prototypes/binder-facade/run.py "$prototype_dir"
```

The GPU subtest requires access to `/dev/dri/card1` and support for dumb-buffer
allocation, PRIME DMA-BUF export, and syncobj sync-file export. Those capabilities
are available on the investigated host. The probe fails rather than silently
skipping this subtest. It does not modeset or take over the display.

`build-probe.py` uses the development shell's C/C++ compiler and libdrm, plus the
pinned AIDL compiler. C++23 makes GCC expose the C atomic names expected by the
pinned `cutils/atomic.h`. `compat.h` adapts Android annotations/assert declarations
for the host compiler. The generated interface files stay in the output directory.

`run.py` starts an isolated broker, native service, and native client. It gives
only the service the test system cookie; the client has an application UID.
The harness closes the service's stdin at the death-test checkpoint and waits
for its exit. Child output, return codes and binary hashes are saved to
`run/results.json`; stderr is retained in `run/{broker,server,client}.log`.

## Checks

- Real AOSP `RefBase/sp/wp`: live weak promotion, destruction after the last
  local strong reference, and identity-preserving local `interface_cast`.
- Actual generated `IFpsListener` proxy/stub across processes: three ordered
  oneway callbacks, caller UID and the Android oneway PID-zero convention.
- Nested synchronous callback on the originating blocked thread, including a
  further call back to the service and restoration of caller identity.
- Canonical live C++ Binder wrappers for repeated lookup and a callback Binder
  returned to its owner, without an application keepalive in the reply path.
- Rejection of a wrong interface token and exact `UNKNOWN_TRANSACTION` status.
- Pinned Fence flatten/unflatten using a pipe to verify payload and FD ownership.
- Pinned GraphicBuffer and Fence flatten/unflatten through the broker using a
  real DMA-BUF, a real signaled sync-file FD, and `NO_FENCE`. Checks dimensions,
  stride, format, 64-bit usage, generation, buffer ID, handle metadata, trailing
  Parcel data, and shared pixel contents changed by the service. Original local
  buffer/fence objects and the DRM handle are released before the transaction.
- Two C++ death observers, with one unlinked before service exit; only the
  remaining observer is notified and receives the expected Binder identity.
- Existing `ndk_test.zig` against the isolated library and broker.

## Experimental runtime changes

`prepare-runtime.py` copies this worktree's `src/binder` into the output directory,
then records its changes as `runtime/runtime.patch`. The isolated Zig build
contains:

1. Outgoing Parcel entries retain native Binder strong references, including
   range copies, and release them on destruction/reset. This fixes the prior
   generated NDK reply failure caused by releasing its last remote Binder before
   `sendReply` forwards it.
2. Unlinking an NDK death recipient sends the broker's per-object unsubscribe
   only when no other recipient remains for that object. The unmodified runtime
   caused the second observer to miss service death.
3. One private `AZKPrototype_Parcel_clone` export copies data and object/FD entries
   for the C++ transaction adapter. It is not added to the public NDK API.

These are bounded prototype changes, not a complete production ownership or
concurrency fix. Incoming unread/repeated Binder reads, overwritten object
entries, unsent/failed local exports, concurrent death link/unlink, and teardown
races still need design and regression coverage before upstreaming the fixes.

## Deliberate limits

The C++ API is the subset used by these checks. `IBinder`, `BBinder`, `BpBinder`,
`BpRefBase`, Parcel and Status are experimental adapters; the interface templates
and reference-counting implementation are real pinned AOSP code. Remote descriptor
queries, weak resurrection, complete status/exception behavior, pool/polling
configuration, permission/stability enforcement and the full Binder control
transaction set are not implemented. Incoming C++ stub flags are not propagated
by the current NDK callback signature. The observer adapter itself is not ready
for concurrent mutation or callbacks that destroy their subscriptions.

The GraphicBuffer mapper is a host seam that clones opaque native handles. The
native handle contains a genuine DMA-BUF plus probe metadata; it is not an Android
gralloc driver's handle format. Allocation and locking through the Android
allocator/mapper are explicitly unsupported. Pixel memory is accessed through
DMA-BUF mmap with CPU-access synchronization. Diagnostic dependency monitoring,
tracing and stack traces are excluded from this experiment.

The real sync file is already signaled. GPU-submitted work, pending-fence
scheduling, gralloc/EGL import, BufferQueue, SurfaceFlinger and visible rendering
remain untested. This is a transport/object/serialization prototype, not a full
libgui build or application integration. No APK tests are claimed.

## Use the production facade

The optional graphics probe can also link the installed production libraries.
This path reuses only the opaque graphics-handle adapters and unmodified AOSP
GraphicBuffer/Fence sources; it does not compile the prototype Binder, Parcel,
Status, RefBase or string implementations. Use the same Zig/libc++ toolchain as
the application:

```sh
nix develop ./artzig -c zig build -j6
nix develop ./artzig -c python3 tools/prototypes/binder-facade/build-production-probe.py \
  /tmp/facade-production-graphics /home/vlad/proj/androlinzberg/aosp \
  --binder zig-out/lib/art/libbinder.so --ndk zig-out/lib/art/libbinder_ndk.so
python3 tools/binder-facade-test.py \
  --output /tmp/facade-production-graphics \
  --probe /tmp/facade-production-graphics/probe \
  --library zig-out/lib/art/libbinder.so --library zig-out/lib/art/libbinder_ndk.so \
  --broker zig-out/bin/azk-binder-broker
```

This variant passed on the investigated host: real DMA-BUF pixels were changed
by the service and returned, together with a real sync_file, NO_FENCE and buffer
metadata. `run/results.json` records the tested production library hashes.
The test mapper is still not a production gralloc or EGL import implementation.
