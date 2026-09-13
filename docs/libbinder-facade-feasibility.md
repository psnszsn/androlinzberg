# A libbinder-compatible C++ facade over the Zig runtime

Status: feasible in principle, with a bounded executable demonstration;
full libgui compatibility is not yet established.

Reviewed on 2026-09-05 against repository source `cbd4362` and pinned AOSP
`android-16.0.0_r1`. This evaluates an alternative to
[replacing the native Binder implementation](cpp-binder-transport.md).
The current follow-up is [stock JNI over the implemented facade](stock-binder-migration.md).

## Verdict

We can retain the custom Zig Parcel representation and AZKB runtime and supply
the C++ APIs that source-built libgui expects. The native kernel Parcel layout
is not inherently required by the inspected libgui serialization paths.
Both ends must serialize through the hosted implementation, including any
SurfaceFlinger or replacement graphics service brought up later.

The source-level API surface is finite and worth implementing as a prototype.
It is more than exporting a handful of missing symbols: Parcel ownership,
native Flattenables, canonical C++ object identity, interface templates,
callback dispatch, and calling context need real implementations. Existing
Zig primitives can be reused, but existing behavior cannot simply be assumed
correct. The executable experiment below found a concrete lifetime gap.

Replacing the Zig object/Parcel implementation with AOSP C++ is therefore an
alternative, not a demonstrated prerequisite for stock libgui. Full native
libbinder compatibility, arbitrary precompiled C++ consumers, and unchanged
communication with a stock-encoded peer are outside the demonstrated result.

## What the pinned source requires

A lexical scan of 139 production `.cpp`/`.h` files under `libs/gui`, excluding
tests/fuzzers/benchmarks, found 11 directly included Binder headers:
`Binder`, `IBinder`, `IInterface`, `IPCThreadState`, `IServiceManager`, `Parcel`,
`Parcelable`, `PermissionCache`, `ProcessState`, `SafeInterface`, and `Status`.
Generated code adds dependencies such as `Delegate` and `Stability`.
This is an inventory aid, not a transitive compile-closure result.

The same direct-source scan found no references to `flat_binder_object`,
`ipcData`, `ipcObjects`, `BINDER_TYPE_BINDER`, `getPrivateAccessor`, or
`binderHandle`. It did find observable requirements that must be preserved:

| Pinned source | Required behavior |
| --- | --- |
| `IGraphicBufferProducer.cpp` | Stack-allocated request/reply Parcels, interface tokens, Bp/Bn dispatch, scalars/vectors, and `read`/`write` of native Flattenables including buffers and fences. |
| `LayerState.cpp:BufferData::writeToParcel/readFromParcel` | GraphicBuffer and Fence flattening, callback Binder references, weak token promotion, nullable objects, vectors, and owned native handles. |
| `SurfaceComposerClient.cpp` | Service discovery, death observers, thread-pool startup, callback objects, and Binder identity used as keys. |
| `SurfaceControl.cpp` | Binder identity comparison and `flushCommands()` on destruction so heavy resources are released promptly. |
| `BufferQueueProducer.cpp` | Distinguish local and remote objects via `remoteBinder()`, register death listeners, and observe caller identity. |
| `BufferQueueThreadState.cpp` and `binderthreadstate/CallerUtils.h` | Current caller UID/PID, `IPCThreadState::selfOrNull`, and serving-stack context; the full build also references HIDL Binder support. |
| Generated `ISurfaceComposerClient` | Interface checks, `markForBinder`, status headers, nullable/non-null Binder reads, generated parcelables, and UNKNOWN_TRANSACTION/default-implementation behavior. |

The inspected calls go through the Parcel API. Custom `dataPosition()` and
`setDataPosition()` can provide consistent size-prefixed serialization as long
as the reader uses the same representation. Native flattened buffer contents
must still follow their pinned native types; changing Binder's representation
does not justify changing GraphicBuffer/Fence formats.

The complete Soong libgui target also depends on libui, allocation/EGL support,
HIDL bufferqueue adapters, flags and generated interfaces. Those dependencies
remain separate bring-up work whichever Binder approach is chosen. The
`NO_BINDER` bufferqueue target is a restricted alternative build, not a switch
that removes Binder from full libgui and SurfaceControl.

