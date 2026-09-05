> Superseded by [the hosted facade implementation](libbinder-facade-implementation.md). The chosen implementation retains Zig Parcel and Binder semantics.

# Stock native Binder over AZKB: implementation plan

Status: planned; no implementation stages completed.

This plan follows [the C++ Binder investigation](cpp-binder-transport.md).
It describes the AOSP-native replacement option. A subsequent
[facade feasibility assessment](libbinder-facade-feasibility.md) evaluates
retaining the Zig implementation instead; this plan is not a prerequisite
established by that assessment.
Its source baseline is repository commit `cbd4362` and AOSP
`android-16.0.0_r1`. Refresh the repository baseline before implementation;
the stock-client migration is running in another worktree.

## Goal and scope

Replace the custom Zig Parcel serialization and Binder object semantics with
the pinned AOSP C++ implementations. Retain the AZKB socket broker and Zig
host transport. Java and native callers must use the same underlying Parcel,
object identity, calling context, and dispatch system within each process.

At completion:

- Stock C++ `Parcel`, `IBinder`, `BBinder`, `BpBinder`, `binder::Status`, and
  CPP-backend AIDL interfaces operate over AZKB.
- Framework JNI entry points remain in Zig. NDK C exports remain available
  under the existing soname and forward object/Parcel operations through an
  AOSP C++ adapter; their ABI compatibility is checked against the pin.
- Zig owns the broker, framing, routing, host waiting, and socket/descriptor
  transport. AOSP owns Parcel storage and serialization, C++ object semantics,
  and the reused dispatch machinery.
- The hosted generic Parcel implementation and fabricated C++ Parcel ABI are
  removed, together with superseded proxy/refcount/dispatch implementations.
- Existing Java, NDK, service, and APK behavior continues to pass its tests.

This establishes native Binder support for a subsequent libgui migration.
It does not bring up WMS, ATMS, SurfaceFlinger, or multiple Wayland windows.
It does not replace AZKB with kernel Binder or AOSP Binder RPC.

## Architecture and fixed decisions

```text
Java framework natives (Zig)      NDK C exports (Zig)
                 \                /
                  AOSP object adapter (C++)
                            |
           real Parcel / BBinder / BpBinder / Status
                            |
              ProcessState / IPCThreadState
                            | explicit hosted C ABI
                   AZKB peer transport (Zig)
                            |
                      broker (Zig)
```

1. **Reuse source.** Compile the pinned AOSP implementation. Keep host changes
   as small, documented patches at the process/thread transport and identity
   boundary. Do not copy public Binder classes into new implementations.
2. **Use the native Parcel layout throughout.** Binder/FD side tables carry
   routing metadata; rewriting those records must preserve payload length and
   byte offsets. Never expand the old four-byte placeholders at send time.
3. **Use one object domain per process.** Java and NDK wrapper objects may own
   references to canonical C++ objects. They must not establish independent
   remote proxy caches, transports, or calling-identity state.
4. **Keep platform behavior in Zig.** Repository-owned C++ is restricted to
   manipulating AOSP C++ types and calling into/out of that layer. Zig I/O uses
   explicit `std.Io` and `std.Io.File` ownership. Directly reused AOSP code keeps
   its original language.
5. **Make the protocol cutover explicit.** Use a new AZKB protocol version
   (v5 if v4 is still current at implementation). Reject mismatched peers at
   handshake. A session never mixes old and new Parcel encodings.
6. **Keep production unchanged until the candidate passes.** Build candidate
   artifacts separately. The final production switch is coordinated across
   broker, native libraries, JNI, launcher, and generated interface consumers.

Prefer a dependency arrangement equivalent to a single shared Zig transport
library, a single shared C++ Binder library, and the existing NDK/JNI facade.
The transport must not depend back on the facade. Resolve C++ callbacks through
explicit registrations or the native command interface, not a circular library
dependency. Reuse one libutils/RefBase implementation across Binder and the
native island; do not statically duplicate it into competing shared libraries.
Finalize names and linkage in stage 1 based on the actual loader/build graph.

## Stage 0: record the baseline and compatibility contract

Work:

- Rebase the implementation branch onto an agreed migration checkpoint and
  record its commit, archive hashes, APK hashes, and working test environment.
- Inventory `ndk.zig` exports and JNI registrations, the native island's Binder
  symbols, `src/bionic_translation.cfg`, and all broker Parcel encoders.
- Trace the pinned NDK and Java JNI implementations as behavioral references
  for owned/borrowed parcels, Binder conversions, weak references, status and
  exception headers, interface tokens, and calling-identity operations.
