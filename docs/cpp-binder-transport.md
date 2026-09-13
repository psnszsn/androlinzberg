# C++ libbinder over the hosted Binder transport

Status: historical investigation of replacing native Binder internals. The
project subsequently implemented the hosted C++ facade. The current
[JNI migration plan](stock-binder-migration.md) retains that facade and AZKB.

Follow-up: [the Zig-backed C++ facade assessment](libbinder-facade-feasibility.md)
demonstrates another viable direction. The Parcel mismatch below prevents
generic conversion between encodings; it does not require abandoning the
hosted encoding when all participants use a compatible facade.

Reviewed against repository commit `cbd4362` and AOSP `android-16.0.0_r1`,
frameworks/native commit `2827a4a16b0340ecd07c2d5a6c89991799b362bb`.
The stock-client migration in the main worktree was still modifying shared
build inputs during this review. No shared checkout was patched or packaged.

## Conclusion

Reusing the pinned C++ `Parcel`, `IBinder`, `BBinder`, `BpBinder`, and generated
CPP-backend AIDL interfaces over AZKB is a credible direction. It is not a
linker-only change or a replacement of one `ioctl` call. The first dependency
is a common Parcel representation across Java, NDK, C++, and the broker's
service-manager implementation.

The recommended experiment retains the stock C++ object machinery and adapts
the `ProcessState`/`IPCThreadState` driver boundary through a narrow C ABI to
Zig. Keep Unix sockets, descriptor transport, broker routing, and host waiting
in Zig. This is a proposed design, not a demonstrated working libbinder port.

## Existing support and the incompatible representation

`src/binder/Client.zig` and `Server.zig` define AZKB version 4. The transport
already carries transaction bytes, Binder object entries, SCM_RIGHTS
descriptors, caller identity, nested-call routing IDs, and exact callee status
codes. The broker maintains process-local exports and global object IDs,
forwards objects between peers, and reports release and death. `ndk.zig` adds
proxy caches, per-target oneway queues, synchronous dispatch, and Java/NDK
object sharing.

However, the current Parcel implementation is not the native AOSP format:

- `ndk.zig:AParcel_writeStrongBinder` writes a four-byte presence placeholder
  plus an AZKB side-table entry.
- Pinned `Parcel.cpp:flattenBinder` writes a `flat_binder_object`, followed by
  the stability word from `finishFlattenBinder`. On the 64-bit target these
  occupy 24 + 4 bytes, including for a null Binder.
- `AParcel_writeParcelFileDescriptor` also uses a hosted placeholder. Stock
  NDK `parcel.cpp` writes the nullable parcelable prefix and delegates to
  `Parcel::writeDupParcelFileDescriptor`; raw FDs and parcelable FDs must not
  be conflated.
- Java `Parcel.mNativePtr` currently points to the Zig `AParcel`.
  `AospParcelAbi.cpp` returns a fabricated C++ Parcel pointer whose implemented
  methods consult thread-local Zig state. It is not an actual C++ object.
- `Broker.zig:smWriteServiceBinder`, `addService`, and the `ServiceWithMetadata`
  reply encoder understand the hosted layout too.

A send-time translator cannot generically expand or shrink object slots:
size-prefixed AIDL parcelables and application code can store byte positions
inside otherwise opaque payloads. For example, a parcelable containing a size,
a Binder, and an int has a hosted size of 12 bytes versus 36 with the stock
64-bit representation. The side table does not identify all enclosing size
fields that would need rewriting.

The pinned AIDL compiler was run on a temporary `BinderEnvelope` parcelable
with a nullable Binder and trailing int. Its generated C++ writer computes the
parcelable size from `dataPosition()`; the reader bounds-checks and seeks using
that size. This confirms the failure mechanism at the generated-code level.
It does not constitute a compiled or executed C++ Binder interoperability test.

## Preferred ownership model

Use one real C++ Parcel representation and one canonical Binder object model
within each process. Zig Java natives and NDK C exports should access these
through a small C++ adapter for AOSP object operations. The adapter may hold
`sp<IBinder>`, create a `BBinder` that calls a Zig/Java handler, and invoke real
Parcel methods. It must not implement socket routing or a second compositor.

This deliberately changes the internal representation behind the existing
Java and NDK interfaces. It requires migrating their ownership and conversion
code together; existing `AParcel`/`AIBinder` pointers cannot be reinterpreted as
C++ objects. NDK wrappers must distinguish owned parcels from borrowed views
of Java parcels, while sharing the underlying data and cursor as required.