## Executed experiment

Artifacts are retained at
`/tmp/azk-libgui-facade-gbwgd7yg/`:

- `probe.cpp`, `include/`, and `compat.h`: temporary, deliberately limited C++
  facade and test harness;
- `cpp/` and `ndk/`: unmodified outputs of the pinned AIDL compiler;
- `compile-command.json` and `compile.log`: build evidence;
- `run.py` and `runs/{normal,hold-reply-binder}/`: execution script and results;
- copied `libbinder_ndk.so` and `azk-binder-broker`: isolated runtime artifacts.

The runtime binaries were copied read-only from the main worktree's existing
`zig-out` installation, rather than freshly built from the source baseline.
Their SHA-256 values are recorded in each `results.json`:

```text
libbinder_ndk.so  02c19d76e7aaae295a3646c1052847d02c7af27018c319e6abed8ba5b3c41e52
azk-binder-broker 263d6b7ae3ecad20c0544b134639a3257c3011cf677349c5a637d6150361e6d2
```

The test used a fresh broker/session/socket in the scratch directory and two
separate native processes. It did not use or modify the main session's broker.

The C++ facade forwards its supported Parcel operations into the existing
`AParcel_*` C exports. The generated CPP and NDK classes came from the actual
`android.gui.CreateSurfaceResult.aidl`, which contains a Binder handle, layer
ID, String, and transform hint. Pinned `ISurfaceComposerClient` was generated
and inspected, but its proxy/stub was not compiled into the executable.

The executable also compiled the actual pinned `LayerMetadata.cpp` and
`system/libbase/stringprintf.cpp` without edits, plus the generated
`LayerMetadataKey` enum. It exercised integer metadata and a byte-vector value
through the facade.

Results:

| Case | Result |
| --- | --- |
| Unmodified native libgui LayerMetadata read/write over Zig Parcel | Passed. |
| Generated CPP -> generated NDK -> generated CPP CreateSurfaceResult in process | Passed, including a non-null Binder, Unicode string, and trailing fields. |
| Remote CPP -> AZKB -> NDK server -> AZKB -> CPP with normal reply object lifetime | Failed: broker reported `invalid object entry in reply`; client hit the 5-second timeout. |
| Same remote exchange with the server explicitly retaining the reply Binder past submission | Passed, including Binder return-to-owner identity, a trailing sentinel, and a pipe FD whose contents were read across both processes. |

The explicit keepalive is a diagnostic control, not a proposed production fix
or a passing ownership regression. The failure is consistent with inspected
`ndk.zig:AParcel_writeStrongBinder`: a remote Binder contributes its ID to the
Parcel side table without acquiring a Parcel-owned strong reference. When the
server's generated NDK result is destroyed before `Runtime.sendReply`, its last
proxy reference can be released to the broker before the reply forwards it.
Holding that reference prevents the observed failure. The associated timeout
also needs regression coverage for failed forwarding and waiter cleanup.

The prototype uses simplified `sp`/String16 support types to isolate the Parcel
and generated-code boundary. It does not establish compatibility with AOSP
RefBase, real Bp/Bn class hierarchies, precompiled C++ ABI, death recipients, or
graphics frame production. It transfers a pipe FD, not a DMA-BUF or sync fence.
The 36-byte payload printed by this particular test includes its string and
other fields; it is not a measurement of the Binder field size.

## Work required for a production facade

1. **Fix Parcel ownership in the shared Zig runtime.** Retain Binder references
   independently of application wrappers through send/reply, parcel destruction,
   copying, overwriting, and unread incoming parcels. Include rollback on failed
   delivery, not just successful read paths. Fix the demonstrated reply failure
   without an application keepalive.
2. **Provide genuine C++ objects.** Replace the fabricated thread-local Parcel
   pointer mechanism with independently owned/borrowed instances. Preserve
   C++ identity for wrappers around one AIBinder. Integrate actual AOSP
   RefBase/sp/wp and reusable interface/template support where practical;
   the prototype's simplified support types are not the production design.