- List every driver operation reachable from the selected libbinder closure:
  initialization, command exchange, polling, thread exit, reference management,
  death, scheduling, security context, and feature queries. Assign each an
  implementation, tested absent-capability policy, or explicit blocker.
- Run the existing validation sequence below to establish a baseline. Record
  existing failures separately; they must not obscure new failures.

Exit: an export/registration inventory and driver-operation matrix exist in
this document or its supporting documentation, with an actual baseline test
result. No behavior has switched.

## Stage 1: build the real native implementation in isolation

Primary files: `aosp/androlinzberg-files.txt`,
`aosp/package-androlinzberg-aosp.py`, `build.zig.zon`,
`build/generator_sources.zig`, `build/generators.zig`, `build/island.zig`,
`build/gen/island_sources.zig`, and a new native Binder build module.

Work:

- Derive the native source closure from pinned `libs/binder/Android.bp`,
  including core objects, process/thread state, service-manager interfaces,
  generated flags, headers, and support libraries. Record unavailable module
  trees rather than substituting placeholder classes or functions.
- Extend generator declarations to expose generated CPP/NDK AIDL source and
  header outputs, not only today's fixed input-enum headers. Generate a shared
  test contract with a Binder field, FD field, nested parcelable, and trailing
  data for use across Java, CPP, and NDK backends. New JVM test code is Kotlin.
- Build a separate candidate library and test executable without linking
  `AospParcelAbi.cpp` or the empty hosted `IServiceManager.h` into that target.
  The existing application still uses its old libraries at this stage.
- Establish a hosted initialization boundary instead of letting the library
  open or mmap `/dev/binder`. Keep unimplemented candidate paths visibly
  unsupported; compilation is not evidence that they work.
- Select the pinned Android-system Parcel token header (`SYST`) explicitly
  for this host adaptation. Avoid changing unrelated platform behavior by
  globally defining Android/bionic macros merely to select one branch.
- Verify symbol ownership, C++ runtime compatibility, and library dependency
  direction. Native Binder must not get a second RefBase/object universe.

Exit: the candidate links against real pinned Binder classes and generated
interfaces; basic Parcel storage/primitive tests pass without a kernel Binder
device. The final object-bearing Parcel gate remains in stage 3.

Every archive manifest change must be packaged and accompanied by the actual
new `.aosp` hash in the same commit. Serialize packager access to the shared
checkout. All AOSP changes need source notes explaining the host seam.

## Stage 2: implement the native transport and object lifetime boundary

Primary files: `src/binder/Connection.zig`, `Client.zig`, `Server.zig`,
`Broker.zig`, transport code extracted from `ndk.zig`, and the pinned
`ProcessState`/`IPCThreadState` host patches.

Work:

- Start with the explicit `BC_*`/`BR_*` command boundary. Map transactions,
  replies, reference operations, buffer release, death notifications, thread
  registration, polling, and completion events onto the actual AZKB broker.
  Do not install a global `ioctl` interceptor or implement a fake device.
- Assign process-local C++ handles to broker IDs, reserving handle 0 for AZKB
  context object 1. Retain local-object cookies only within the owning process.
  Sanitize native pointer fields before sending payloads.
- Use broker-authenticated UID/PID for incoming calls and the assigned Android
  self UID when clearing identity. Preserve stock save/restore ordering,
  including nested calls, work-source state, and oneway PID behavior.
- Translate received parcels into owned data/object arrays with explicit
  release callbacks. Hold references for objects in queued and unread parcels;
  a proxy being read must not be the only mechanism retaining an object.
- Map broker delivery counts and release acknowledgements to the native
  strong/weak lifecycle. Prove last-release versus in-flight delivery behavior
  without retaining strong references forever to simplify weak references.
- Keep nested synchronous callbacks on the correct waiting thread. Stock
  dispatch handles normal calls; the transport demultiplexes messages into
  appropriate queues. Do not retain the old NDK worker pool alongside it.
- Define oneway acceptance/completion, queue limits, and wakeup behavior.
  Extend AZKB explicitly if the existing messages cannot express the required
  completion or lifetime guarantee.
- Validate object offsets against actual payload length, object record size,
  alignment, order, overlap, supported type, and descriptor count before use.
  Return exact native status values separately from service exceptions.
- Implement disconnect/thread-exit cleanup and test lost peers while blocked.
  SID/freezing/scheduler features must follow the stage-0 capability matrix;
  never fabricate a security context or silently accept a required operation.

During development, compile a candidate broker/client variant using the new
protocol, with a separate session/socket and installation tree. If temporary
build selection is needed, choose the payload codec at build time. There is
no per-message automatic transcoding and no mixed-version broker session.