The C++ symbol definitions in `AospParcelAbi.cpp` must be removed when the real
Parcel implementation is linked. Replace its JNI helper with access to a real
Parcel and retain CursorWindow cross-process regression coverage. Audit the
empty `src/native/binder/IServiceManager.h` include shadow as well. There must
be only one definition of the C++ Binder classes and their `RefBase` support
in the relevant process/linker domain.

A conceptual dependency graph is:

```text
Java Binder/Parcel natives and NDK C exports (Zig)
                  | C++ object adapter
                  v
stock C++ Parcel / BBinder / BpBinder / AIDL stubs
                  |
adapted ProcessState / IPCThreadState
                  | narrow C ABI
                  v
shared hosted transport runtime (Zig)
                  |
                AZKB broker
```

Factor reusable connection/routing state out of the current `ndk.zig` runtime
as needed. Do not add a second peer/object cache and assume language conversions
will preserve local identity. Java -> native -> Java round trips, local calls,
and forwarding a remote object back to its owner must retain their semantics.

## Proposed transport boundary

Pinned `IPCThreadState::writeTransactionData` already separates the Parcel's
data from its object-offset array. `talkWithDriver` exchanges `BC_*`/`BR_*`
commands, and `executeCommand` implements dispatch, caller identity save/restore,
object reference callbacks, replies, and thread-pool growth.

The first prototype should adapt that command boundary explicitly, rather than
globally intercept libc or emulate a `/dev/binder` device. A Zig adapter would
consume the supported outgoing commands and produce the corresponding incoming
commands, backed by AZKB and per-thread pending queues. This preserves more of
the pinned C++ dispatch behavior than replacing every public Binder method.

This is still a substantial semantic adapter. Required areas include:

| Area | Required mapping or decision |
| --- | --- |
| Process initialization | Replace driver open/version/mmap assumptions; expose a hosted readiness source for polling. Map C++ handle 0 to AZKB context object 1. |
| Handle table | Translate C++ process-local 32-bit handles to broker 64-bit IDs; maintain identity and safe lifetime, including objects returned to their owner. |
| Transactions | Preserve code, flags, status, routing ancestry, reply ownership, oneway ordering, and completion events. Establish whether AZKB needs additional acceptance/completion messages. |
| Incoming dispatch | Nested callbacks must run on the originating waiting thread when applicable. Normal calls go to the configured pool or polling thread. Do not run two competing dispatch systems. |
| Reference lifetime | Map acquire/release and weak proxy bookkeeping to delivery counts and the export release acknowledgement. Hold references for unread parcels and queued work. Test last-release versus in-flight export races. |
| Death | Deliver death and unlink completion safely, including disconnect while calls, callbacks, or references are pending. |
| Parcel buffers | Retain incoming bytes and offsets until the receiving Parcel releases them through `BC_FREE_BUFFER`; never reference a reused socket receive buffer. |
| Descriptors | Define ownership at every handoff. Zig-owned descriptors use `std.Io`; ownership transferred into stock Parcel is released by stock Parcel. Error paths must neither leak nor double-close. |
| Identity | Populate broker-authenticated caller UID/PID; share calling context across all language APIs, including local calls and clear/restore identity. |
| Policy features | Inventory priority inheritance, requesting SID, freezing, transaction flags, and driver feature queries. Implement required behavior or expose an explicit unsupported capability. |

`IPCThreadState::clearCaller` uses host `getuid()` in stock code. That is not
the broker-assigned Android UID for an application. Incoming identity alone is
therefore insufficient; self identity and `clearCallingIdentity` also need the
host seam. Independent Zig and C++ thread-local caller identities would produce
incorrect permission decisions across language calls.

The existing NDK pool functions are not a complete implementation of the C++
pool contract: `ABinderProcess_setThreadPoolMaxThreadCount` currently ignores
the requested count, and `joinThreadPool` parks while other workers dispatch.
Validate pool sizing, polling, shutdown, and nested-call behavior explicitly.

At the wire boundary, preserve payload lengths and object offsets. Normalize
native pointers/handles into the side table without transmitting process
pointers, then reconstruct destination-local object records on receipt. Leave
ordinary payload bytes and Binder stability metadata intact. Validate offset
bounds, object sizes, supported types, ordering, descriptor ordinals, and
overlap before exposing a received Parcel to C++.