3. **Complete the Parcel API used by the closure.** Implement inplace access,
   vectors, raw/parcelable FD distinctions, native handles, Flattenables,
   status/exception helpers, object-aware copying, bounds/error behavior, and
   interface checks through a small internal Zig C ABI where the public NDK
   ABI is insufficient. The existing exported `AParcel_appendFrom` rejects
   object-bearing ranges, while the internal Java append path has different
   behavior; a facade must reconcile the contracts, not alias them blindly.
4. **Unify the C++ and existing dispatch models.** Implement ProcessState and
   IPCThreadState behavior at this API boundary over the existing runtime.
   Test local versus remote calls, nested callbacks, pool and polling semantics,
   thread-local identity, flush/release ordering, death and unlink behavior.
   Current NDK polling returns success with FD -1 and pool sizing ignores its
   argument; those exports alone are not sufficient implementations.
5. **Preserve one encoding domain.** Source-build libgui and the participating
   native service interfaces against the facade. The broker can keep its
   current encoding if its contract need not change. Native binaries that rely
   on stock Parcel object layout or emit stock-encoded bytes require a separate
   compatibility decision. Merely resolving their symbols is insufficient.
6. **Audit remaining stock assumptions.** Permission checks, stability metadata,
   scheduler settings, SID requests, and HIDL caller-context helpers cannot be
   accepted as no-ops without tracing the hosted contract. Test unsupported
   capabilities explicitly and avoid application-specific behavior.

Several of these are existing-runtime correctness work, not new requirements
created by C++. The facade makes those contracts visible to more consumers.

## Follow-up prototype result

The next prototype was executed on 2026-09-05. Its source and reproduction steps
are in [tools/prototypes/binder-facade](../tools/prototypes/binder-facade/README.md).
It passes with actual AOSP RefBase/sp/wp and IInterface templates, an unmodified
AIDL-generated IFpsListener Bp/Bn pair, nested and oneway callbacks, local/remote
identity, calling context, and death/unlink notifications.

It also compiles unmodified pinned GraphicBuffer.cpp and Fence.cpp. Both native
Flattenables cross AZKB using a real DMA-BUF and a real signaled sync file. The
service changes shared pixel contents, and the client verifies them after the
return trip. GraphicBuffer import uses a small host mapper that clones opaque
native handles; Android gralloc and rendering are not part of this result.

The original generated C++/NDK reply test also passes without its diagnostic
keepalive after the isolated outgoing Parcel-reference fix. The follow-up
exposed a second existing runtime defect: unlinking one death observer removed
the broker subscription still needed by another observer. A second isolated fix
preserves that subscription, and the death test passes. Neither fix has been
applied to production sources; production ownership and concurrency audits remain
necessary. The prototype README lists the unimplemented API and failure paths.

The final experiment builds its own Zig library and broker from this worktree's
sources. Build commands, source provenance, the experimental runtime patch,
results and logs are retained in `/tmp/azk-binder-facade-v2/`. The existing native
NDK self-test also passes against that isolated build.

This satisfies the bounded prototype gate below and supports proceeding with a
production implementation of the facade architecture. Full libgui, graphics
allocation/import, and mixed JVM/application regression gates remain ahead.

## Original prototype gate (now exercised)

Pursue a bounded facade prototype before committing to replacing the entire
native implementation. Fix the reply lifetime case and rerun it without the
diagnostic keepalive. Then compile a real pinned Bp/Bn graphics interface with
actual RefBase-based objects, and exercise callbacks, death, local identity,
caller identity, and a GraphicBuffer/Fence Flattenable path through the broker.
Follow with the existing mixed-language and APK regressions when the shared
runtime changes; this investigation itself did not change or test APK behavior.

Prefer reusing pinned AOSP helper/template code over rewriting it merely to
preserve the facade approach. If the required adapters grow into a second
implementation of most Parcel/Binder semantics, reassess the maintenance cost
against the AOSP-native migration plan. There is currently evidence for trying
the facade, and no demonstrated encoding-related reason it cannot work.

The source-rebuild qualification also matches AOSP's documentation: the CPP
backend's libbinder API surface is not a stable independent-delivery ABI,
whereas the NDK facade provides the stable C boundary.
[AOSP AIDL backends](https://source.android.com/docs/core/architecture/aidl/aidl-backends)