Exit: actual separate C++ processes can call and callback through the broker,
including a third forwarding peer, with correct references, FDs, identity,
oneway order, error returns, pool/polling progress, and death cleanup.

Design checkpoint: if retaining the command boundary requires recreating broad
kernel behavior unrelated to the selected API closure, compare a direct
adaptation of private IPCThreadState transport operations. Document the change
and rerun the same contract tests. Do not solve it with replacement public
Binder classes or no-op driver commands.

## Stage 3: prove Parcel and service-manager interoperability

Work:

- Exercise real generated CPP and NDK code against the candidate transport.
  Include null/non-null Binder fields and trailing fields inside nested
  size-prefixed parcelables, Binder stability, raw versus parcelable FDs,
  `appendFrom`, overwrite/truncation, and malformed input.
- Exercise a pinned native-handle/Flattenable path. Verify descriptor ownership
  after parcel copy, discard without reading, failed send, and disconnect.
- Change the candidate broker's service-manager codec to the new representation:
  get/check/add service, service unions and metadata, callbacks, and exact
  failure results. Validate replies against the pinned generated interfaces.
  Retain only the broker's narrow service-specific codec; it must not become
  another generic Parcel implementation.
- Test lookup through the real C++ `defaultServiceManager()` path, including
  context-object ping, rather than only a custom test lookup API.
- Verify object return-to-owner and proxy identity by observable API behavior,
  including callback invocation after the original sending Parcel is gone.

Exit: generated native clients can exchange object-bearing parcelables and
resolve services through the candidate broker without byte-layout adapters.
An integer echo or symbol-export check alone does not pass this stage.

## Stage 4: move Java and NDK facades to canonical C++ objects

Primary files: `src/binder/ndk.zig`, `framework_jni.zig`, a small new AOSP C++
object adapter, `src/native/AospParcelAbi.cpp`, native boundary documentation,
and existing Kotlin Binder/NDK bridge tests.

Work:

- Replace the Zig AParcel byte buffer and serializer with owned or borrowed
  access to an actual C++ Parcel. Make Java native-pointer access and the NDK
  wrapper agree on ownership and shared cursor semantics. Do not reinterpret
  old wrapper pointers as real Parcel objects.
- Replace independent Zig AIBinder object/refcount/proxy state with wrappers
  holding canonical C++ Binder references. A local BBinder adapter invokes
  existing Zig/Java handlers with real Parcel arguments.
- Route status/exception serialization through the stock implementation. Audit
  every existing export, not just the functions the current APK exercises.
- Route Java/NDK calling-identity and pool APIs through the same context used
  by C++ dispatch. JNI attachment/detachment and global-reference lifetime
  must follow the current runtime's requirements on every callback thread.
- Preserve Java -> native -> Java identity, local short-circuit behavior,
  weak-handle promotion, death-recipient lifetime, and exception propagation.
- Replace the fabricated `AospParcelAbi.cpp` mechanism with real object access
  in the candidate native island. Preserve CursorWindow and SharedMemory paths.
- Keep framework natives in Zig and add only the AOSP-object bridge to the C++
  allowlist. Update `docs/native-boundary.md` to describe the new ownership.

Build a complete candidate application tree using the candidate broker and
facades. Run mixed-language tests in that tree before changing the default.
Do not load old and new Binder runtimes into one ART process to compare them.

Exit: all Java/CPP/NDK directions pass both remotely and locally, including
nested callbacks across language boundaries, shared Parcel views, Binder
identity, service discovery, FD ownership, and caller identity clear/restore.
The complete candidate APK integration suite passes.

## Stage 5: switch production and delete the superseded implementation

Work:

- Rebase onto the agreed stock-client migration checkpoint and resolve shared
  manifest/generator/JNI changes before enabling the candidate by default.
- Switch broker, facade, native Binder, transport, application installation,
  launcher setup, and soname mapping together. Restart all session peers;
  no live-session upgrade is supported. Protocol mismatch must fail promptly.
- Remove temporary old/new build selection, the generic Zig Parcel codec,
  obsolete AIBinder caches/refcounts, old dispatch workers and identity TLS,
  fabricated Parcel symbols, and unused bridge exports. Preserve host transport
  and broker algorithms that remain necessary.
- Preserve `libbinder_ndk.so`'s external ABI and other installed aliases that
  currently provide framework native registrations. Audit loading order and
  ensure only one transport and C++ Binder state is active per process.
- Update architecture/native-boundary docs and the investigation's status.
  If any JVM stub is actually replaced, use the live closure tool and regenerate
  its audit/budget as required by AGENTS.md; this migration need not replace a
  Java class just to move its native implementation.