The hosted and stock payload formats should not coexist without negotiation.
A coordinated AZKB version bump is the straightforward approach: update the
broker's service-manager encoders/decoders and all participating clients in
the same migration. The broker's general routing machinery can remain
independent of application parcelable schemas.

## Alternatives considered

**AOSP Binder RPC over sockets.** The pinned source supports `RpcSession` and
`RpcServer`, including builds without the kernel Binder driver. This is a real
alternative architecture, but not an AZKB socket backend. It has its own wire
protocol and object representation. `RpcState::onBinderLeaving` rejects a
proxy from an unrelated RPC session or a kernel Binder proxy. Stock
`IPCThreadState` calling-identity APIs are also guarded in RPC dispatch
contexts. Adopting RPC would require resolving broker-wide forwarding,
identity, service discovery, and existing Java/NDK interoperation, rather than
merely connecting an RPC socket to the broker.

**Implement C++-looking proxy wrappers over current AIBinder.** Potentially
useful for source-built libgui when extended into a compatible C++ API backed
by the same hosted encoding at both ends. Simple proxy wrappers alone do not
satisfy the required Parcel ownership, Bp/Bn, ProcessState, or IPCThreadState
behavior. The follow-up facade assessment records an executed partial probe,
the remaining API requirements, and a concrete runtime lifetime failure.

**Rewrite the public C++ Binder API in repository-owned code.** Avoid: this
duplicates the pinned object, Parcel, callback, and failure semantics. The
command adapter should be judged against a narrower direct transport adaptation
after the reference/pool prototype, rather than committing to a broad rewrite.

## Build and integration dependencies

The selected AOSP archive includes Binder headers and AIDL definitions, but
does not currently select/build the full native Binder implementation. Derive
the source closure from pinned `libs/binder/Android.bp`: core objects, process
and thread state, service-manager support, and their generated dependencies.
Existing libutils support already supplies RefBase, String16/String8, and
related utilities; reuse it rather than linking a second copy.

`build/generators.zig:aidlCpp` currently generates a fixed set of input-related
headers and discards generated source paths. Extend the declarative generator
inputs and build-consumed outputs for actual Binder interface implementations;
do not hand-write their proxies/stubs. Account for Binder aconfig flags too.

Host compilation also needs an explicit compatibility choice:
`Parcel.cpp` emits `UNKN` as its interface header on a normal non-Android host,
whereas our clients and broker use `SYST`. The pinned service-manager code also
has host-specific paths. Do not assume a generic host libbinder build has the
right behavior, or globally pretend the process is bionic to fix one header.

Keep the current application runtime intact while building an isolated native
probe. Production integration touches shared generators, source manifests,
`build.zig.zon`, `ndk.zig`, framework JNI, and the native island. Coordinate that
cutover with the ongoing stock-client migration. Do not package the shared AOSP
checkout concurrently with another session.

## Implementation gates

1. **Parcel conformance probe.** Build real pinned C++ Parcel and generated
   CPP/NDK AIDL in an isolated target. Exercise null and non-null Binder fields,
   stability words, raw/parcelable FDs, nested size-prefixed parcelables,
   `appendFrom`, cursor sharing, and a pinned native-handle/Flattenable path.
   An int-only echo is not adequate evidence.
2. **Native broker transport probe.** Run two C++ processes through the actual
   broker: service lookup/registration, exact error returns, callbacks and
   same-thread nesting, per-object oneway order, descriptor round trips,
   object return-to-owner, reference release, and death. Add a third peer for
   forwarding identity. Validate polling and bounded-pool progress.
3. **Mixed-language cutover.** Move Java/NDK ownership to real C++ objects and
   adopt the common payload format. Exercise Java/C++/NDK calls in both
   directions, including local identity, nested calls across language changes,
   unread/discarded parcels, and CursorWindow FD ownership. Restart every peer
   for the protocol cutover.
4. **Graphics contract probe.** Only after the above, compile actual pinned
   graphics interface types and test buffer/native-handle plus fence transfer,
   completion callbacks, and destruction. This establishes the Binder
   prerequisites for libgui; it does not claim SurfaceFlinger is brought up.

Run the existing broker/native/Binder/system/JVM tests for their affected
modules, and the required APK launch/full integration suite for runtime/JNI
integration. Follow the repository Zig job limit and retained-artifact rules.

The review executed only the AIDL generation experiment and source inspection.
No C++ libbinder target, broker interoperability prototype, or APK regression
suite was built or run as part of this investigation.