Exit: the normal build and full validation sequence pass with the old generic
implementation deleted. Recovery, if required, selects the previous complete
installation and starts a fresh session; it never mixes old and new libraries.

## Stage 6: demonstrate the graphics Binder prerequisite

Use a small pinned graphics interface/parcelable closure to transfer a real
native buffer handle and synchronization FD, invoke its completion callback,
and release it across process boundaries. Verify that callbacks and teardown
remain correct when the producer or consumer dies or stalls. Trace the chosen
interface before selecting the fixture; do not invent a lookalike graphics API.

Exit: native graphics types and their Binder interactions work through AZKB.
Record this as the prerequisite for libgui/SurfaceFlinger work, with graphics
allocation, compositor, and presentation bring-up still separately scoped.

## Test matrix and validation

Extend existing Binder contracts and runners. New native cases should be
reachable through a proposed `cpp-binder-test` target, and join the existing
native checks when the default switches. Generate test interfaces from AIDL;
new JVM test implementations belong in Kotlin.

| Contract | Minimum distinguishing case |
| --- | --- |
| Parcel layout | Nested size-prefixed parcelable with Binder, FD, and trailing sentinel; decoded by generated code. |
| Object lifetime | Receiver discards an unread Binder parcel; another case retains its Binder after parcel destruction. Last-reference cleanup must happen at the correct time. |
| Identity | Return a local object through a third peer and recover the original local identity; Java/native round trip shares the same Binder. |
| Calling context | Java -> CPP -> NDK nested callback, followed by clear/restore identity and another call; verify UID/PID at each boundary. |
| Concurrency | Same-thread nested callback with a constrained pool; ordered oneway calls to one target while another target makes progress. |
| Descriptors | Raw and parcelable FD transfer, copy, discard, failed transaction, and peer death; verify usable contents and bounded descriptor counts. |
| Failure results | UNKNOWN_TRANSACTION, DEAD_OBJECT, transport failure, and a service exception remain distinguishable. |
| Shutdown | Kill a peer during a call and during queued oneway work; waiters unblock and all remaining processes terminate cleanly. |
| Discovery | Real C++ and Java service-manager clients register, resolve, receive registration callbacks, and handle missing services. |
| APK regression | Existing provider/cursor, resources, startup, and UI paths reach expected states with no structured runtime issues. |

Run the smallest affected target first. Before each production-affecting commit
and before the cutover is accepted, run:

```sh
# In the established development shell. Lower further for memory/load.
binder_jobs=$(( $(nproc) > 2 ? $(nproc) - 2 : 1 ))
if [ "$binder_jobs" -gt 10 ]; then binder_jobs=10; fi

zig build -j"$binder_jobs" build-tools-test graph-audit cpp-audit ndk-abi
zig build -j"$binder_jobs" native-test ndk-test binder-test system-test jvm-test
# Once the proposed target is implemented:
zig build -j"$binder_jobs" cpp-binder-test
zig build -j"$binder_jobs" integration-test -- \
  --class NewPipeTest --method launchesHomeWithoutRuntimeIssues
zig build -j"$binder_jobs" integration-test
```

Supply `AZK_APK_NEWPIPE_0_29_1` as an absolute path with the pinned hash. Use the
existing development-shell/Mesa setup and any required `--system zig-pkg`
option. Candidate tests must explicitly use candidate artifacts, not the
default v4 installation. Do not build or run another session's changing tree.

APK verification uses `tools/shot.sh`, `WLR_RENDERER=gles2`, and existing
`azk-test step` diagnostics. On failure inspect `steps.jsonl` and `app.log`,
plus retained failure/tree/screenshot artifacts. Passing requires the expected
UI state and empty `diagnostics.runtime.issues` for every step. Unit/native
success alone does not authorize treating the runtime migration as complete.

## Coordination and commit boundaries

Stages 0-3 can proceed in this worktree while the stock-client migration runs,
provided shared checkout packaging is serialized. Stage 4 can also develop in
the candidate tree, but integration needs a stable migration checkpoint and
exclusive ownership of the shared files being changed. Waiting for unrelated
client migrations is unnecessary once that checkpoint and ownership are agreed.

Keep build/generator import work, native adapter work, and contract tests in
reviewable commits. The production protocol/native-pointer switch is one
coherent cutover; do not leave an intermediate default build whose broker and
clients disagree. The symbol replacement and deletion must occur together in
each library that starts linking real Parcel. Never commit an unreproducible
archive hash or raise the stub budget to finish a stage.

Completion requires all six implementation stages after the baseline, deletion
of the superseded generic implementation, full APK verification, and recorded
remaining graphics-stack work. An isolated probe is a gate, not completion of
this migration.
