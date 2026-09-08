# Stock graphics and Wayland task windows: implementation plan

Status: stock WMS/ATMS cold and warm launch, HWUI/BLAST rendering, two-task
Cage/labwc presentation, native input-channel routing and compositor keyboard-map
publication pass. Stock window clients are selectable for development. The
production launcher cutover, complete desktop input, configuration/output policy
and desktop interoperability remain in progress through M11.
Code baseline: `2a2d199`, the
hosted libbinder facade merged into main. This document expands the original
“Wayland task windows with synthetic Android coordinates” proposal into a
dependency-ordered implementation plan. It includes the BufferQueue milestone
and the work needed to make task windows the normal APK presentation path.

## Drag focus across asynchronous source preparation (2026-09-08)

Provider preparation can outlast the first local drag motion. Pinned
`ViewRootImpl.handleDragEvent` then retains a focused View and reports no new
recipient ENTER when native motion arrives over that same View. Regression 478
forces this ordering with an 800ms provider read and actual compositor motion
before and after takeover; it reproduced a cancelled drop without a framework
exception. Handoff now sends EXIT through the same IWindow stream used for native
LOCATION. This clears old drag focus while preserving the original START, data,
grants and END. Queued recipient callbacks also retain their original offer
identity instead of selecting whichever offer is active when the handler runs.

Regression 479 passes all 32 drag actions with no structured runtime issues,
including the delayed-provider EXIT/ENTER sequence and subsequent native,
cross-task and fallback drops. The native destination now has a colored backdrop:
captured stock GPU shadow pixels prove alpha blending, bounds and hotspot rather
than only a blue rectangle over black. Regression 477 also passes that alpha gate.
Artifacts are retained in `/tmp/wayland-drag-focus-478-artifacts/`,
`/tmp/wayland-drag-focus-479-artifacts/` and
`/tmp/wayland-drag-alpha-477-artifacts/`.

Shutdown after the earlier failed run exposed an uncaught DeadObjectException in
icon retirement. Outgoing drag cleanup now attempts every resource owner even if
the presenter dies; claim and cancellation failures still end the original WM
drag. A dead presenter cannot be awaited for a terminal callback. Other failures
remain in the structured diagnostic journal. Regression 480 passes the full JVM
suite, all 16 APK tests (52 clean steps), Wayland unit tests and C++/graph audits.
Artifacts: `/tmp/wayland-drag-focus-480-artifacts/integration/`. Deliberate
process-death coverage and the remaining M8–M11 work are still in progress.

## Visible task-local drag fallback (2026-09-08)

When native drag presentation is unavailable, the original WMS input stream now
keeps its shadow in the source task's mirrored subtree. A translation container
preserves pinned shadow position, alpha, transforms and animation in Android
synthetic coordinates. It follows the source task's bounds; cleanup follows the
stock shadow-removal and relinquishment lifetime. Drops outside that task are
rejected because this input stream cannot identify another desktop task.

The native claim checks data-device version and viewport availability before
staging URI exports or consuming a gesture. Known rejection before takeover
selects local presentation; uncertain completion still ends the gesture.

Regression 470 reproduced a functional file drop with a missing shadow: 25
clean actions passed, but captured pixels showed only the source background.
Regression 472 passes all 29 drag steps, including visible fallback shadows in
both task positions, rejection of a cross-task attempt without the protocol,
and successful native cross-task transfer after protocol restoration. The
fixture also now honors stock END broadcasts to interested views that did not
receive DROP. Regression 473 passes the full JVM suite and all 16 APK tests
with 49 clean steps. Regression 474 passes the 29-step drag, four-step video,
21-step clipboard and both GPU icon gates, plus C++/graph audits. Artifacts:
`/tmp/wayland-local-drag-470-artifacts/`,
`/tmp/wayland-local-drag-472-artifacts/`,
`/tmp/wayland-local-drag-473-artifacts/`, and
`/tmp/wayland-local-drag-474-artifacts/`. Package 92 contains 96 patches.

The complete drag policy, death/timeout and pressure matrix and the remaining
M8–M11 acceptance and production cutover are still in progress.

## Original Android drag handoff and native export (2026-09-08)

Stock `View.startDragAndDrop` now transfers its authorized drag to the native
presenter. The shell retains the original WMS DragState, ClipData, source UID
and URI-grant flags. A bounded native start reply distinguishes accepted,
rejected and uncertain grabs while the Wayland owner keeps dispatching. Only
accepted handoffs reset the stock input dispatcher; queued legacy motion/drop
callbacks cannot close the transferred drag.

The original stock shadow is mirrored by SF into a private BufferQueue output
and presented as the compositor drag icon. Logical dimensions/hotspot are
projected from the accepted task geometry; allocation follows its output scale.
Task and icon outputs share a private layer-stack allocator. Icon retirement
follows SF unbinding and the terminal source event, with bounded orphan cleanup.

Offers returning to the same Android session carry the current source identity.
They reuse the original state and grants without reading exported bytes or
creating a second drag. Leaving a task only ends that offer. The Android
recipient result finishes the offer before the compositor finishes the source.
Private/local-only sources export no representations, and a native peer merely
finishing their marker cannot claim Android consumption. URI exports require
GLOBAL_URI_READ and retain exposed immutable file leases for the session.

Regression 461 passes private-source leave/re-enter and completion-authority
checks. Regression 465 passes 49 Wayland unit tests and all 15 stock drag steps:
Android recipient acceptance/rejection, native text/HTML/file import, and real
Android-to-native text/file export. Captured GPU pixels verify the original blue
64x48 shadow, white DRAG text, and 32,24 hotspot. Regression 466 passes the entire
JVM suite and all 16 APK integration tests with 48 clean steps. Artifacts:
`/tmp/wayland-own-source-461-artifacts/`,
`/tmp/wayland-outgoing-465-artifacts/`, and
`/tmp/wayland-outgoing-466-artifacts/`. Package 91 contains 95 patches. Regression 467 also passes stock video
(four clean steps), both drag-icon protocol versions, all three GPU-fence gates
and C++/graph audits; artifacts: `/tmp/wayland-outgoing-467-artifacts/`.

Regression 468 expands the drag gate to 17 clean steps: a native recipient
refuses the offer, then a subsequent accepted offer is left before DROP. Both
produce Android source cancellation, and a fresh file drag still succeeds.
Artifacts: `/tmp/wayland-outgoing-468-artifacts/stock-drag/`.

Regression 469 expands the gate to 23 clean steps with two separately placed
Android task windows. Both text and file drags reach the second task; the source
gets successful completion, and the file recipient checks that its URI is the
original Android URI, not a staged desktop export. Artifacts:
`/tmp/wayland-cross-task-469-artifacts/stock-drag/`.

The complete capability/death/timeout matrix and the remaining M8–M11
acceptance and production cutover remain unfinished.

## Native source start acknowledgement (2026-09-08)

The native source now waits for compositor evidence of grab acceptance. A
processed request alone is insufficient: source/offer events or ordered input
takeover prove acceptance. Explicit compositor cancellation before input loss
permits local fallback. A locally aborted request or one-second acknowledgement
timeout is uncertain and cannot resume the consumed gesture.

Regression 457 exercises rejected serials, locally aborted starts and fresh
physical gesture recovery before all five native-to-Android transfers. All 12
steps pass without structured issues. Regression 456 also passes both GPU icon
protocol versions. Regression 458 passes the complete JVM suite and all 16 APK
integration tests with 49 clean steps. Artifacts:
`/tmp/wayland-start-456-artifacts/`, `/tmp/wayland-start-457-artifacts/`, and
`/tmp/wayland-start-458-artifacts/`. Presenter/WMS invocation remains in progress.

## Stock dispatcher drag handoff (2026-09-08)

The pinned InputDispatcher now exposes a host handoff that checks its current
drag input token and clears that drag without reporting a failed DROP. This is
separate from ordinary channel/window removal, which retains the stock failure
callback. Native targeting now only queries SF geometry; WMS sends native
LOCATION through the same IWindow channel as START and DROP. The unused second
input-FD hover path has been removed.

Regression 453 passes all 13 native dispatcher tests, including wrong-token
rejection, one-time handoff, cancellation before/after channel teardown, a fresh
subsequent gesture, and unchanged failure reporting without handoff. The Kotlin
bridge also resolves Android pointer IDs back to the owning task's native touch
contact, with assertions for remapping, release, cancellation and resize.

Regression 454 passes the full JVM suite and all 16 APK tests with 49 clean
steps; 455 passes all 12 stock/native drag steps. Artifacts:
`/tmp/wayland-handoff-454-artifacts/` and
`/tmp/wayland-handoff-455-artifacts/`. The package now includes native patch 0010.
WMS invocation, shadow mirroring and original
state reuse across task/native targets remain the next integration work.

## GPU drag-icon presentation (2026-09-08)

An unroled Wayland icon surface now accepts the same stock BufferQueue leases
as task output. The session owns a shared DMA-BUF importer; buffer release
permits reuse, while teardown abandons outstanding leases. Frame callbacks only
pace submission. The icon viewport retains its logical size across producer
buffer changes, and the hotspot offset applies once rather than accumulating
on every frame.

The native GPU fixture presents 12 stock Surface frames, resizes their buffers
from 320×240 to 480×360, and checks a 64×48 icon with a 12×9 hotspot against the
compositor screenshot. Regression 447 passes with zero live graphics buffers
after shutdown. Regression 448 passes stock video (4 clean steps), stock/native
drag (12 clean steps), delayed acquire-fence delivery, fence timeout and shutdown.
Regression 449 passes the full default APK suite (16 tests, 49 clean steps).
Regression 450 passes the icon gate using both current surfaces and version 4
attachment offsets, plus C++/graph audits. Both icon runs release every buffer.
Artifacts: `/tmp/wayland-icon-448-artifacts/` and
`/tmp/wayland-icon-449-artifacts/`, with both icon captures in
`/tmp/wayland-icon-450-artifacts/`.

The full `integration-test` target currently requires the default build selection;
its launcher still uses the legacy service stack. Combining it with
`-Dstock-window-clients=true` fails at legacy window-session admission before
any test action, as confirmed by the retained 448 failure logs. Stock task tests
use their own cold-launch harness. Migrating the complete suite belongs to M11.

WMS still needs to mirror its stock shadow into this icon producer and transfer
the existing Android drag state, including cross-task recipients and URI grants.

## Held native drag gesture validation (2026-09-08)

The session records the exact pointer-enter or touch-down surface and the
physical grab's serial. Android-facing callers snapshot a generation before
payload preparation, then consume it once at handoff. Released, replaced,
cross-window and already-used gestures fail validation. Pointer chords retain
the first press serial; bounded button overflow fails closed. Reused touch IDs
receive fresh generations, including when native drag consumed the previous UP.
Origin removal and seat capability loss retire the corresponding ownership.

Regression 444 passes the Wayland unit tests and all 12 native/stock drag steps
through the session handoff API. The real compositor completes text, HTML and
URI COPY, recipient rejection and pre-drop cancellation with no runtime issues.
Artifacts: `/tmp/wayland-drag-grab-444-artifacts/`. WMS integration and the stock
drag-shadow mirror remain the next outbound work.

## Outbound native source component (2026-09-08)

The seat now owns an outbound COPY data-source component with immutable MIME
representations, a private source marker, shared bounded FD workers and terminal
compositor-event delivery. Source transfer failures cancel the source; the
post-drop destination wait is bounded to ten seconds. Origin-surface, manager
and session teardown cancel outstanding ownership. Source objects and their
payloads remain bounded to one per seat and retire at replacement or teardown.

The existing native drag peer now exercises this production source component
instead of its own protocol listener. Regression 443 passes all 12 drag steps:
Unicode text, HTML and URI COPY completion, Android recipient rejection and
cancellation before DROP. Native result files verify target/action negotiation,
FD sends and the correct terminal source event. All 21 clipboard steps, Wayland
unit tests and C++/graph audits also pass. Artifacts:
`/tmp/wayland-drag-source-443-artifacts/`.

This is the outbound protocol component, not the completed Android/native
handoff. WMS still needs to transfer the existing drag state using the actual
held-input origin/serial, preserve its grants across task targets, and mirror
its stock drag shadow into the native icon surface.

## Test-control descriptor ownership and pressure (2026-09-08)

The Varlink server now uses explicit std.Io ownership for sockets, reads,
writes, clocks, path permissions and cleanup. One cancellable accept task hands
a descriptor to the ART main thread through a one-slot queue and eventfd. Tree
reads and Android actions remain on the existing main-thread loop. Partial
requests and blocked replies have a five-second deadline; requests are bounded
to 1 MiB and queued replies to 16 MiB. Complete Android settle requests keep
their own timeout.

Socket publication binds inside a private staging directory, applies mode 0600,
then atomically renames without replacement. Binding through the owned directory
descriptor preserves long endpoint paths. Nine native tests cover selectors,
fragmented/pipelined messages, reconnects, duplicate endpoints, long paths,
cancelled blocked accepts, descriptor counts and nonreading/partial peers.

Regression 439 passes all 12 stock/native drag steps through this transport.
Regression 441 passes its native tests, C++/graph audits and all 16 APK tests
with 49 clean steps. Artifacts: `/tmp/wayland-control-439-artifacts/` and
`/tmp/wayland-control-441-artifacts/`. This closes the raw test-control I/O item;
the broader recovery/pressure and default-cutover work remains in M11.

## Stock MediaCodec rebase and clipboard ordering (2026-09-08)

Rebased onto main `84b3907b`, incorporating stock AudioManager, AudioTrack,
MediaCodec, media metrics/CAS and extractor sources. Main was left untouched.
The package contains 93 patches (56,226 sources and 217 symlinks), with hash
`N-V-__8AAOyOe04S_EqvkIKxsmZc_8ISUctwByyUKWAzJ6zv` and SHA-256
`e1b46c0e10c1e88f5e24d06791ad1dc648d4f18877bc8cddc445a33362fa3d32`.
The source audit has seven pending stubs and four survivors.

Pinned Java MediaCodec owns validation, buffer views and Handler delivery.
HostedMediaCodecBackend now connects decoded frames, color metadata and rendered
frame events to HostedVideoOutput. Polling uses the handler selected by stock
Java. The rebase also removes duplicate permission-service AIDL/native/source
entries. HostedAudioService preserves stock navigation/home effect switches and
the disabled-sound-effects early return. The native permission smoke now uses
an unassigned application UID; its previous value had root app ID zero.

Clipboard publication now has one wl_display.sync barrier in flight. Copies and
clears coalesce behind it; the preceding source marker survives only until the
replacement barrier completes. A barrier orders events without treating an old
seat serial as accepted. Explicit ownership loss still retires publication
retries. A 16-pair copy/clear burst verifies final plain/HTML contents and retained
Android ClipDescription metadata. Codec CLI fixture writers now own files through
explicit std.Io.

Regression 431 passes stock video (four clean steps), native BufferQueue and
C++/graph audits. Regression 432 passes all 21 clipboard steps and Wayland unit
tests. Regression 433 passes all 16 APK tests with 47 clean steps; regression 434
passes the full JVM suite after correcting the permission test UID. Artifacts:
`/tmp/wayland-video-clipboard-431-artifacts/stock-video/`,
`/tmp/wayland-clipboard-432-artifacts/`, and
`/tmp/wayland-regression-433-artifacts/`. Outbound/cross-task drag, its icon, and
the remaining M8–M11 acceptance/cutover work are still unfinished.

## Rendered video callbacks (2026-09-08)

`MediaCodec.setOnFrameRenderedListener` now forwards to the hosted codec output
owner. Stock Surface supplies frame numbers and timestamps. Following pinned
`CCodecBufferChannel`, BLAST display queues report an observed present time (or
latch time where present fences are unavailable), with its 100 ms grace and
scheduling clamp. Intermediate TextureView queues report successful queue
completion with the original media PTS and requested render timestamp. Missing
or dropped display samples never become fabricated presentation callbacks.

One handler poll and 128 native records bound pending work. Listener replacement,
flush, output changes, stop and release invalidate pending records and callbacks.
Application callbacks execute outside the output ownership lock. After the stock
MediaCodec rebase, pinned Java selects the explicit handler, creation looper or
main-looper fallback; the host output owns no additional callback thread. Already
delivered Java messages retain stock MediaCodec lifecycle semantics.

Regression 426 passes the native BufferQueue ownership/pressure suite, stock
video APK and C++/graph audits. The APK checks real SurfaceView timestamps,
TextureView queue timestamps, both explicit/default callback handlers, listener
replacement, surface switching, resize, release cancellation and decoded pixels
under an Android UI overlay. Four steps have no structured runtime issues.
Artifacts: `/tmp/wayland-video-426-artifacts/`. Regression 427 adds a passing
synchronous-flush gate without replacing the listener or restarting the codec.
The catalogue now reports video as hardware accelerated with Surface output;
audio remains software decoded. Regression 428 passes the full default JVM suite
and all 16 APK tests with 52 clean steps. Artifacts:
`/tmp/wayland-video-428-artifacts/`. Full M10/M11 acceptance remains unfinished.

## Native incoming drag and rendering lifetime (2026-09-08)

Regression 421 passes 12 stock drag steps: Android text/file/rejection plus a
separate native Wayland source sending Unicode text, HTML and a local file,
observing rejection, and cancelling by leaving the destination. The native
source uses a real held pointer serial and checks COPY negotiation and the
terminal source event. The file recipient takes and releases stock
DragAndDropPermissions. Sixteen consecutive compositor captures preserve both
drag regions, and the final PNG contains the expected pixels. An earlier visual
inspection incorrectly classified the saved screenshot as missing content;
decoding its pixels and reopening it confirmed the complete frame. Video (four
steps), clipboard (19), desktop (13), Wayland ownership tests and C++/graph audits
also pass. Artifacts: `/tmp/wayland-native-drag-421-artifacts/`.

`HostedNativeDrag` adopts a stock WMS DragState for an external source, retaining
recipient validation, URI grants, drop results and timeout. InputDispatcher's
SF hit test, task ownership map and window transform resolve the recipient.
Native locations use the same IWindow channel as START/DROP/END: sending the
first location over a separate input FD raced START when the pointer stopped
on entry. The Android View hierarchy still selects the receiving child.

The seat owns one native offer, COPY acceptance and bounded asynchronous MIME
streams. Offer IDs survive protocol recreation; late staging is matched to an
attempt identity. Clipboard and drag share the provider's two-worker/four-queue
staging budget. A file lease survives an attempted DROP because an app may hold
its stock drag permissions beyond the gesture; the shared session storage limit
still applies. Cancellation before DROP releases unpublished staging.

HWUI now releases imported EGL rendering objects before exporting completion
and publishing the buffer, including cancellation. RenderEngine orders its
producer-fence wait before creating a DMA-BUF backend texture. Diagnostic
readbacks and CPU waits are absent. The native composition regression holds
producer writes behind a real unsignalled GPU fence on both first import and
buffer reuse: composition completion remains unsignalled until release, then
both mirrored outputs have the expected crop, alpha and secure-layer pixels.
Regression 422 passes this gate and the full JVM suite. Its APK suite has one
reproducible desktop fullscreen timeout. Tracing showed NewPipe had requested
LAYOUT_* flags while its visible controls kept the bars shown. The regression
now finishes the expanded-player layout and hides controls before requiring
host fullscreen; layout flags alone retain their Android meaning. Regression
428 passes the entire suite with that correction.

The combined package now contains 90 patches. This checkpoint covers incoming
native COPY drags; outbound/cross-task drags, Android drag icons and the broader
death/pressure/fallback matrix remain M10/M11 work.

## Main framework rebase (2026-09-08)

Rebased onto main `1f84559`, incorporating its stock ringtone, MediaPlayer,
subtitle, accessibility shortcut, HIDL transport, HDMI and generator additions.
The combined AOSP package contains 55,981 sources and 88 patches. The live
source audit now reports 12 stubs: eight pending and four documented survivors.
The closure-tool regression suite passes all 17 tests.

Regression 399 passes stock clipboard (19 steps), IME (22 steps), video (four
steps), Cage task presentation, C++ and graph audits. The desktop launcher
reaches its final UI state with 13 clean steps; its final assertion accidentally
used the clipboard test's 19-step count. Correcting that assertion yields a
passing desktop gate in regression 400. Retained artifacts:
`/tmp/wayland-rebase-399-artifacts/` and
`/tmp/wayland-drag-400-artifacts/stock-desktop-launcher/`.

Regression 404 passes the complete default JVM suite and all 16 current APK
integration tests, with 48 clean steps. Artifacts:
`/tmp/wayland-rebase-404-artifacts/integration/`. Drag-and-drop and the remaining
acceptance work are in progress; this rebase is not the default launcher cutover.

## Stock drag contracts and cursor lock ordering (2026-09-08)

The new `stock-drag-test` launches a real APK View and calls stock
`View.startDragAndDrop` during a held compositor pointer gesture. Regression
403 passes six clean steps covering text and content-URI drops, the stock
permission object, callback ordering, recipient consumption and rejection.
Artifacts: `/tmp/wayland-drag-403-artifacts/stock-drag/`.

The fixture exposed two issues. The virtual input driver previously sent a
complete drag in one burst, releasing the pointer before WMS could transfer its
input channel. Stock test drags now retain one bounded pending gesture and send
paced edges from the normal presenter loop, with the final input barrier after
release. Task/configuration and seat generations guard the pending gesture.

Stock `DragDropController.performDrag` calls the pointer-icon service with the
WM lock held. The host adapter synchronously waited for the shell thread, which
needed the same lock. Cursor publication now accepts a validated request and
coalesces one update per device, revalidating its device generation and input
window before rendering. It never waits for the shell while holding a caller's
WM lock. Internal drag input channels are not mistaken for task windows.

Tablet cursor capture now derives from all live windows, including when the
protocol manager reappears. A late unlock cannot clear another live capture.
The ownership unit test and the existing real tablet gate pass in 403, as does
the 13-step desktop cursor/input gate. A real manager-recreation-under-capture
gate remains part of the broader input acceptance matrix.

This proves the stock same-window drag contract. Native/cross-task data offers,
Wayland drag icons, cancellation/death recovery and the remaining M10/M11 work
are still pending. The 402 failure also showed why M11 must retain diagnostics
from dying app processes: its raw fatal-exception log preceded inspection loss.

## Clipboard file transfers (2026-09-08)

The desktop bridge now stages `text/uri-list` through the separately installed
`dev.artzig.desktop` provider APK. It has an ordinary application UID. Native
local regular files become opaque, read-only `content://` URIs; stock
ClipboardService issues and revokes the recipient grants. Android exports use
temporary grants from ClipboardService's authenticated source UID to the staging
provider. System UID never impersonates ownership of an app URI. Provider control
calls require system UID, and stock ContentProvider.Transport enforces reads.

Staging preserves file names and immutable bytes, handles multiple URI items,
and refuses nonlocal file URIs, devices/FIFOs, final-component symlinks, and
malformed input. Limits are 16 files, 8 MiB per file, 32 MiB per transfer and
64 MiB of session storage. Two workers and four queued requests bound provider
work; file reads have a five-second deadline. A six-second system timeout revokes
source grants even if an external provider has not returned. Imported leases
retire when the clipboard changes; existing open descriptors remain readable.
Exported files remain available for the session because `text/uri-list` has no
acknowledgement for a native recipient's later file open. Exhausting that storage
budget is an explicit failure, never silent eviction of an advertised file.

Native publication is confirmed by the source's private MIME marker. A
compositor may ignore an obsolete seat serial without reporting an error; an
unconfirmed programmatic copy retries with a fresh data source on subsequent
real input. Pending copies survive data-device manager replacement. Application
copy requests coalesce, and independent import generations reject obsolete
provider completions without overwriting a newer Android or native selection.

`desktop-transfer-test` verifies parsing, immutable leases, cleanup, cancellation,
deadlines and admission/recovery under pressure. The expanded stock clipboard
gate passes 19 journal-clean steps, including Android/native file contents,
Unicode names, native multi-file import, denied writes/control calls, grant
revocation and retry after real Wayland input. Initial retained passing artifacts:
`/tmp/wayland-uri-396-artifacts/stock-clipboard/`. The final 398 gate also verifies
that a descriptor opened before revocation remains readable, with artifacts in
`/tmp/wayland-uri-398-artifacts/stock-clipboard/`; 397 passes source and graph audits.

Unresponsive-provider process retirement and the broader recovery/pressure matrix
remain M11 work. Intent clipboard export and heterogeneous URI/non-URI item lists
are explicitly unsupported. Drag-and-drop and the remaining M10/M11 work remain
unfinished.

## Outcome and scope

Rebased onto main `1b8b637` on 2026-09-07, incorporating its camera, media,
shared-memory, NSD and IKE framework additions. The native SF display-event path
remains the Choreographer authority. Package 71 contains 55,586 sources and 78
patches; the audit reports 19 stubs (13 pending, six survivors). Regression 135
passes framework-module, native, Binder C++, system, graph and the complete APK
integration targets. The rebase exposed duplicate JNI Binder converters; their
shared implementation now remains solely in `AospParcelAbi.cpp`. Log:
`/tmp/wayland-rebase12-validation-135.log`.

## Text-input checkpoint (2026-09-08)

The system input-method service can now bind stock IInputMethodSession and
IRemoteInputConnection clients to a seat-owned text-input-v3 object. Task and
editor generations guard both directions. Incoming batches own bounded UTF-8
payloads; their queue budget includes borrowed events. Android queries use
asynchronous futures with deadlines, and publication waits until the app has
applied the edit batch. An older state serial still delivers valid edits; a
superseded editor's serial cannot acquire a new editor.

The adapter converts surrounding text, selection and deletion between UTF-16
and UTF-8 without splitting a Unicode scalar. It omits password context and
unavailable context, removes old preedit before applying deletion/commit, and
preserves the composing selection. Stock setImeConsumesInput handles hidden
preedit carets. Android editor replacement follows BaseInputConnection's close
contract, retaining text while finishing composition. CursorAnchorInfo maps
from the synthetic Android display into task-local coordinates. There is no
fabricated Android IME surface, panel height or inset.

The test compositor supplies actual protocol events through the existing
headless harness. Regression 366 passes `stock-ime-test` with 11 clean semantic
steps covering Unicode composition, hidden caret, commit, byte deletion,
password context, delayed state serials, superseded editor events and editor
replacement. It also passes the tablet gate, Wayland ownership/state unit tests,
C++ audit and graph audit. Regression 364 passes the existing 13-step desktop
physical-input gate with no running compositor input method. Artifacts:
`/tmp/wayland-ime-366-artifacts/` and `/tmp/wayland-ime-364-artifacts/`.

Regression 367 passes the complete JVM suite and all 16 APK integration tests,
with 48 clean steps. Artifacts: `/tmp/wayland-ime-367-artifacts/`.

Regression 373 expands the IME gate to 22 clean steps. Actual compositor focus
loss removes preedit, re-entry starts a fresh editor generation, stock input
invalidation advances its command header, and physical Enter reaches the editor
action listener. Removing and recreating the protocol manager preserves physical
typing and reactivates composition with a fresh query of the edited document.
The callback test exposed an incorrect PID check: Binder intentionally reports
PID zero for oneway transactions. Session callbacks now check their registered
UID and Binder capability, with the window/process tuple still validated by WMS.
Artifacts: `/tmp/wayland-ime-373-artifacts/`.

Desktop software-IME visibility follows the existing empty-Android-IME branch:
show/hide return false and do not claim an Android panel was shown. A compositor
input method owns its native UI; text-input-v3 supplies no independent panel
visibility or geometry contract. Android's selected-service/subtype inventory
therefore remains empty and its IME window height is zero. Text editing and
physical editor actions remain available independently of those panel APIs.
Regression 374 passes the 22-step IME gate, stock Cage launch, C++ and graph
audits, including the selected-service/subtype and IME-height Binder queries.
Regression 375 passes the complete JVM suite and all 16 APK integration tests
with 48 clean steps. Artifacts: `/tmp/wayland-ime-374-artifacts/` and
`/tmp/wayland-ime-375-artifacts/`.
In-flight invalidation/death stress, clipboard, drag-and-drop and the remaining
M10/M11 work are still pending.

## Clipboard text/HTML checkpoint (2026-09-08)

Package 83 connects stock ClipboardService's monitor seam to the seat-owned
Wayland data device. Stock app access checks, URI ownership validation,
listeners, expiry and grant revocation remain in ClipboardService. Native
imports bypass the outbound monitor; revisions checked under the stock
clipboard lock reject a delayed native read after a newer Android copy.

The native bridge negotiates UTF-8 text and HTML, preserves explicit plain
representations and derives plain text with Android's HTML parser for an
HTML-only offer. MIME negotiation preserves the offered spelling and rejects
unsupported explicit charsets. Each representation is limited to 256 KiB.
Eight concurrent FD transfers have absolute deadlines and cancel with their
offer/source. All descriptor operations use std.Io, including setting incoming
blocking descriptors nonblocking. Native event and Java callback budgets also
include payloads awaiting delivery. An external selection replaces the old
Android contents immediately while its new payload is read.

Publication uses real seat serials and defers while unfocused. Manager/seat
replacement retains the current revision and unpublished copy/clear request;
manager recreation seeds focus from the latest real seat serial. A source marker
avoids replacing stock ClipData with a roundtrip of its own native source.
Peer cancellation/timeouts produce transfer warnings, not false missing-API
reports. Unsupported URI/Intent and multiple-item export remain explicit gaps.

The existing testkit now supports `step exec [observation options] -- COMMAND
[ARG...]`: an argument-vector process with a deadline, followed by the same
cursor-based diagnostics and step journal as UI actions. Regression 382 passes
52 native tests and all 22 testkit tests. Regression 385 passes nine clean
clipboard interoperability steps using real wl-copy/wl-paste. Regression 386
passes 13 clean steps, adding protocol removal, Android copy while unavailable,
manager recreation, native paste and a subsequent native import. Artifacts:
`/tmp/wayland-clipboard-385-artifacts/` and
`/tmp/wayland-clipboard-386-artifacts/`.

Regression 387 passes the 22-step IME, 13-step desktop input, stock Cage launch
and four-step video gates, plus C++ and graph audits. Regression 388 passes the
complete JVM suite and all 16 APK integration tests with 48 clean steps. Artifacts:
`/tmp/wayland-clipboard-387-artifacts/` and
`/tmp/wayland-clipboard-388-artifacts/`. The fixture's compact controls and
scroll container keep all primary actions visible in a 640-by-480 task window.

Clipboard URI staging/grants, drag-and-drop and the remaining M10/M11 work are
not completed by this text/HTML checkpoint.

## Stock decoded video checkpoint (2026-09-08)

Stock MediaCodec surface output now queues independently owned GPU video frames
through libgui Surface/BufferQueue. VAAPI converts supported SDR inputs to RGBX
DMA-BUFs, preserving color standard, transfer and full RGB range metadata. The
shared allocator validates and imports the external descriptors; stock parcels
carry the resulting GraphicBuffers. Completed VPP output owns its storage
independently of the decoder pool. Surface supplies bounded queue pressure,
timestamps, output switching and disconnect behavior.

The APK video fixture decodes actual AVC into SurfaceView underneath an Android
overlay, switches that codec to TextureView, resizes and releases both views.
The `stock-video-test` gate uses the existing headless runner and four semantic
test steps, verifies each view's onscreen pixels and requires clean diagnostics.
Regression 336 passes this gate, `gui-test` and `cpp-audit`. Native tests also
cover external-buffer validation, parcel ownership, exact timestamps, color and
crop metadata, a bounded queue timeout followed by recovery, and FD cleanup.
Artifacts: `/tmp/wayland-video-336-artifacts/`; log: `/tmp/wayland-336.log`.

Two HWUI omissions surfaced during this gate: pending texture updates were
discarded by an Android-only compilation guard, and importing a window buffer
changed GL texture bindings without invalidating Ganesh's binding cache. Package
79 includes the updater guard correction; the host EGL import seam now resets
the affected cache state. No CPU frame-copy fallback was added.

Stock APK regressions 339/340 pass activity input, dialogs, saved-state restore,
TextureView and permission-result delivery. The host logging setup now retains
AOSP's logcat group policy instead of printing every proto-enabled WM trace
group. Cage supplies explicit output-local placement to the existing testkit;
its headless keyboard is available before activities launch.

Package 80 adds a small ActivityStarter seam for the existing host permission
operation. Stock launch authorization precedes it, and ActivityRecord.sendResult
owns delivery. The operation checks the requesting activity/process identity,
returns the administrator-managed grants and creates no system activity process.
Platform installation now binds the parsed package UID as well as PackageState,
so generated ApplicationInfo and resolved components consistently report uid
1000. Regression 341 reaches NewPipe's home screen with clean diagnostics;
its obsolete legacy-launch log assertion is replaced by a visible home-tab check.

Regression 355 extends the video fixture with an AVC frame whose four borders
must be cropped away. FFmpeg's default cropping loses top/left offsets on opaque
hardware frames; the host now retains that rectangle and applies it through VA
VPP. The normalized RGB output reports its actual dimensions, full crop, opaque
Surface format and converted color aspects through MediaCodec. The fixture
checks the declared BT.709 metadata and all onscreen corners. Regression 345's
native gate additionally verifies quarter-turn transforms, negative rotations
and both Surface scaling modes. Artifacts: `/tmp/wayland-video-355-artifacts/`.

Package 81 observes the top activity's main window at the stock WM layout seam.
The task organizer also reads committed visibility when Session reports a change
without scheduling layout. The existing host immersive policy retains fullscreen
while legacy layout flags remain set during temporary control visibility. A
system-authorized presenter operation handles these requests and unmodified F11;
xdg configure remains authoritative. Regression 352 passes all four desktop
NewPipe tests, including paused-video fullscreen, control reveal and explicit
exit, with 18 clean steps. These video cases now use the existing desktop fixture:
NewPipe's phone UI requests orientation changes, which pinned Android deliberately
ignores for resizable freeform tasks. No synthetic rotation is introduced.
Artifacts: `/tmp/wayland-video-352-artifacts/integration/`.

Physical test input now revalidates the selected process-local widget and window
order/focus/geometry, allowing unrelated label changes without accepting a changed
target. Default framework module regression 348 verifies text mutation, disabled
and moved targets, stale process identity and window changes. Regression 353
passes the stock Cage, desktop, mixed-output, suspension and tablet task gates,
plus C++ and graph audits. Artifacts: `/tmp/wayland-video-353-artifacts/`.

Regression 356 passes the complete default JVM suite and all 16 APK integration
tests, with 51 clean steps. Artifacts: `/tmp/wayland-video-356-artifacts/`; log:
`/tmp/wayland-356.log`. Main remains at `f79fbb3`; no newer framework work was
available to rebase during this checkpoint.

At this checkpoint rendered-frame delivery and codec capability reporting were
unfinished; the newer checkpoint above covers that follow-up. Broader HDR policy,
desktop interoperability and M11 remain open; unsupported HDR conversion is
currently rejected.

## Presentation feedback checkpoint (2026-09-08)

The session negotiates presentation-time v1/v2 and retains the compositor's
declared clock domain. Each stock UI DMA-BUF child commit can request actual
presentation feedback independently of its parent's frame callback and the
buffer's release event. Observations retain the configuration generation, frame
number, timestamp, output global identity, refresh prediction, sequence and flags.
Refresh zero remains unknown; neither software callbacks nor Android's requested
frame rate are reported as actual presentation timing.

Each window retains at most four feedback records. When covered-window or resize
pressure replaces an observation, the unobserved count records that loss without
claiming the compositor discarded the content. Actual discarded events have their
own counter. Late feedback cannot replace a newer submission's observation.
Output identities are copied when received, avoiding references to removed output
objects. Invalid nanoseconds and a changed connection clock fail explicitly.

`surface-pacing-test` now checks both supported and absent presentation feedback.
Regression 319 observes all 12 GPU frames in Cage's CLOCK_MONOTONIC domain, with
unknown refresh prediction. The fallback still paces and renders the final image,
while reporting no fabricated timestamps. Regression 320 passes all 7064 stock
Wayland/native build steps and 25 tests, including all 13 surface scenarios,
mixed outputs, suspension, tablet input and the desktop launcher. Regression 321
passes the complete JVM and 15-test APK suites with no runtime issues.
Artifacts: `/tmp/wayland-feedback-{319,320,321}-artifacts/`; logs:
`/tmp/wayland-{318,319,320,321}.log`. Media/desktop bridges, remaining input/output
acceptance cases and the M11 default cutover remain unfinished.

## Task frame pacing checkpoint (2026-09-08)

Stock task output submission now follows each toplevel's compositor frame
callbacks. The queue retains at most one completed pending frame within its
existing two-lease budget and coalesces newer completed frames into that slot.
Callbacks wake only the corresponding queue. A new configuration's first image
can replace retained content without waiting on an older covered-window callback.
Configuration retirement and suspension explicitly wake the queue to release
pending images even when no further producer frame or callback arrives.

Submission, frame callbacks and buffer release remain separate operations:
callbacks permit another submission; only wl_buffer.release permits reuse of
compositor-owned storage. Pinned SF's asynchronous virtual-display producer
returns WOULD_BLOCK under pressure, allowing other outputs to keep composing.
The worker also stops acquiring after any lease abandons its stock consumer,
while continuing to drain returns until shutdown. This removes an intermittent
attempt to acquire from an already abandoned consumer during teardown.

`surface-pacing-test`, also included in `surface-wayland-test`, sends a burst of
12 GPU frames without producer sleeps. It verifies deferred submissions, at most
one submission per callback after the initial image, monotonic frame order,
the final frame's pixels, and complete buffer cleanup. Regression 313 observes
12 submissions and callbacks with 11 deferred attempts. It also passes the
running SF stalled-output isolation gate and stock desktop/launcher interactions.
Regression 314 passes all 7071 Wayland/native build steps and 28 tests, including
suspension, legacy protocol fallback, tablet, mixed outputs and all 12 surface
scenarios. Regression 316 verifies the final consumer-retirement fix against
pacing, delayed fences, suspension and mixed outputs.

The complete JVM suite passes in regressions 315 and 317. Regression 315's APK
failure is the retained NewPipe live-feed parsing error before fullscreen setup,
with no runtime issues; regression 317 passes the unchanged full 15-test APK suite.
Artifacts: `/tmp/wayland-pacing-{314,315,317}-artifacts/`; logs:
`/tmp/wayland-{311,313,314,315,316,317}.log`. Actual presentation-time feedback,
remaining output policy and the other M8–M11 gates remain unfinished.

## Mixed-output task checkpoint (2026-09-08)

`stock-task-output-test` now places two stock APK tasks on separate labwc
outputs, scales only the second monitor to 1.25, and removes that monitor.
Screenshot checks verify each task's five color markers independently, including
the 550×375 scaled buffer and its 440×300 logical presentation after evacuation.
The private presenter snapshots prove stable task identity, synthetic geometry,
shared density and the first task's output generation. Grouped activity launch,
sibling removal and complete buffer teardown still pass after unplug.

The compositor retains the second task's fractional preference after evacuation;
the existing viewport correctly presents its retained 550×375 buffer at the
surviving monitor's 440×300 logical size. The test accepts that protocol behavior
and validates pixels instead of inventing a new scale hint. Screenshot placement
coordinates stay inside the harness and never become Android global positions.

An initial fixture used MoveTo without selecting the monitor. Pinned labwc's
view_move_resize saves placement before its later output discovery, a race noted
in that source. A layout change consequently moved the first task to the other
monitor. The fixture now explicitly uses MoveToOutput before MoveTo; the sibling
isolation assertion remains intact. Protocol trace: regression 308.

Regression 309 passes the focused gate; its screenshots and state log are in
`/tmp/wayland-output-309-artifacts/`. Regression 310 passes all 6991 build steps
covering the new gate, existing Cage and desktop stock tasks, and graph audit.
Logs: `/tmp/wayland-{307,308,309,310}.log`. Frame pacing and the remaining M8–M11
gates remain unfinished.

## GPU fence checkpoint (2026-09-08)

The surface worker now normalizes the pinned libsync `-ETIME` timeout to the
private queue API's Android `TIMED_OUT`. A real pending GPU acquire fence exposed
the mismatch: the first 10 ms wait previously failed the queue instead of
retrying within its one-second deadline. Pinned AOSP Fence behavior is unchanged.
The worker exposes the frame currently waiting on a fence for queue diagnostics.

A hardware Vulkan fixture exports a real sync_file from a submission delayed by
a host-controlled event. Its bounded release worker prevents a failed harness
from leaving GPU work waiting indefinitely. It requires Vulkan 1.1 hardware and
exportable SYNC_FD fences; an absent GPU capability fails the gate. The development
shell supplies the Vulkan loader. Supply the host Vulkan ICD with `VK_DRIVER_FILES`
when the loader cannot discover it, alongside the existing EGL/GBM environment.

`surface-fence-test` checks three cases, also included in `surface-wayland-test`:
frame 7 remains unpresented while the owner loop responds, then all 12 frames
present with correct pixels after release; shutdown returns all buffers while
the GPU fence remains pending; and the one-second wait deadline fails explicitly
with TIMED_OUT and releases resources before the producer is allowed to continue.
Both retirement cases require further producer calls to return DEAD_OBJECT.
`gui-test` additionally forces a stock BufferQueue slot to carry a pending GPU
release fence through the control Parcel and stock producer Bp/Bn transport. The
dequeued fence remains pending until explicit GPU release.

Regressions 302 and 303 pass these focused gates on the Radeon 8060S/RADV.
Regression 304 passes all 7012 stock graphics/task build steps, including all 11
surface scenarios, BufferQueue, both output lifecycle cases, and Cage, labwc and
tablet APK task presentation. Fence logs and binary/library hashes are retained
under `/tmp/wayland-fence-304-artifacts/`; build logs are
`/tmp/wayland-fence-302.log` and `/tmp/wayland-{303,304}.log`.
Regression 305 passes the complete JVM suite; one APK live-feed setup failed
with NewPipe's website parsing error even after its existing single retry, with
no runtime issues. Regression 306 repeats the unchanged full APK suite and passes
all 15 tests. Both runs retain their integration artifacts under
`/tmp/wayland-fence-{305,306}-artifacts/`.
Mixed-output task pixels, output pacing and the remaining M8–M11 gates remain.

## Tablet input checkpoint (2026-09-08)

The shared Wayland session now owns tablet-v2 discovery and bounded tool, tablet
and pad proxy lifetimes. Up to 16 logical tools receive generation-checked Android
input devices. Frame assembly preserves enter/contact/button/leave ordering and
uses the final coordinates/pressure from each protocol frame, including when
DOWN precedes axis updates. Pen and eraser identity, pressure, distance, tilt,
barrel rotation, slider and wheel values cross the Zig Binder transport. Stock
InputDispatcher retains routing, modal-window checks and keyboard focus.
Pad controls are not advertised as Android stylus tools; their bounded protocol
object descriptions are consumed and released.

Drawing tablets advertise the pinned InputReader's MOUSE | STYLUS source while
retaining their pen/eraser tool type. The native acquisition adapter supplies
cursor coordinates absent from Java-created combined-source MotionEvents.
Tablet mouse/lens tools preserve mouse button ordering and Back/Forward keys.
Resize, suspension, removal and pointer capture cancel active streams; capture
suppresses absolute tablet input until the compositor releases the lock. Stale
tool generations cannot revive devices or complete an earlier contact.

Per-tool cursor requests retain the stock input-window ownership check and also
require the current task generation, device generation and proximity serial.
Cursor-shape and bounded immutable SHM bitmap/animation paths share the existing
cursor implementation. Capture hides tablet cursors; proximity loss and tool
removal release their images.

The stock task organizer also consumes TaskStackListener description changes.
Pinned WMS queues ordinary TaskOrganizer updates until layout/animation, so that
stream alone leaves an idle desktop title stale. Description callbacks update
metadata while retaining organizer-owned geometry.

A test-only source in the pinned labwc fixture drives tablet-v2 through the
existing shot.sh harness. The stock APK gate checks pen/eraser axes, local/raw
coordinates in the second task, button ordering, distinct rendered marks,
custom cursor colors/hotspots, focus preservation and cancellation on removal.
No tablet commands or fixture package checks enter production framework code.
Regression 289 passes this gate and input-channel JNI tests; 292 passes the
Android input smoke including capture and tablet mouse keys. Native regression
291 passes all 25 tests, including frame pressure and cursor ownership (its JVM
failure exposed a missing key display ID, fixed and verified by 292).
Regression 293 passes all 7026 stock task, desktop launcher, surface and output
build steps; the launcher's 13 interaction steps have no runtime issues.
Artifacts: `/tmp/wayland-tablet-293-artifacts/`; logs:
`/tmp/wayland-tablet-{289,291,292,293}.log`.

Regression 294 passes the complete JVM suite. Its APK run and repeat 296 each
encountered a transient NewPipe live-feed extraction error before fullscreen
setup, with zero runtime issues. The integration setup now performs one normal,
recorded RETRY action only for that exact error and retains its screenshot.
Regression 297 passes all 15 APK tests (46 interaction steps, zero runtime
issues) and all 7000 integration build steps. Artifacts:
`/tmp/wayland-tablet-297-artifacts/`; logs `/tmp/wayland-tablet-{294,295,296,297}.log`.

## Compositor suspension checkpoint (2026-09-08)

The task presenter negotiates xdg-shell up to v6 and applies the optional
suspended state only after its xdg-surface configure is acknowledged. Explicit
suspension cancels pointer/touch/key streams, revokes pointer capture, suppresses
new input and cursor animation, and returns completed producer frames without
attaching them. The last compositor image remains retained. A private callback
sets the stock TaskOrganizer WCT hidden flag; pinned WMS owns visibility,
reordering and activity pause/stop/start/resume. Resumption restores only actual
seat keyboard focus, with a maintained held-key snapshot. Unknown occlusion and
minimization remain conservatively visible to Android.

Advertising v6 does not guarantee a compositor sends this optional hint. Pinned
labwc 0.20.1's `xdg_toplevel_view_minimize` is a no-op. The existing shot.sh
harness therefore supports a test-only labwc binary whose small source patch
emits the standard suspended configure. Its Nix build uses artzig's locked
nixpkgs and is a dependency only of the protocol-fixture targets.

Regression 275 verifies real minimized/resumed pixels and Android lifecycle
ordering in three cases: ordinary labwc v6 without hints, the fixture with v6
hints, and the same fixture forced to v2. Explicit suspension stops the activity
and resumes the same instance; the two unknown cases retain Android visibility.
Task-shell tests cover duplicate and stale visibility callbacks. Regression 276
passes Cage, cold launch, desktop launcher, all eight surface scenarios, both
output lifecycle scenarios, native tests and graph audit. Regression 277 passes
all 7091 JVM/APK/native/surface/graph build steps, including 15 APK tests with 47
interaction steps and zero runtime issues. Logs:
`/tmp/wayland-suspension-{273,274,275,276,277}.log`; retained artifacts:
`/tmp/wayland-suspension-277-artifacts/`.

## Keyboard layout pressure checkpoint (2026-09-08)

The ordered seat queue retains at most 16 XKB layout snapshots and charges at
most 32 MiB of their serialized source sizes across pending and borrowed batches.
This supplements the existing 4096-event and 16 MiB individual keymap limits.
Group snapshots sharing a map are conservatively charged separately. These are
source-size/reference limits, not a measurement of libxkbcommon's parsed heap.
Overflow fails the session explicitly rather than dropping a layout and
interpreting following keys with the wrong map. Drain and task removal release
the corresponding references and budget.

Regression 272 passes native pressure/ownership/chronology tests, Cage/labwc
stock task gates and graph audit (7019 successful build steps). Log:
`/tmp/wayland-keymap-pressure-272.log`.

## Resize input checkpoint (2026-09-08)

Production pointer, touch, wheel and relative-motion events now wait for a
presented producer whose configuration matches the acknowledged compositor
geometry. Preparing an Android-initiated relocation also closes that gate before
the WCT changes input windows. The presenter cancels active gestures before the
bounds callback, processes queued protocol events before accepting new frames,
and restores pointer hover after the first compatible frame. Inspection frame
requests and cursor changes use the same readiness condition.

Touch cancellation removes only the affected task's contacts using pinned
MotionEvent FLAG_CANCELED and native splitAction semantics. Other tasks retain
their contacts; physical releases from the canceled stream cannot complete a
click. Mouse Back/Forward cancellation uses InputState's canceled key-up flag.

The labwc gate presses before fullscreen, holds replacement frames, verifies
cancellation at the APK boundary, attempts real compositor input while pending,
and checks input resumes after presentation. Regression 270 passes this gate,
Cage, stock cold launch, desktop launcher, native tests and graph audit. The
Android input smoke additionally splits a canceled contact between two windows
and verifies the surviving contact's move/up sequence. Regression 269 passes
7091 build steps, including all 15 APK tests (46 steps, zero runtime issues).
Regression 271 repeats the complete gate with the final Back/Forward flag
addition: 7091 successful build steps and the same 15 APKs / 46 issue-free steps.
Logs: `/tmp/wayland-resize-input-{267,268,269,270,271}.log`; retained artifacts:
`/tmp/wayland-resize-input-271-artifacts/`.

## Bitmap cursor checkpoint (2026-09-08)

Stock pointer icons now fall back to immutable Wayland SHM cursor surfaces when
cursor-shape is unavailable or the application supplies a bitmap. Loading uses
the pinned PointerIcon resources, animation frames and HWUI shadow filter. The
host seam preserves source bitmap ownership, converts straight alpha to native
premultiplied ARGB and bounds dimensions, frame count and total transfer size.
Only the pointer-focused task can apply an icon. Animation advances on the
session timer even while the application produces no frames.

Regressions 263–265 pass stock cold launch, Cage/labwc task presentation, the
desktop launcher, native tests, graph audit and eight surface scenarios. The
launcher forces cursor-shape off and verifies custom cursor colors and hotspot
in a compositor screenshot; the surface gate observes both animation frames.
Regression 266 passes the complete JVM and APK suites, native/surface tests and
graph audit (7091 build steps; 15 APK tests, 46 interaction steps, zero runtime
issues). Logs: `/tmp/wayland-cursor-{263,264,265,266}.log` and
`/tmp/wayland-cursor-266-summary.log`. Remaining M8–M11 work includes production
input during pending configures, tablet input and desktop interoperability.

## Resize pressure checkpoint (2026-09-08)

The labwc gate now sends 64 alternating maximize/restore requests while holding
replacement frames. It waits for each compositor configure, allowing Android
bounds synchronization and buffer production to lag behind. Every configure
advances its generation; held frames never advance the presented generation.
The final acknowledged generation commits and displays with correct pixels.

An inspection-only resource snapshot reports live tasks, active/pending queues,
Wayland imports, host graphics buffers and process descriptor count. Regression
259 coalesces the 64 configures into two Android output updates. It starts at
`[1,1,1,1,13]`, peaks at `[1,2,1,1,14]`, and returns to `[1,1,1,1,13]`.
The gate enforces queue/import bounds throughout the storm and checks that
buffers/descriptors return within their fixed post-presentation allowance.

Regression 260 also passes Cage, the desktop launcher, native tests and graph
audit. Logs: `/tmp/wayland-storm-{259,260}.log`; artifacts:
`/tmp/wayland-storm-259-artifacts/`. This adds configure-pressure coverage; real
delayed GPU fences, mixed-output task pixels and the other M8–M11 gates remain.

## Configure generation checkpoint (2026-09-08)

The presenter now acknowledges each xdg-surface serial before publishing the
associated logical size, pixel size and a monotonic configuration generation.
Task configuration remains in Android logical coordinates with stable shared
density. Fractional preferences change the private SurfaceFlinger output size.
State-only configures retain valid same-geometry content.

Each synchronized task configuration prepares a fresh bounded consumer queue.
The stock SurfaceControl transaction changes its private display producer with
bounds/projection; SurfaceFlinger disconnects the previous producer. The
presenter switches queues after that transaction commits. At most one active
and one pending queue exist per task, with two acquired leases per queue.
Old-generation frames are released without attaching, including an A-B-A resize
whose dimensions match again. Inspection input carries its captured generation
and is rejected when it no longer matches committed/presented content.

While Android's new frame is pending, the previous committed image remains on
the compositor and scales to the acknowledged window geometry. Producer
retirement abandons Android reuse without detaching that image. Child viewport
commits and parent configure-only transactions progress independently of new
buffers. Acquire-fence waits retain the one-second limit but check shutdown in
10 ms slices. Private queues validate actual buffer dimensions; inspection
reports the dimensions of the buffer presented, not merely the desired size.

Regression 252 verifies actual labwc pixels while replacement frames are held,
then fullscreen/maximize/restore, A-B-A stale input rejection, and 1.5x SF output
(440x300 logical, 660x450 buffer) without changing shared Android density.
Regression 254 passes Cage, the desktop launcher, cold launch, input dispatch,
task-shell coalescing, native tests, seven Surface scenarios, output lifecycle
and graph audit. Regression 255 passes the JVM suite and all 15 APK tests
(46 steps, zero runtime issues). Logs: `/tmp/wayland-generations-{252,254,255}.log`.

Repeated cold launch exposed an organizer-handover race: pinned
TaskOrganizerController removes the prior organizer by migrating its leashes in
WM's pending animation transaction. A later application of that old position
can overwrite the new organizer's already-committed transform. Failure 256's
WM and SF dumps show Android bounds at (0,0) with the SF task still at (453,27).
The new organizer now uses the stock WMS animation/surface/input synchronization
barrier before submitting its first independent transform. Regression 258 passes
cold launch, Cage, labwc and the desktop launcher with that fix; six additional
consecutive cold handovers pass in `/tmp/wayland-cold-handover-258-{0..5}.log`.
Artifacts and the before-fix WM/SF dumps are retained under
`/tmp/wayland-generations-258-artifacts/`. Resize storms, delayed GPU fences and
the remaining M8–M11 gates are still pending.

## Wayland output discovery checkpoint (2026-09-08)

The shared session now tracks up to 64 output records with stable storage,
committed mode/scale/transform metadata and bounded names. Surface enter/leave
membership and registry removal update every window; removing an output clears
its membership before its slot can be reused. Protocol destruction respects
wl_output version. Output-capacity and malformed-state failures propagate through
both session and legacy window event loops.

Surface scale follows fractional preferences, then wl_surface v6 integer
preferences (initially one), with the maximum entered-output scale as the older
protocol fallback. Child surfaces use the toplevel's logical mapping. Physical
output and preferred surface transforms are recorded as hints; buffers remain
normal-oriented and the compositor handles output rotation. Shared Android
configuration/density is not rewritten when desktop outputs change.

The `wayland-output-test` target uses the existing shot.sh/labwc harness with two
headless outputs and wlr-randr. It verifies 1.5x fractional preference, integer
2x fallback with fractional/core-v6 hints disabled, removal, reappearance with a
new registry identity, new windows on the survivor at 1.25x/2x, and rotation
metadata. Labwc 0.20.1 retains an evacuated surface's old scale preference until
it sends another hint, and can restore that window to a re-enabled output. The
tests preserve those compositor choices rather than deriving desktop placement
or overriding per-surface preferences from output metadata.

Regression 242 passes both live output scenarios; 243 passes stock Cage/labwc,
desktop launch, seven Surface scenarios, native tests and graph audit. After the
final v6-default/error-propagation adjustment, 244 passes output tests, all 15 APK
tests (46 steps, zero runtime issues), seven Surface scenarios, native tests and
graph audit; 245 passes all three stock task-window gates (desktop: 13 steps,
zero runtime issues). Logs are `/tmp/wayland-outputs-{242,243,244,245}.log`.
Artifacts are retained under `/tmp/wayland-outputs-{244,245}-artifacts/`.

This closes output discovery and scale-hint ownership. Higher-resolution stock
task composition, configure/content generations, delayed-frame rejection and the
remaining M8–M11 acceptance gates are still pending.

## Virtual-pointer v1 checkpoint (2026-09-08)

The presenter now issues `create_virtual_pointer_with_output` only when the
negotiated manager version is at least two. Version one uses the original
seat-based creation request. The existing desktop harness caps negotiation at
version one and verifies the actual version before accepting its thirteen
physical input steps. Production still negotiates the highest supported version.
Regression 234 passes this gate with zero runtime issues, plus the Cage and
labwc task tests at their normal protocol version. Artifacts are retained in
`/tmp/wayland-pointer-v1-234-artifacts/`; log:
`/tmp/wayland-pointer-v1-234.log`.

## Stock night configuration checkpoint (2026-09-08)

Stock task sessions now start the pinned UiModeManagerService and
DreamManagerService. Application night-mode changes use the real ATMS package
updater and persistence, and global changes use ATMS configuration delivery.
The legacy backend retains its UI-mode adapter until the final cutover.

Dream policy reads the existing battery authority through BatteryManagerInternal.
InputManagerInternal forwards supported operations to the existing seat and
InputDispatcher bridge; pulse-gesture configuration follows AOSP's no-control-file
behavior. Unhosted local operations retain structured unsupported diagnostics.
The single-user UserManager seam preserves the stock caller-identity check used
by UI-mode policy. Dream flags come from the pinned aconfig declaration.

The cold-process test verifies application/activity/WPC configuration agreement,
night-mode relaunch ordering and saved-state restore, handled `uiMode` callbacks,
global changes preserving an app override, and clearing the override to inherit
global configuration. These assertions also pass in Cage and labwc task windows.

Regression 231 passes stock boot/cold launch; 232 passes Cage, labwc, the desktop
launcher (13 steps, zero runtime issues) and native input dispatch. Regression
233 passes the full JVM/native suite, all 15 APK tests (47 steps, zero runtime
issues), seven Surface/Wayland scenarios and build audits. Logs are
`/tmp/wayland-stock-night-{231,232}.log` and
`/tmp/wayland-stock-night-regressions-233.log`; UI artifacts are retained under
`/tmp/wayland-stock-night-232-artifacts/`. Regression 230 exposed and was fixed for
package-private service startup and a test using the wrong Binder getter.

Package 77 adds ten unchanged AOSP sources and one generated flag declaration;
archive SHA-256 is
`c32ff652e594de2a006beb1eb790aec0935a72a54841e3015e5f5e2351b865d0`.
The audit remains 15 stubs, ten pending and five survivors. Output/configuration
generations, mixed-scale output handling and the remaining M8–M11 gates remain.

## Stock grammatical configuration checkpoint (2026-09-08)

Stock task sessions now start the pinned GrammaticalInflectionService and its
three helper classes. Package overrides use the stock ATMS package updater,
WindowProcessController, activity configuration and PackageConfigPersister. The
legacy window backend retains its adapter until the final cutover.

The cold-process gate sets a package override before the first launch and checks
application resources, activity resources and AMS's ATMS-backed configuration
getter. A subsequent unhandled change must pause/save/destroy/create/resume and
restore instance state. A second activity declaring `grammaticalGender` must
receive exactly its configuration callback without recreation. Closing it restores
the original activity, and clearing the override restores inherited configuration.
These checks also execute inside the Cage and labwc task-window gates.

Regression 227 passes stock boot and cold launch. Regression 228 passes Cage,
labwc, the desktop launcher (13 steps, zero runtime issues) and JNI input dispatch.
Regression 229 passes the complete JVM/native suite, all 15 APK tests (47 steps,
zero runtime issues), seven Surface/Wayland scenarios and build audits. Logs are
`/tmp/wayland-stock-grammar-{227,228}.log` and
`/tmp/wayland-stock-grammar-regressions-229.log`; retained UI artifacts are in
`/tmp/wayland-stock-grammar-228-artifacts/`.

Package 76 adds four unchanged AOSP sources; archive SHA-256 is
`e9b8aea3c702be40547289adb9d9f03e73d97f4b93de8bac57ae3ae51191c642`.
Stock night-mode integration and the remaining M8–M11 gates are still pending.

## Permission/AppOps framework rebase checkpoint (2026-09-08)

Rebased onto main `f55fd3e`, retaining its stock AppOps, permission manager,
sensor, media, network validation and bundled DownloadProvider work. The main
worktree was left untouched. The stock WM permission policy has a separate name
from the package permission backend, and native PermissionController uses AMS's
stock AppOps authority. Source/generator/JNI declarations are deduplicated.

Package identities remain assigned at installation, before cold process launch;
package callbacks and AppOps notifications run after the package lock is released.
The permission facade and its local dependencies now precede platform/image APK
scanning, so PackageParser2 obtains split permissions without an early missing
`permissionmgr` error. Application processes retain their own storage environment.

WM process-state changes now reach stock AppOps, including the aggregate UID
state on attachment and death. The stock boot gate verifies foreground audio-focus
access is denied before an activity exists, allowed after resume, and denied again
after finish while the process remains alive. Default process capabilities follow
pinned OomAdjuster; foreground-service-specific while-in-use capability grants are
still a separate service-policy gap.

Package 75 contains 55,852 source files and 84 patches (SHA-256
`731f52c2534765d205db2714173e1a64b98d10749b3364fd94d4e794c8a80b02`).
The live stub audit now records 15 stubs: ten pending and five survivors, with a
pending budget of ten.

Validation:

- Regression 222: framework modules, native tests and graph/C++ audits pass.
- Regression 224: stock client boot, cold launch, Cage/labwc task windows,
  desktop launcher and native input/SF integration pass.
- Regression 225: the corrected permission-before-scan bootstrap passes all
  stock boot/task gates; the desktop test completes 13 steps with zero issues.
- Regression 226: full JVM/native tests, all 15 APK tests (47 steps, zero runtime
  issues), all seven Surface/Wayland scenarios and build audits pass.

Logs are `/tmp/wayland-main13-{222,stock-224,bootstrap-225,regressions-226}.log`;
stock UI artifacts are retained under `/tmp/wayland-main13-stock-225-artifacts/`.
The native-input artifacts also remain in the regression-224 copy.

The next M9 closure is now smaller: stock grammatical inflection needs four
additional sources without a compile blocker. Stock UI mode needs four further
sources and the pinned current-user check; its dream collaborator has a seven-source
closure without compile blockers. These services should own configuration changes
in ATMS/WPC instead of sending client-only deltas. M8–M11 remain incomplete;
this checkpoint does not enable the stock task path by default.

## Stock evdev layout checkpoint (2026-09-07)

The manually maintained scan-code switch is replaced by pinned Generic.kl and
KeyLayoutMap::loadContents/mapKey. The resource is packaged in both JVM and ART
jars. Wayland snapshots now cover all 768 supported evdev scan positions; the
private initial snapshot version is 2. Its roughly 195 KiB payload remains below
the transport's 1 MiB Parcel limit. XKB remaps still take precedence, including
the ISO extra character key's separate Android identity.

Layout flags survive key-down, release and cancellation into InputDispatcher's
normal policy path. Generic.kl FUNCTION keys receive native function metadata;
explicit XKB remaps clear the previous mapping's flags. Physical Fn state remains
visible across compositor modifier snapshots. This also corrects scan 120 from
the old APP_SWITCH mapping to the pinned RECENT_APPS mapping.

Gate 216 passes framework-module, native and JNI compilation. Gate 217 passes
the last-scan-position XKB test, Cage/labwc task gates, the desktop's 13 clean
steps, and JNI policy/receiver checks for FUNCTION on both down and release.
Module checks include voice-assist/macro mappings, unmapped scans, high-scan
capabilities, remaps and cancellation. Artifacts:
`/tmp/wayland-stock-key-layout-success-217/`; logs:
`/tmp/wayland-stock-key-layout-216.log` and
`/tmp/wayland-stock-key-layout-stock-217.log`.

Package 74 has 55,587 sources, 215 symlinks and 79 patches. Its SHA-256 is
`806d5f0cd1a9c4fee8af28b2a225d29677df5778fa713066ae3afbfb2f3ed148`.
Regression 218 passes full JVM/native, all 15 APK integration tests (49 steps,
zero runtime issues), all seven Surface/Wayland scenarios and build/source audits.
Log: `/tmp/wayland-stock-key-layout-regressions-218.log`. Stylus, remaining
visibility/capture cases and M9–M11 remain incomplete.

## Logical seat keyboard checkpoint (2026-09-07)

A keyboard-capable Wayland seat now publishes Android device 1, with an external
keyboard descriptor, display-0 association and a device-1 character map. Device
-1 remains the fallback. Keyboard edges/cancellation use device 1; mouse
back/forward keys use pointer device 0. Capability changes replace immutable
device snapshots and remove device 1 when the keyboard capability disappears.
The alphabetic classification follows pinned EventHub's Q-key capability check;
WMS now observes the keyboard in its stock configuration computation.

Module 212 passes map-generated event IDs, source/device key queries, immutable
snapshots, alphabetic/non-alphabetic layouts and keyboard removal/fallback tests.
Stock gates 214 pass Cage/labwc cold launch and the 13-step desktop interaction
sequence, with WM reporting QWERTY before APK installation. Artifacts:
`/tmp/wayland-keyboard-inventory-success-214/`; logs:
`/tmp/wayland-keyboard-inventory-{212,stock-214}.log`.

The cold-launch query exposed missing AMS.getConfiguration delegation. It now
calls the pinned ATMS implementation for the calling process; the legacy host
returns a copy of the last sent process configuration (or its global fallback),
with userSetLocale cleared. Regression 215 passes full JVM/native and all 15 APK
integration tests (49 steps, zero runtime issues), including copy isolation and
preservation of a calling process's grammatical-gender override. Log:
`/tmp/wayland-keyboard-inventory-regressions-215.log`.

The compositor exposes an aggregate seat, not physical USB/Bluetooth inventory.
This does not complete extended scan-code/media mappings, stylus support or the
remaining M8–M11 work.

## Task-scoped keyboard focus checkpoint (2026-09-07)

The hosted seat now supplies the actual WM Task Binder identity to the pinned
InputDispatcher. Window-to-task membership accompanies SF input metadata under
the dispatcher lock. Stock FocusResolver sees only the keyboard seat's current
task candidates; pointer/touch routing retains the complete window list. Keys
retain their originating task and focus epoch through policy delays, repeats and
fallbacks. Leaving and returning to the same task cannot revive an old queued
key. Ordinary unscoped InputReader/privileged injection paths remain available.

Native gate 207 passes all 11 dispatcher tests, including five task-focus tests:
waiting for task metadata, delayed A→B→A delivery rejection, same-task popup
retargeting, independent touch through keyboard withdrawal, and held-key/repeat
cancellation. The delay test uses a ten-second stale-event timeout so the stock
age limit cannot mask an authority failure. Log:
`/tmp/wayland-task-focus-native-207.log`.

Live gate 209 passes all 13 desktop steps with zero per-step runtime issues:
open another task, type only into its editor, finish it, and resume typing in the
original task. The final screenshot shows counter 2 and editor `abC`. Artifacts:
`/tmp/wayland-focus-success-209/`; log: `/tmp/wayland-task-focus-stock-209.log`.
Cage/labwc task and dispatcher JVM gates pass in 208. That run also exposed an
existing two-phase controller action rejecting its own tap's accessibility
changes; tap+key/text now validates once and retains one ordered input barrier.

The live tests exposed two sequencing defects fixed at their host boundaries:
WM display attachment requests traversal only after ATMS creates the default
DisplayContent; frame inspection counts actual callbacks per task, allowing a
visible task to progress while a covered task receives no callbacks. An existing
callback is never attributed to a newer request. These are global progress
counts, not task-specific presentation or scanout acknowledgements.

Package 73 contains 55,586 sources and 79 patches, including the hosted dispatcher
seam. Regression 210 passes full JVM/native, all 15 APK integration tests
(45 steps, zero runtime issues), all seven Surface/Wayland scenarios, native
input/SF integration and build/source audits. Log:
`/tmp/wayland-task-focus-regressions-210.log`. Physical keyboard inventory and the
remaining M8–M11 behavior are still unfinished.

## Bounded physical typing checkpoint (2026-09-07)

Task-addressed typing validates the entire request before producing keys, then
drains compositor events in groups of at most 128 characters. Shifted input can
produce six seat callbacks per character; a maximum-size 1,024-character request
must not overrun the 4,096-event queue. Each group rechecks focus and accepted
geometry. The action retains one final ordered completion barrier. A failure
following partial delivery reports unknown completion and must not be retried.

Regression 201 passes native checks and all nine stock desktop diagnostic steps
with zero runtime issues. The new physical request types 1,024 uppercase letters
and immediately verifies the exact editor contents after idle, then restores
`ab` semantically. The final screenshot was visually checked (counter 2, text
`ab`). Artifacts: `/tmp/wayland-bounded-type-success-201/`; log:
`/tmp/wayland-bounded-type-201.log`.

Regression 202 passes full JVM/native, all 15 APK integration tests
(49 diagnostic steps, zero runtime issues), and all seven Surface scenarios:
normal, backpressure, producer/consumer death, broker loss and both pointer-capture
capability paths. Log: `/tmp/wayland-bounded-type-regressions-202.log`.

## Physical keyboard state checkpoint (2026-09-07)

WMS's key/scan state queries now read immutable snapshots of the compositor
keyboard. Source/device filtering, supported scan positions, remapped Android
keys and aggregate DOWN/UP answers follow pinned InputReader/EventHub semantics.
Wayland supplies physical state only while this session has keyboard focus;
queries return UNKNOWN outside that scope and for unavailable keys/devices.
Inherited enter keys remain down without invented Android key presses. Delivery
cancellation, including repeat-policy changes, does not imply physical release.
Keys received before a committed projection update state without producing an
unmatched Android stream. Switch state remains UNKNOWN because wl_seat provides
no corresponding switch state.

Regression 198 passes the module gate. Regression 199 passes full JVM/native and
all 15 APK integration tests (49 diagnostic steps, zero runtime issues), including
remapped modifier aggregation, inherited state, source rejection, pre-projection
edges, cancellation versus release and focused-task removal. Regression 200 passes
stock desktop, Cage/labwc task gates and the native-dispatcher Java gate. Logs:
`/tmp/wayland-key-state-198.log`, `/tmp/wayland-key-state-regressions-199.log`, and
`/tmp/wayland-key-state-stock-200.log`.

This closes focused key-state queries for the current keyboard map. Full physical
keyboard inventory, cross-task focus acknowledgement and remaining M8–M11 gates
are still pending.

## Inspection peer isolation checkpoint (2026-09-07)

Session inspection validates each complete app tree before merging it. Invalid
or duplicate window/node identities, cross-window references, malformed child
arrays and oversized contributions make only that process unavailable. Healthy
windows remain inspectable and actionable. A failed peer receives no actions or
physical-input target, and recovering it preserves its live window identities.
Truncated trees may reference omitted children, matching the collector contract.

Regression 196 passes the framework module gate, including eight malformed-peer
cases, unchanged-failure revision stability, healthy-peer actions and recovery.
Regression 197 passes stock desktop's seven semantic/physical diagnostic steps
with zero issues and both Cage/labwc multi-task inspection gates. Logs:
`/tmp/wayland-peer-isolation-196.log` and
`/tmp/wayland-peer-isolation-stock-197.log`. Slow-peer scheduling, native journal
aggregation and the remaining M11 recovery/default-cutover work are still pending.

## Early seat connection checkpoint (2026-09-07)

The task shell now connects the session keyboard before registering its organizer.
A synchronous snapshot seeds layout, key capabilities, modifiers and repeat
policy before any APK window can be created. Subsequent keyboard state callbacks
have session scope and remain ordered with task input on the shared Binder
endpoint, including periods with no task windows.

Regression 194 passes the stock desktop launcher, Cage/labwc task gates and native
checks. The trusted cold-launch driver verifies the compositor character map and
key capabilities before installing the APK. After both native task windows are
removed, the same compositor helper switches US → German → US; Android publishes
new device generations and the corresponding Shift+2 character each time. The
existing pointer lifetime and task pixel gates also pass. Regression 193 passed
the live gates but found an outdated native test invocation, corrected in 194.
Log: `/tmp/wayland-early-seat-194.log`; retained artifacts:
`/tmp/wayland-early-seat-success-194-{cage,labwc}/`.

Regression 195 passes the full JVM/native and all 15 APK integration tests
(45 diagnostic steps, zero runtime issues):
`/tmp/wayland-early-seat-regressions-195.log`.

Cross-task focus acknowledgement, complete key-state queries and the remaining
M9–M11 work are still pending; this checkpoint does not complete M8.

## Task-addressed physical input checkpoint (2026-09-07)

The session controller now validates the observed process generation, window
revision and WMS task ownership before injecting compositor input. The presenter
checks the task generation and accepted geometry again. Test output placement is
supplied explicitly by the existing labwc harness; synthetic Android coordinates
are transformed into that placement. Before sending a pointer button, the
presenter verifies the compositor's actual pointer target and local coordinates.
Unknown text is rejected before any supported prefix is typed.

Regression 179 passes the desktop launch gate's seven diagnostic steps: semantic
counter/dialog actions followed by physical counter click, editor click, typing
and Backspace. Every step has zero runtime issues. The retained screenshot shows
counter 2 and editor text `ab`; it was visually inspected. Stock cold launch,
Java input channels and native checks also pass. Log:
`/tmp/wayland-physical-editing-179.log`; desktop artifacts:
`/tmp/wayland-physical-success-179/`.

Regression 180 passes the full JVM, native and complete 15-APK integration suite
with zero runtime issues in its diagnostic steps. Log:
`/tmp/wayland-physical-regressions-180.log`.

These actions exposed and fixed three host boundary gaps. Boot completion now
uses ATMS's screen/input enable path after AMS boot flags change. Built-in cursor
requests retain stock InputDispatcher's pointer/window validation and use Wayland
cursor shapes (or an explicitly hidden cursor). Channel removal tolerates an
already removed channel, matching the pinned native InputManager JNI. The empty
IME service returns the stock no-bound-method result for software keyboard
visibility requests. XKB terminal control characters no longer become editable
text: Backspace/Delete/Escape retain editing identities and Enter maps to newline.

This checkpoint does not complete input or IME support. Cross-task focus
acknowledgement, full seat/device publication,
custom cursor bitmaps, text-input composition and the remaining M9–M11 work are
still pending. The no-software-IME branch is the keyboard-only fallback, not the
future compositor text-input bridge.

Regressions 181–182 add an ordered test-input barrier: a compositor roundtrip
precedes a sequence callback behind the seat events on their shared Binder
endpoint. The shell records the sequence after enqueuing those events. The
controller then uses stock InputDispatcher.waitForIdle outside WM/registry locks
before observing the app and applying the existing stable-tree/frame criteria.
This waits for processing and channel acknowledgements; it does not claim that
every physical event must be accepted by Android policy. Expected UI state remains
part of the test. A failed input roundtrip reports unknown completion rather than
promising that nothing was sent.

Regression 182 passes the dispatcher gate with deliberately unacknowledged keys,
then successful idle after receiver completion. Its seven-step desktop gate
checks counter/editor contents immediately after settling, without a later wait
masking delayed input. Native checks pass. Logs:
`/tmp/wayland-input-barrier-181.log` and
`/tmp/wayland-input-completion-182.log`; dispatcher evidence:
`zig-out/test-results/task-output-jvm/run/client.log`.
Regression 183 passes the full JVM/native and all 15 APK tests (47 steps, zero
runtime issues): `/tmp/wayland-input-completion-regressions-183.log`.

## Seat device publication checkpoint (2026-09-07)

The hosted input service publishes the Wayland seat's available mouse (device 0)
and touch (device 2), matching the existing event stream. Android's virtual
keyboard remains the fallback KCM. Capability changes rebuild immutable device
records, advance their generations and notify stock WMS plus input-device
listeners outside the service lock. Relative-pointer source support follows the
negotiated pointer-constraint/relative-pointer protocols. Old source generations
cannot restore removed devices.

Seat capability events now have session scope in the ordered native queue and
shared Binder callback. They survive removal of the final task; cancellation
precedes capability loss. Removing the selected wl_seat releases its input
objects and test devices and publishes the loss. Test injection rejects a missing
selected seat rather than implicitly selecting another compositor seat.

Regressions 187–189 pass desktop physical/semantic actions and both stock task
compositor gates. The live device test starts with only a keyboard, creates a
pointer, destroys it, and creates it again, then repeats after both native task
windows are gone. Cage reports capability masks 3 → 2 → 3 (pointer+keyboard,
keyboard only, pointer+keyboard); Android removes the pointer and publishes a
newer generation on return. Labwc retains mask 11 (including relative pointer)
after virtual-device destruction; Android correctly retains the aggregate device.
The earlier 185–186 assertions assumed hardware-device lifetime implied a
compositor capability change, which the protocol does not promise.

Regression 187 also passes all seven Surface scenarios. Logs:
`/tmp/wayland-seat-capabilities-187.log`, `/tmp/wayland-global-seat-188.log`, and
`/tmp/wayland-windowless-seat-189.log`. Per-compositor `result.json` files retain
the reported masks under `seat_capabilities`. Full keyboard capability/key-state
queries, focus acknowledgement, pre-window layout publication and M9–M11 remain
pending; this checkpoint does not mark M8 complete.
Regression 190 passes the full JVM/native and all 15 APK tests (46 diagnostic
steps, zero runtime issues), including source generation validation, immutable
device snapshots, listener notification and keyboard fallback checks:
`/tmp/wayland-seat-device-regressions-190.log`. Live device artifacts are retained
in `/tmp/wayland-seat-success-189-{cage,labwc}/`.

Regression 191 passes stock desktop and both task compositor gates with
layout-derived `IInputManager.hasKeys` answers. XKB symbols retain non-text
editing/modifier capabilities; remapped positions advertise their Android key
identities. Queries apply pinned InputReader's device/non-class-source filtering,
clear only the requested output prefix, reject short output arrays and return
false for absent keys. The pointer/touch devices have no keyboard mapper. Log:
`/tmp/wayland-key-capabilities-191.log`. Held-key state and complete pre-window
keyboard publication remain pending.
Regression 192 passes the full JVM/native and all 15 APK tests (45 diagnostic
steps, zero runtime issues): `/tmp/wayland-key-capability-regressions-192.log`.

## Pointer capture and accepted resize checkpoint (2026-09-07)

Stock IInputManager now forwards pointer-capture requests to InputDispatcher.
WMS resolves its authorized input token to the enclosing task, including embedded
windows, before the serial shell requests a Wayland lock. Queued acknowledgements
retain Android's sequence and task generation. Stale replies cannot capture a
replacement window; actual unlock destroys a persistent constraint so it cannot
reactivate after Android has lost capture. Relative motion uses unaccelerated
compositor deltas, transformed into Android task units without a synthetic origin.
Mode changes cancel the outgoing pointer source, and buttons/scroll retain the
stock relative-source ordering.

Regression 143 passes native checks, both stock task-window compositor gates and
all seven Surface scenarios. Cage lacks pointer constraints: its new gate verifies
clean rejection without state or object leaks. The labwc gate verifies actual
lock acknowledgement, exact relative deltas and unlock. The gate exposed split
seat ownership between the rendered child and its toplevel; both hit testing and
the constraint now belong to the toplevel, matching compositor keyboard focus.
The labwc screenshot was visually inspected. Retained artifacts:
`/tmp/wayland-capture-143-{cage,labwc}/`; log `/tmp/wayland-capture-143.log`.

Regression 144 passes framework-module, native, task-shell and all 15 APK tests
after that input-surface change (45 diagnostic steps, no runtime issues).
Regression 140 also passes the stock Java input-channel gate, including relative
axes, scaled deltas, button delivery and cancellation. Delayed/stale capture
replies, token validation and unsupported-capability behavior pass module tests.
Queued scroll now retains its event-time position. Failed/rejected resize no
longer replaces the accepted presentation size used for later bounds repair.

Desktop focus withdrawal and focus acknowledgement, task-addressed APK semantic
input, device capabilities, launcher cutover and the remaining M9–M11 work are
still in progress. These capture gates do not complete M8.

Regressions 145–146 also verify desktop keyboard withdrawal: the native adapter
retains SF's preferred window while clearing effective input focus on Wayland
leave. SF updates cannot reclaim focus while the compositor seat is elsewhere;
reentry restores the latest request. Stock InputDispatcher owns focus/capture
cancellation. Removed displays discard retained requests, and clearing the
focused application now forwards the null handle. Default module/native/input
and 15 APK tests pass (45 steps, no issues), as do both stock compositor gates.
Logs: `/tmp/wayland-desktop-focus-{145,146}.log`. Focus acknowledgement before
cross-task key delivery remains a separate pending gate.

Regressions 147–148 pass XKB non-character remapping through the layout callback:
Caps-as-Control produces Control key events and does not toggle Caps Lock;
multiple physical Control keys retain the modifier until both are released.
The KCM carries the same scan-code overrides, while character positions retain
their independent Unicode matrices. Native/module and all 15 APK tests pass,
as do the stock input-channel and both compositor gates. Logs:
`/tmp/wayland-remapped-keys-{147,148}.log`.

Regressions 149–151 verify keys held before compositor entry and XKB persistent
modifiers. Physical key state survives cancellation of a delivered stream;
inherited releases update modifiers without emitting unmatched Android key-ups.
Left/right modifier combinations and latched/locked Shift retain their meaning.
Shift Lock and F13–F24 also carry their Android identities. Native checks, the
corrected module smoke, all 15 APK tests, stock input channels and both compositor
task gates pass. Logs: `/tmp/wayland-held-keys-{149,150,151}.log`; 149's initial
module failure was a test attempting a duplicate physical press after cancellation,
corrected to release the key and require no unmatched event in 150.

Stock accessibility now discovers actual WindowManagerGlobal roots, retains
Android screen bounds for dialogs, scopes resource-ID fetch flags to each query,
and rejects stale revisions and removed window IDs. Registration is observed
through the decor's parent before its first traversal: View.getViewRootImpl is
still null then, and using it incorrectly reported the new dialog as idle.
Regression 158 passes the stock activity/dialog action and bounds gate, native
checks and all seven Surface scenarios. Regression 159 passes the default module,
native and complete 15-APK suite. Logs: `/tmp/wayland-desktop-launcher-158.log`
and `/tmp/wayland-stock-accessibility-159.log`. This gate covers process-local inspection; subsequent checkpoints below add
session aggregation and task selectors.

The development APK command now selects DesktopLauncher when AZK_TASK_WINDOWS=1
and stock clients/services are enabled. The session owner installs and starts
the APK through ATMS without attaching as that APK or creating a legacy render
window; its lifetime follows the session's application tasks. Regression 158's
stock-desktop-launcher-test verifies a separate app PID, actual labwc pixels,
compositor close, pause/stop/destroy and zero retained presenter buffers.
Screenshot inspected; artifacts: `/tmp/wayland-desktop-launcher-158-artifacts/`.
Default launcher selection remains legacy. Initial fullscreen/frame-limit options reject explicitly until their adapters are
wired; task-window test-control is available to the session owner as described below;
ordinary joined-launch authorization still needs its regression gate.

Regressions 160–164 add system-authorized process inspection. Applications
register their real IApplicationThread before bindApplication callbacks; AMS and
Binder validate UID/PID, and death removes the exact registration generation.
Queries/actions run on the app main thread with a bounded wait and cancel queued
work on timeout. WMS validates window-token ownership and supplies task IDs,
including embedded targets, before tokens are removed from public observations.
The session journal merges app/system cursors, retains source identity after
death, bounds retained records and reports lost source history explicitly.

Stock gates verify remote actions, stale revisions/generations, app-denied
inspection, task mapping and removal. The initial Cage test crossed an initial
resize and correctly rejected its stale action; regression 161 moves the action
past initial presentation and refreshes explicit rejections. Regression 163
passes the full JVM and 15-APK suites (45 steps, no issues), including journal
merge/overflow/atomic-page checks. Regression 164 passes stock boot, cold launch,
both task compositor gates and desktop launcher. Logs:
`/tmp/wayland-process-inspection-{160,161,162,163,164}.log`.
These gates cover cross-process observation over Binder. Subsequent checkpoints
below add session window IDs and Varlink aggregation; compositor input selection
and native-process journal coverage remain in progress.

Session observations now assign stable global window IDs, rewrite accessibility
node relationships and retain task/process generation identity. Repeated local
IDs from separate apps cannot collide; stale actions, process replacement and
removed windows reject. Temporary observation failure preserves a live window's
ID. Regressions 165–166 pass the model and real cold/compositor registry gates.

Inspection also follows AMS.finishAttachApplication: while Application startup
is pending, the registry reads the thread-safe runtime journal and reports an
explicit starting state without queueing a request behind the app main thread.
Regression 167 holds provider startup and verifies responsive observations,
rejected actions, no false idle/failure, then normal attachment in cold launch
and both compositor gates. The Varlink/Kotlin selectors now accept task_id,
window_id, pid and process_generation; explicit identities select independently
of default desktop focus and conflicting identities never broaden a match.
Regression 168 passes full JVM/native/testkit and all 15 APK tests (46 diagnostic
steps, no runtime issues). Logs: `/tmp/wayland-session-observation-{165,166}.log`,
`/tmp/wayland-inspection-startup-167.log`, `/tmp/wayland-session-selectors-168.log`.

Regression 169 connects the owner DesktopLauncher to the existing native Varlink
server through a main-thread JNI poll. Accessibility and diagnostics come from
SessionObservation; NMS supplies all session notifications, including those
retained after their posting process exits. Private system-authorized presenter
queries pace test rounds with real Wayland frame callbacks. These are callback
acknowledgements, not claims of scanout/presentation timestamps. Task membership
changes invalidate a pending round rather than acknowledging a removed task.

The existing labwc harness runs three `azk-test step` actions against task/window
selectors: increment, show an attached dialog, and close that dialog. All steps
have empty runtime issue lists; the resulting counter screenshot was inspected.
Artifacts: `zig-out/test-results/stock-desktop-launcher/`; log:
`/tmp/wayland-controller-varlink-169.log`. Physical compositor action selection,
joined controller access, native-process diagnostics and the default cutover
remain pending. The current adapter explicitly reports input unavailable for
physical actions, while stock semantic actions are routed to the selected app.

Regression 170 passes full JVM/native and 15 APK tests (47 steps, no issues).
Regression 171 passes stock boot/cold, both task compositors and all seven
Surface scenarios, but catches a stale dialog action in the new launcher gate.
Paint-only redraws advanced the observation cursor between the controller's
preflight query and the app's action. App-main validation now distinguishes
paint activity from action-relevant accessibility/tree mutations; paint still
resets visual-idle waits. The session resolves its published window identity
and lets the app validate atomically, without an extra preflight query.

Regression 172 passes the same launcher actions and stock boot/cold gates.
Regression 173 passes full JVM/native and 15 APK tests (47 steps, no issues),
including a draw-only action test and rejection after an actual mutation/action.
The failed 171 artifacts remain at `/tmp/wayland-controller-failure-171/`.
Logs: `/tmp/wayland-controller-regressions-170.log`,
`/tmp/wayland-controller-stock-171.log`, `/tmp/wayland-controller-repaint-{172,173}.log`.

Run stock Android window clients, WMS and ATMS on one hosted Android display.
Present each application task as a separate Wayland `xdg_toplevel`. Multiple
activities in one task share that toplevel; attached dialogs and application
popups participate in that task's composition. Preserve Android task, window,
configuration, input and activity lifecycle contracts.

Keep Zig Binder objects, Parcel storage and AZKB transport. Source-built AOSP
C++ consumers use the existing libbinder facade. Pure Zig clients must continue
to work without adopting C++ objects. Reuse pinned Android 16 r1 libgui, libui,
SurfaceFlinger composition and framework sources wherever their dependencies
are available. Linux graphics and Wayland behavior belong in host adapters.

“Complete” includes buffer allocation/import, GPU rendering, per-task
composition, synchronization, stock window clients and services, task geometry,
input/focus, resize, scale/output changes, IME, clipboard/drag-and-drop,
system-window policy, accessibility/test routing and process teardown. The
milestones below are intermediate deliverables, not substitutes for that scope.

Completion does not mean implementing every optional Wayland protocol or
pretending that core Wayland exposes desktop positions, stacking, minimization
or occlusion. Such capabilities need an explicit supported policy and tests.
Protected playback is unavailable until the complete allocation, rendering and
presentation chain can enforce protection; it must fail closed in the meantime.
No arbitrary prebuilt Android C++ ABI compatibility is promised.

This work deliberately replaces the window/graphics portions of the fence in
`stock-client-migration.md` as their replacement milestones pass. The fence
remains useful for unrelated migration work. Audio/PipeWire and codec behavior
stay in their existing host modules; their window/buffer integration is in scope.

## Multiple task desktop checkpoint (2026-09-07)

Regression 118 passes both `-Dstock-window-clients=true stock-task-wayland-test`
and `stock-task-desktop-test`. Two stock APK tasks share display 0 and one
Wayland connection, with independent SF outputs, disjoint synthetic bounds and
distinct verified pixels. A second activity launched into the selected task
changes that toplevel's content without creating another task. Removing the
sibling preserves the first task and process; removing the final task records
Android's remove-task exit reason and releases all presenter buffers.

The logical display publishes a 16,384-square coordinate plane before WMS boots.
Freeform launch policy bounds initial app sizes; the gate verifies the primary
SF mode remains 1280x720. Density and timing remain stable. The shell reconciles
external bounds changes through WCT and retains the compositor's current output
size. Capacity admission now precedes native output allocation; unpresentable
new tasks request Android removal, and already created outputs wait for vanish.

`tools/shot.sh` supports `SHOT_COMPOSITOR=labwc` using the same session and
artifact conventions as Cage. It isolates compositor configuration and preserves
client failure status. The desktop fixture controls placement only in the
compositor; Android origins remain independent. The test exposed an app-ID
ordering bug: identity must be sent before first map for compositor rules to
work. Both task screenshots and the grouped-activity desktop image were visually
inspected. Artifacts: `zig-out/test-results/stock-wayland-task-{cage,labwc}/`,
with retained copies at `/tmp/wayland-stock-task-grouping-118-{cage,labwc}/`.

Regression 119 passes task-shell policy, default framework module/native/graph
checks and all 15 APK integration tests (46 steps, no runtime issues). The input
adapter also passes InputEvent-boundary checks for transforms, focus ownership,
button ordering, key cancellation, multitouch IDs and stale task generations;
it is not yet connected to the production presenter. Log:
`/tmp/wayland-task-boundaries-119.log`.

The grouping test initially compared against provisional launch bounds, before
the second task rendered. Regression 117's trace showed the shell had already
placed it correctly; the gate now snapshots its rendered bounds before testing
activity grouping. This checkpoint does not complete task restoration,
decoration fallback, input, output/configuration policy, desktop bridges or the
default launcher migration.

## Keyboard layout and seat chronology checkpoint (2026-09-07)

Regression 129 passes native tests, the Java InputDispatcher channel gate, and
both stock APK compositor gates. A separate process reads the compositor's
published keyboard map through stock `IInputManager`/`InputDevice`, including
base and shifted characters. The headless harness registers a persistent virtual
keyboard without sending UI actions; without that device, Cage and labwc have
no keyboard capability and supply no keymap (the stronger gate caught this in
127). This uses the existing surface test executable and `tools/shot.sh`.

XKB layout snapshots retain their exact keymap and group until queued events
have been consumed. The host compiles a bounded character matrix into the pinned
AOSP KeyCharacterMap parser, including modifier combinations and combining dead
accents. Input-device publication preserves immutable old snapshots and notifies
id/generation changes outside the device lock, following InputManagerService.
Tests cover US/German groups, AltGr, Caps+Shift, ISO's extra character key,
parceling and invalid character matrices. Supplementary characters require the
IME bridge; they are rejected at this KCM boundary instead of being truncated.

A bounded session queue preserves event chronology across windows. One Binder
callback object carries task IDs and generations, preserving that chronology
through Binder's per-target oneway queues. Closed/stale task generations are
ignored. Compositor modifier snapshots include keys held before keyboard enter.
Stock InputDispatcher owns repeat timing and count; Wayland supplies rate/delay
and per-key repeat eligibility. The channel gate verifies both zero-rate and
per-key exclusions, then verifies the enabled repeat stream and focus cleanup.
Log: `/tmp/wayland-keyboard-seat-129.log`.

Regression 130 passes default framework/module/native checks, but the APK suite
caught a screenshot assertion that included independently fading feed thumbnails
behind NewPipe's desktop drawer. The observed drawer was stationary and both
steps had empty issue lists. The test now checks unchanged accessibility panel
bounds and the same pixel thresholds within that panel. The focused regression
131 passes; regression 132 passes all 15 APK tests (48 steps, 0 runtime
issues). The failed artifacts remain at
`/tmp/wayland-keyboard-regression-130-drawer-failure/`; compositor checkpoint
artifacts remain at `/tmp/wayland-keyboard-seat-129-{cage,labwc}/`. The grouped
labwc screenshot was visually inspected.

This checkpoint does not complete M8. Physical Wayland input through APK semantic
steps, remapped modifier-key semantics, pointer capture/relative input, device
capabilities and the remaining desktop/lifecycle work still need implementation
and verification. Default launcher cutover and M9–M11 remain in progress.

## Stock task seat checkpoint (2026-09-07)

Regression 123 passes the native Java input-channel gate and both stock APK
Cage/labwc presentation gates with presenter input callbacks connected. The
native gate verifies a task at synthetic x=12000 receives local x=8 and raw
x=12008, a scaled task receives the inverse-projected coordinates, and hover
preserves keyboard focus. Focus transfer cancels held keys before delivery to
the new task. Wayland rate/delay configure stock InputDispatcher's repeat timer;
a zero rate sets its per-event disable-repeat policy even with the pinned
aconfig default. Client-side repeat generation is disabled for the presenter.
Mouse events carry the seat modifier state. Log:
`/tmp/wayland-task-input-dispatch-123.log`.

The first build attempt (122) stopped on Kotlin nested-class visibility; the
corrected build and runtime gates pass. These checks do not yet prove physical
Wayland input through APK semantic actions. Layout publication, modifier
snapshots, pointer capture and the remaining M8–M11 work are still in progress.

## Session task presenter checkpoint (2026-09-07)

The system process now owns a TaskOrganizer independently of APK launchers.
`WaylandTaskShell` serializes appearance, metadata, synchronized bounds changes
and removal, reserves old bounds until commit, coalesces resize requests and
rejects stale task generations. Its stock adapter positions organized task
leashes explicitly: WMS deliberately leaves their positioning to the organizer.
Private SF outputs retain the stock window/buffer-child hierarchy.

`azk-task-presenter` owns one Wayland connection and up to 64 task windows.
System-UID-only AIDL calls transport stock `android.view.Surface` objects over
AZKB. A bounded request queue moves work from Binder workers to the protocol
thread; each output uses the existing stock BufferItemConsumer/DMA-BUF path.
Session supervision starts the presenter before system_server, handles readiness
failure and shuts it down before SF. Organizer/presenter death ends the affected
session generation. Development selection is `AZK_TASK_WINDOWS=1` together with
stock window clients and `AZK_WINDOW_BACKEND=stock`; default cutover is pending.

Task removal now implements the hosted equivalents of ActiveServices cleanup
and AMS's process-removal callback. Started services receive `onTaskRemoved`,
`stopWithTask` clears started ownership, and bound-only services remain bound.
Process exit waits for background eligibility with no started service or active
ordered receiver, and records Android's user-requested/remove-task exit reason.

Regression 97 passes stock cold/warm launch, private task pixels and removal,
and Surface/JNI checks. Foreign-thread Binder callbacks detach only attachments
they own. Patch 0060 adds the corresponding lifetime to host HWUI workers;
HWUI JNI now receives the host graphics build flag. Package 70 contains 55,363
sources and 77 patches: `N-V-__8AAJPr6U31AAxA_2gERyr0S9Zlq4vJMy1iF3NNrxPD`,
archive SHA-256 `7e4ce6421e9550b65747f443ac4ee179ebbe35918049a77b7d49c426b6f79767`.
The NDK also preserves pinned `AParcel_delete(nullptr)` behavior, covered by
regression 104. System WTF reporting defers process lookup along with the report,
as AOSP requires to avoid taking AMS's lock while WMS holds its own lock. The
empty-desktop policy stops retaining TOP importance after the last activity goes.
Regression 99 passes Binder facade, policy and existing supervision checks;
regression 102 adds presenter startup/shutdown failure and descriptor checks.

Regression 108 passes `-Dstock-window-clients=true stock-task-wayland-test`:
a cold stock APK produces the expected center and four corner colors in Cage
at 1280x720, then Android task removal, the user-requested process exit record and zero live
presenter graphics buffers all pass without fatal exceptions.
The screenshot was visually inspected. Artifacts are retained under
`zig-out/test-results/stock-wayland-task/`; the build log is
`/tmp/wayland-stock-task-wayland-108.log`. This is one task, not the complete M7
acceptance gate: synthetic display sizing, simultaneous tasks, stock input,
desktop bridges and M11/default migration remain unfinished.

Regression 109 passes the framework module contract checks and all five Cage
Surface scenarios. Regression 110 passes the complete default JVM, system,
Binder, native, supervision and graph checks plus 15 APK integration tests:
50 diagnostic steps, all with empty runtime issue lists. Logs are
`/tmp/wayland-task-regression-109.log` and
`/tmp/wayland-full-task-regression-110.log`.

## Main rebase and shared dispatcher verification (2026-09-07)

Rebased onto main `7e2ad4c`, incorporating stock MediaStore/HTTP clients,
virtual-device local contracts, service registration and Parcel blob/descriptor
ownership. The combined package 69 contains 55,363 sources and 76 patches:
`N-V-__8AALvo6U2g5DBWmiWCv2ON7jDp7frrFYZFBqdSstO4`, archive SHA-256
`2e1a402befdc38d040891a02f512485c405c5043b7090e4420ab5381b8426ec2`.
The audit has 21 stubs: 13 pending and 8 survivors; the pending budget is 13.

Regression 86 passes the stock service launch gates, JVM/system, broker/session,
graph and 15 APK tests (48 diagnostic steps, no runtime issues), but exposes an
intermittent std.Io batched-receive cancellation panic in the new dispatcher.
The pinned Threaded backend leaves immediately completed socket receives linked
in its submitted list. The dispatcher now uses its readiness-batched zero-byte
file read, preserving all libwayland bytes/descriptors. It requires an owner
notification file so both operations participate in the readiness batch.
Regression 87 passes all five Cage surface scenarios and native checks. Main's
new blob test reused the transaction code of our ParcelFileDescriptor test;
assigning a distinct test code preserves both. Regression 88 passes the complete
C++ Binder facade gate, including descriptor/blob transport and pool monitoring.
The closure-tool unit suite passes 13 tests. The task presenter is still being
connected; none of these checks completes the remaining M7–M11 gates.

## Shared Wayland session checkpoint (2026-09-07)

`wayland.Session` owns one connection, registry, seat, keyboard map, compositor
factories and DMA-BUF formats. Each `Window` owns its surfaces, configure state,
input gesture/repeat state and imported buffer leases. Surface identity routes
pointer/keyboard focus and touch contacts; destroying a shared window leaves
its peers and connection alive. Partial initialization releases acquired globals
and window resources. Keymaps and DMA-BUF tables use bounded std.Io reads.

The session dispatcher uses std.Io batched socket readiness and eventfd reads.
libwayland retains ownership of wire bytes and descriptor transfer. Cancellation
collects completed notifications, and window events remain queued until consumed.
Only congested flushes use a bounded retry timer. The legacy single-window API
remains available while the task presenter is connected.

Regression 85 passes native checks and all five existing Cage surface-queue
scenarios (normal, producer death, consumer death, broker loss and backpressure).
Each creates two windows on one session, destroys the sibling, and exercises the
surviving window through the shared dispatcher. Normal/backpressure runs verify
12 producer frames and zero live graphics buffers after graceful teardown.
This proves shared connection lifetime and queue delivery, not yet concurrent
presentation of two Android tasks; that M7 integration gate remains outstanding.
Log: `/tmp/wayland-session-pump-85.log`.

## Stock window-client rendering checkpoint (2026-09-07)

The `-Dstock-window-clients=true` source selection compiles pinned ViewRootImpl,
SurfaceView, AccessibilityInteractionController and PhoneFallbackEventHandler.
Host root frame/input adapters preserve the legacy selection during migration.
Accessibility connection registration now owns unique IDs, death cleanup and
WMS metadata; IME client registration and focus checks follow the pinned
no-selected-IME path. Overlay UI updates feed process importance. Contract
checks exercise registration, stale removal, duplicate IME clients and invalid
window/display outcomes.

Stock regression 80 launches the real fixture APK through stock ViewRootImpl,
HWUI and BLAST, then captures its SF task leash. The center and four distinct
corner colors pass, with the saved portrait image visually checked. This exposed
an uninitialized `Layer.mWindowTransform` and crop in pinned HWUI's temporary
readback layer: GPU bitmap copies rotated and stretched otherwise correct task
pixels. Patch 0059 initializes the optional geometry; landscape and portrait
hardware-bitmap roundtrips now pass. Package 68 is pinned as
`N-V-__8AAIrlxk303Bz2Alp6lB1chU3Mx8ikwqhM3b2ye_Pe`, archive SHA-256
`05e9227a4f5d8d04ded26bdb21fc558bde0c9b7e8eab5ebcb217cb272898af57`.
Artifacts: `/tmp/wayland-stock-task-capture-80.log`,
`/tmp/wayland-stock-task-80.png` and its `.sf.txt` dump.

Full default-selection regression 84 passes both stock service launch gates,
JVM, system, Binder broker, session and graph checks, plus all 15 APK integration
tests (51 diagnostic steps, zero runtime issues or failure artifacts).
The stock source selection is still developmental: three legacy root-private
probe classes need migration, the production task presenter is not connected,
and the legacy renderer remains the default. This checkpoint does not complete
M6–M11 or claim a full APK-suite pass with stock window-client sources.

## Cold process and camera-client rebase work (2026-09-07)

Rebased onto main ed49081, retaining its stock virtual-device and camera client
closure and hosted virtual-device service. Package 67 combines those imports
with the graphics/WM closure: `N-V-__8AAHDlxk20lzaYWXDpnS9Mk8vY-sv4zbevEpP3CCc_`,
archive SHA-256 `2c58a4b7c838651930f217976f717d0ada5993ac51aea704556af01faa94d8ad`.
The merged generator lists retain both branches and drop duplicate device-state
entries. The stub audit now has 23 stubs, 14 pending and 9 survivors.

Regression 53 passes both stock launch gates, module, session and graph checks. Regression 48 exposed
anonymous test-driver identities; the drivers now connect using their fixture
package. Regression 49 passes module, broker and graph checks, but switching
activities reaches the previously unsupported AM updateCpuStats callback. A system-only broker
request reserves a package UID from the same table as process hello. Package
installation publishes that identity before ATMS resolves its first activity.
AM's startProcess boundary invokes the host launcher with the requested process
name; attachment announcements are keyed by UID and PID. Broker tests cover
reservation/hello identity, reconnects, denied allocation, malformed requests
and exhaustion. The stock-window gate now launches an absent secondary process
and observes provider/create/start/resume callbacks through a public-API APK
provider. A second gate installs the package in a fresh session before any app
peer exists, then requests its first launch from a trusted system test client.
Service and provider resolution now selects the manifest process instead of
whichever sibling attached first. CPU sampling uses stock ProcessCpuTracker
with asynchronous five-second throttling and session UID battery accounting.
Regression 50 reaches process creation and exposes a launcher join bug: parsed
JSON strings borrowed a temporary stack buffer, corrupting the child property
path. SessionInfo now reads complete metadata into caller-owned arena storage
and copies parsed strings. The fresh-session test driver also implements the
production supervisor exec trampoline. Regression 51 gets the child through native startup and reaches APK installation;
a relative/absolute path mismatch rejects the same APK. Install paths now use
canonical file identity, covered by a reinstall/observer regression. Session
supervision regression 52 passes all startup, deadline and descriptor gates.
Regression 53 verifies an install-time UID before the first app peer exists,
ATMS-driven creation and provider/create/start/resume callbacks. It also
verifies a secondary activity process, service reuse of that process and a
separate provider-only process. Full regression 54 passes the stock, JVM,
system, broker, session and graph gates, but 13 APK tests fail during launch:
the GUI resource bootstrap connects anonymously before setting package identity.
Regression 56 confirms the early assignedUid call and verifies that changing an
already connected identity is now rejected. The bootstrap now parses ApkLite and
sets identity before initializing system assets. Targeted launch regression 57 and
full regression 58 pass: 15 APK tests, 45 steps, no structured runtime issues,
plus both stock launch gates, JVM, system, broker, session and graph checks.
Startup failure recovery and complete process supervision remain outstanding.

## Process startup recovery checkpoint (2026-09-07)

HostedProcessStarts now owns start sequences, duplicate-start suppression,
attachment validation, readiness deadlines and matching-generation exit cleanup.
The launcher passes each sequence through ActivityThread.attach; an old child,
late timeout or delayed exit cannot claim or cancel a replacement. A watcher
reaps each child and removes it from the session's child set. Startup death and
timeout release pending service bindings with onBindingDied and wake only the
providers assigned to that manifest process. Deadline termination uses the
host Java child handle; forced termination and pre-attach ATMS activity recovery
still need completion.

Regression 59 passes deterministic generation, timeout, early-exit, retry and
sibling tests. Regression 60 passes both production cold-launch gates; its module
mock used an arbitrary nonzero start sequence without a supervised launch, and
now uses the external-launch sequence zero while retaining its mismatched-ack
check. Regression 61 passes the targeted gates and a real provider-startup crash:
the pending service receives onBindingDied, retry starts a fresh process, and
both existing sibling PIDs survive. Full regression 62 passes all stock, JVM,
system, broker, session, graph and 15 APK tests with no structured runtime issues.
The intentional crash exists only in the stock recovery fixture action.

Closure 63 against the live framework jar reports four stock client sources
(ViewRootImpl, SurfaceView, AccessibilityInteractionController and
PhoneFallbackEventHandler), no additional AIDL and no source blockers. Host
caller adaptation and presentation remain the M6 cutover work.

## Real WMS client and TextureView checkpoint (2026-09-07)

Regression 41 passes the real WMS session gate: the fixture resumes in task 2,
adds a 320x240 application window with its activity token, obtains an input
channel and surface, and commits a HWUI frame through BLAST and WMS
finishDrawing. Module, buffer/JNI and the targeted TextureView APK checks pass.
The gate remains a direct session client, not a stock ViewRootImpl APK window.
Cold process launch is still missing ActivityManagerInternal.startProcess and
pre-launch package UID assignment; do not mark the full M5 process scope done.

Stock TextureView now replaces the stub and uses stock TextureView/TextureLayer
JNI. The host Ganesh pipeline creates a stock DeferredLayerUpdater. The fixture
verifies CPU-produced pixels through HWUI readback, resize callback ordering,
and surface release after detach. The audit has 27 stubs, 17 pending and 10
survivors (TextureView was a survivor, so the pending budget stays at 17).

Regression 42 passes JVM, system, stock-window and graph checks but fails two
of 15 APK tests. The new compositor screenshot check exposes Layoutlib's
non-Android drawLayer guard: readback succeeds while recording omits the texture
layer. Patch 0058 enables stock layer recording for ARTZIG_HOST_GRAPHICS.
Regression 44 passes the stock-window, module, buffer/JNI, graph and targeted
TextureView APK gates. Both cyan and resized magenta pixels appear in compositor
screenshots; all three diagnostic steps are clean. Package 66 is pinned as
`N-V-__8AADZTuU1bOnEYBc5YCngEzOlMlcN8uM01bZdn18Zf`, archive SHA-256
`9cff110e2584452eaa36d3c085687afb7483c980eb7e63042cc22a54501ff8e2`. The live NewPipe fullscreen test captured
a mostly black stream frame; isolated regression 43 passes unchanged with seven
clean steps and a visible full-width video frame. Both regression-42 failures
are retained under `/tmp/wayland-regression42-artifacts/`. No structured runtime
issues were recorded in those failed captures. Full regression 47 now passes
stock-window, JVM, system, graph and all 15 APK tests (47 diagnostic steps,
zero runtime issues and no failure artifacts), including both visual checks.

## Media rebase and window-client source checkpoint (2026-09-07)

Rebased onto main 931c8c9, retaining its stock spatializer, loudness and media
routing clients and hosted services. Package 65 combines those imports with the
existing graphics closure and the four previously unvendored window-client
sources. It is pinned as `N-V-__8AAABTuU061pcDkSxp2uwVtikveeGZ7CF-WO6BJUNb`,
archive SHA-256 `a7fa2a3f652097756bb90a18ff0dd07ac80451e5a2936b1ad789e9313143eb7d`.
The regenerated stub audit has 28 stubs: 17 pending and 11 survivors.

An isolated javac check compiles stock ViewRootImpl, SurfaceView, TextureView,
AccessibilityInteractionController and PhoneFallbackEventHandler together
against a live framework build (`/tmp/wayland-client-compile-main9.log`).
TextureView also compiles alone against the current root
(`/tmp/wayland-texture-compile-main9.log`), allowing its independent replacement.
These are source checks, not proof of runtime client/service compatibility.
Regression 39 passes module, system, full APK and graph checks (14 APK tests,
44 steps, zero runtime issues). Its stronger stock-window gate reaches real
window registration and fails at PackageManagerInternal.isPackageSuspended.
The local query now reads stock package user state; regressions 41 and 44
validate it through real WMS window registration. The TextureView replacement
and its public-API APK fixture are validated in the newer checkpoint above.

## Stock task launch checkpoint (2026-09-07)

Regression 38 passes stock-window boot, framework module, system, full APK and
build graph checks (`/tmp/wayland-stock-window-boot-38.log`). Stock ATMS launches
and resumes the fixture in task 2; the registered process transitions from
CACHED_EMPTY to TOP. The APK suite passes 14 tests and 44 diagnostic steps with
no runtime issues or failure artifacts. This verifies activity launch, not yet
acceptance or presentation of a real WMS client window.

Package component use now follows stopped-state clearing and notifications.
Stock usage records own overlapping activity accounting. WPC callbacks publish
process state, pending TOP state, service foreground types and UI-hidden trim
notifications. Battery callbacks account nested foreground durations and CPU
time while on battery through a host adapter, exposed in the battery Binder
dump. Stock BatteryStatsImpl's closure requires power HAL/BPF infrastructure.
Binding/provider priority inheritance, service execution accounting, Android
memory reclamation policy and durable usage history are not implemented by
these adapters. Linux continues to own host memory reclamation.

## Stock ActivityThread rebase checkpoint (2026-09-07)

Rebased onto main's 3c58b02 stock ActivityThread commit. The obsolete hosted
system-UI context cache is removed; stock ActivityThread owns that cache and
client lifecycle dispatch. The URI-permission host adapter retains main's new
provider reference counting, including release on exceptions and early returns.
The first combined build (`/tmp/wayland-stock-window-boot-30.log`) found six
fixture compilation errors from retired ActivityThread/Choreographer helpers.
The adapted fixtures and package-property index pass module validation. Regression
31 passes the system/full APK/graph checks: 14 APK tests, 44 steps, zero runtime
issues. Stock display construction now supplies its PermissionEnforcer with the
deferred bootstrap Context, allowing startup before ActivityThread exists.
Regression 32 boots stock WMS/ATMS and creates a real task/activity record; launch
still fails at PackageManagerInternal.notifyComponentUsed and the asynchronous
ActivityManagerInternal.updateActivityUsageStats callback. A resumed activity and
real client window remain unverified.

The live closure scan for ViewRootImpl reports three selected sources and no
source-closure blockers: ViewRootImpl, AccessibilityInteractionController and
PhoneFallbackEventHandler (`/tmp/wayland-view-root-closure-main8.log`). Adding SurfaceView and TextureView makes five sources and adds the TextureView
JNI blocker (`/tmp/wayland-window-client-closure-main8.log`). Runtime and
host-caller adaptation remain required before the M6 cutover.

## Registered application process checkpoint (2026-09-07)

Ordinary process attachment now creates a real WindowProcessController in the
existing AMS registry, maps it by name/PID in ATMS, and uses stock preBindApplication
configuration before binding. ATMS attachment waits for Application.onCreate's
acknowledgement. The shared lock covers binding and publishing the live thread;
removed-state reads do not invert the AMS/WM lock order. Cleanup removes stock
process indexes and configuration listeners. UID state and package membership
are published to ATMS. Activity/service state callbacks remain unfinished.

The registered-app window-session gate passed in
`/tmp/wayland-stock-window-boot-19.log`. The stronger gate now installs the window
fixture and attempts actual stock task/activity creation. It remains failing:
`/tmp/wayland-stock-window-boot-29.log` reaches ActivityRecord configuration, where
package-property lookup is missing. Stock ColorDisplayService now starts and
package aspect-ratio state passes its module checks. Package-property support
imports the stock index and is awaiting validation. A real client window and
task lifecycle are not yet verified. Source package 64 adds PackageProperty,
pinned as `N-V-__8AAO5TpE1o7hfJYVcGUFm_im_jkV9mjoPC_JlQU1MN`, archive SHA-256
`4107f84d2580a020b29fb9e3a22bcc9a290eaedc4c4416fcda8ae849256f3cf5`.

Direct package installs now receive a native-library search path even without
extracted libraries, as required by stock LoadedApk. The launch resolver uses
host package policy for instant-app/permission-review checks and home fallback;
package-use timestamps use stock transient state. PermissionPolicyInternal now
has a host adapter for launch checks, initialization and task eligibility.
Legacy notification prompts still diagnose the missing permission flags/overlay
activity when eligible; automatic legacy prompt integration remains outstanding.

AppOps now uses pinned per-operation defaults instead of allowing every unset
operation. The platform-signed server fixture explicitly requests background
activity-start permission and verifies overlay permission remains denied; its
launch now follows BAL_ALLOW_PERMISSION. This is a server test driver. Ordinary
APK initial launches still need the desktop task shell's user-launch path.
The module/system/full APK/graph checks in regression 27 pass: 14 APK tests,
44 steps, zero runtime issues. Only the expanded stock task-launch gate fails.

Main committed the stock ActivityThread cutover as 3c58b02 and this branch is
now rebased onto it. Main's newer uncommitted audio changes remain in its worktree.

## Stock service boot checkpoint (2026-09-07)

The opt-in stock-window boot gate passes (`/tmp/wayland-stock-window-boot-16.log`),
along with module, system and graph checks. WMS enables the primary 1280x720
display and invokes SurfaceFlinger bootFinished; the client queries the actual
window/display services after the final boot phase. Session readiness is now
published only after the WMS boot-animation-complete callback releases the
pending boot work. The callback and final phase are consumed once.

This checkpoint includes main `411405b`'s application-binding completion
handshake. Process-start notifications remain at attachment; queued component
work waits for Application.onCreate completion. The grammatical-inflection
Binder service now supplies its stock local interface, including developer
override fallback. ATMS configuration broadcasts use the existing queue with
configuration/locale ordering and split-notification permission filtering.
PackageManager's local activity lookup now uses stock start-time exported
filtering and private resolution flags; ambiguous resolver-UI requests remain
explicitly unsupported. No Android home package is installed in this session.

The desktop keeps Linux memory policy, without Android LMKD target or global
watermark tuning. Density cleanup selects session process records by target SDK
and reported state and requests exit through their Binder connection. Application
state still uses the legacy TOP default until stock WPC activity/service callbacks
are connected; unresponsive-process forced termination remains part of recovery.
These are remaining process-integration tasks, not completed M5/M11 behavior.

The full JVM/system/APK regression after the application-binding rebase and boot
callback changes passed except for the then-pending stock startup gate
(`/tmp/wayland-stock-window-boot-12.log`): 14 APK tests, 44 steps, no structured
runtime issues. Startup still reported lost early System UI context callbacks;
the pinned fixed-read-only `track_system_ui_context_before_wms` bugfix is now
enabled through the generator. The complete stock boot/JVM/system/APK/graph and
build-tools regression passes (`/tmp/wayland-stock-window-boot-17.log`): 14 APK
tests, 44 steps and no structured runtime issues. The stronger stock startup
gate, module, system and graph checks also pass in
`/tmp/wayland-stock-window-boot-18.log`. Startup now rejects any structured
issues recorded by the server before readiness. The stock boot process no longer
loses its early UI context token, and WMS rejects an unregistered client PID
without killing the service. The legacy module's intentional unattached-token
fixture still produces its existing warning.

A live-build source inventory for stock ActivityThread and ViewRootImpl reports
four source classes, the ART flags generator and ActivityThread JNI. Existing
host replacements count as present in that inventory, so it does not establish
client cutover readiness (`/tmp/wayland-main7-window-clients-closure.log`). M5
still needs real application windows/task lifecycle tests; M6–M11 are unfinished.

## Main framework integration and stock boot checkpoint (2026-09-07)

Rebased onto main `1365002`, including stock ThreadedRenderer,
SurfaceControlViewHost, WindowlessWindowManager, SplashScreenView, connectivity
clients, submitted-frame callbacks and preserved-window fixes. The combined
HWUI pipeline retains main's shared EGL/Ganesh lifetime and this branch's stock
ANativeWindow queues. Java EGL14 and legacy EGL now acquire display ownership
through the same native authority, so terminating a Java EGL lease no longer
invalidates HWUI snapshots. The regression includes repeated initialize,
terminate and an extra terminate between verified hardware snapshots.

Stock WindowInfosListener and InputTransferToken JNI are compiled and registered.
The live InputDispatcher/SF fixture verifies Java input-window metadata and
transforms; the system test round-trips a native InputTransferToken through
another application process. Native tests pass (25/25). The full JVM, system,
buffer, APK, build-tools and source/graph audit run passes except for the separate
opt-in stock-window startup gate (`/tmp/wayland-stock-window-boot-9.log`). APK
artifacts contain 14 tests, 42 steps and no structured runtime issues.
The Cage FrameCommitProbe and HardwareBitmapProbe also pass, including the
post-teardown desktop frame; screenshots were inspected at
`/tmp/wayland-main6-frame-commit-2.png` and
`/tmp/wayland-main6-hardware-bitmap.png`. The first manual invocation used a
stale zig-out runtime and failed to load ART; both passing probes use the
current build's runtime tree explicitly.

The stock startup path constructs WMS and its primary DisplayContent, attaches
the existing system process to ATMS with a real WindowProcessController, and
reaches system-ready initialization. Display policy now handles absent-keyguard
lockscreen flags, fixed-mode display properties and cleared brightness overrides.
The package-list observer dependency in ATMS's LaunchParamsPersister now uses
the stock PackageList and PackageObserverHelper over the hosted package registry.
Module checks verify snapshot stability, installation and enabled-state callbacks,
idempotent installation and repeated close/unregistration. Module, system and
graph checks pass (`/tmp/wayland-stock-window-boot-10.log`); the separate boot
gate next fails because the grammatical-inflection Binder service has no local
interface registered for ATMS's configuration initialization. Source package 63
is pinned as `N-V-__8AAC0hpE3ouNqdBe4H3_XNKPULGWazUTOg-tu0eTbc`, archive SHA-256
`76a9d7ef73b214a69ce7b767017893eeff51c5a8ba262c7796956a564817befd`. Boot is
still opt-in. Application process/task attachment, stock application window
clients, the Wayland task presenter and M6–M11 are not complete. The current stub
audit has 31 stubs: 20 pending and 11 survivors.

## Desktop window policy checkpoint (2026-09-06)

HostedDesktopWindowPolicy now has a generated WindowManagerPolicy interface
adapter that inherits the pinned Java default methods for window/subwindow
layers. The host policy follows PhoneWindowManager's add-window permission
ordering and absent-keyguard callbacks, and uses stock DisplayPolicy's screen
state plus WMS waitForAllWindowsDrawn for the draw barrier. It is not yet
installed in normal startup. Screen lifecycle integration still needs the
stock WMS/ATMS boot gate; the passing module checks do not prove that gate.

Framework-module-test and graph audit pass (`/tmp/wayland-desktop-policy-1.log`).
Checks cover inherited default methods/overload failures, permission output
mutation and ordering, token-validated window categories, keyboard configuration,
and absent-keyguard dismissal failure. The preceding complete power/process
observer regression has 14 integration step files, 42 steps and no runtime issues.

## Power and process observer checkpoint (2026-09-06)

The existing power Binder service now also backs the generated stock
PowerManagerInternal adapter. Low-power queries and observers follow stock
PowerManagerService's battery-saver-unsupported branch. Android launch/display
performance hints use the same existing advisory power implementation. A real
per-window screen-off deadline remains explicitly unsupported by the desktop
compositor; clearing the default override is accepted.

AMS and its generated local adapter share the process-observer registry and
SET_ACTIVITY_WATCHER permission gate. The registry follows ProcessList's
RemoteCallbackList ownership, pending PID coalescing, activities-before-services
ordering and cancellation of pending changes on death. Attach and death feed it;
foreground activity/service updates still require the actual task/service state
connections. The usage-stats attach callback is not used as foreground truth.

Module checks pass (`/tmp/wayland-power-observers-1.log`), including observer
registration/death/failure behavior and denied Binder/local registration. JVM,
system, full APK integration, graph and build-tools regressions pass
(`/tmp/wayland-power-observers-regressions-1.log`). The earlier power-only run
failed before the test because the required Mesa environment was omitted.
Stock WMS/ATMS startup and desktop policy integration remain unfinished.

## Session display checkpoint (2026-09-06)

HostedSessionDisplayService and its generated DisplayManagerInternal adapter
share one HostedLogicalDisplay. Stock DisplayInfoOverrides supplies the exact
WM override fields. Geometry follows LogicalDisplay's aspect-preserving
projection, rotation and offsets, and traversal uses the display-specific
transaction while notifying listeners with the global transaction afterward.
SurfaceFlingerDisplayOutput obtains real primary-display metadata and submits
the projection through stock SurfaceControl. Private virtual task outputs do
not enter Android display discovery. These services are not yet registered in
normal startup; WMS/ATMS boot and the default cutover remain outstanding.

Java and graph audit pass (`/tmp/wayland-session-display-java-2.log`). The system
suite passes in `/tmp/wayland-session-display-runtime-1.log`, including the real
SF backend and private-output discovery check. That combined run's module gate
failed because the test supplied an invalid display mode; after correcting the
fixture, `/tmp/wayland-session-display-runtime-2.log` passes. Module checks cover
identity/mode preservation, copied overrides, projection/offsets, per-display
transaction selection, callback masks/registration ownership, permission checks,
callback death and failed death links. Stock device-state tests still pass.

Rebased onto main `39807f7`, the stock ActivityThread mainline closure
(120 Java sources, 45 AIDL). The combined source package 59 preserves both
branches' selections and removes duplicate source/generator declarations.
Its archive SHA-256 is
`6a474a526ec3617354f4f8a47a138465b086cf939446aa527e8d7272fbcf40a4`.
Main's uncommitted networking migration remains separate.

The complete rebased JVM, system-service, APK integration, C++/graph and build
tools checks pass (`/tmp/wayland-main5-regressions-2.log`). Retained integration
artifacts contain 14 step files, 41 steps, no runtime issues and no failure
artifacts. The first run caught duplicate proto declarations in the build audit;
the second run verifies their removal. The live stub audit records 35 stubs,
20 pending and 15 survivors. Stock WMS/ATMS startup is still unfinished.

## Device-state service checkpoint (2026-09-06)

The normal framework now compiles the stock DeviceStateManagerService,
request controller, notifications and shell command. Its default policy factory
selects HostedDesktopDeviceStatePolicy: the stock default state `0:DEFAULT`,
without foldable hardware properties. The provider preserves supported-states
before current-state delivery, single listener registration, and configuration
completion. WMS/ATMS boot remains unfinished; the service is not yet published
in the legacy startup path.

The device-state HAL types are generated from frozen V1 AIDL in the pinned
`platform/frameworks/hardware/interfaces` project (commit
`1fbcd8ad465856725dd004a282d5be142fe8fccc`). Generator declarations retain its
version and frozen hash; no generated interface is handwritten. Source package
58 is pinned by `build.zig.zon` (archive SHA-256
`63a17f06d3e265058f9c6003b27ed057ec7e4f4bc27c8d02d9a811b4f38f4ac2`).

Validation: Java compilation, C++/graph audits and framework-module-test pass
(`/tmp/wayland-device-state-java-1.log`,
`/tmp/wayland-device-state-runtime-1.log`). Tests call the stock service Binder
API with scoped broadcast and permission adapters, checking initial delivery,
registration ownership, invalid state/token failures, denied controls, base
state requests, callback death and failed death-link cleanup. They do not
replace global service registrations. The previous main-rebase checkpoint's
complete JVM, system and 14-APK regression run remains recorded below.

Next boot dependencies are the session logical display and power LocalService,
stock ATMS startup, and its process/screen observer connections. The full stock
LogicalDisplay closure still pulls 429 Java sources, 49 AIDL and 112 blockers
(`/tmp/wayland-stock-logical-display-closure-1.log`); its hardware/service graph
is not independently reusable. The desktop display adapter must preserve the
stock override-field and traversal contracts while hiding private task outputs.

## Baseline inventory

The table records the pre-implementation baseline. Current progress is recorded
below it; in particular, HardwareBuffer storage has since moved out of
`platform_abi.zig` into the shared graphics module.

| Area | Evidence in the current tree | Work still required |
| --- | --- | --- |
| Binder | `build/binder_cpp.zig`, `src/native/binder`, `src/binder/ndk.zig`; generated IFpsListener and ownership/death regressions | Compile and exercise the actual libgui/SF interface closure; extend supported APIs as needed |
| Buffer transport experiment | `tools/prototypes/binder-facade`; real GraphicBuffer/Fence serialization over AZKB | Production allocation/import and real BufferQueue scheduling; the prototype mapper only clones opaque handles |
| Native source package | libgui headers and libui sources are selected; only the probe's GUI AIDL is explicitly added | Curate libgui implementation, generated interfaces and SF dependency closure; headers being present is not a built library |
| Hardware buffers | GBM-backed AHardwareBuffer allocation/mapping in `src/native/platform_abi.zig`; EGL/Skia import in `src/native/HardwareBufferRender.cpp` | Shared importable buffer ownership for GraphicBuffer and AHardwareBuffer, metadata validation and fence contracts |
| Rendering | `ArtzigGpuPipeline.cpp` and `host_renderer.zig` render HWUI to a host target | Render through a stock Surface/ANativeWindow buffer lifecycle |
| Wayland | `src/platform/wayland.zig` owns one connection/window, input, fractional scale and video DMA-BUF subsurfaces | Session-level connection, many task surfaces, output/seat tracking, task output buffers and explicit synchronization |
| Window clients | `build/java_sources.zig` excludes stock ViewRootImpl, WindowManagerGlobal, SurfaceControl, TaskOrganizer and related classes | Native/JVM closure, JNI integration and removal of the corresponding replacements |
| Services | Hosted ATMS, display and input services in `src/java/dev/artzig/system`; hosted system-server bootstrap | Stock WMS/ATMS and input/display collaborators with real internal service contracts |
| APK execution | `src/java/dev/artzig/ApkHost.kt`, ActivityThread's host adapter, `src/main.zig` | Session process supervision and stock window/input lifecycle; remove single-target globals |
| Tests | `tools/shot.sh`, `tools/azk-test`, Kotlin integration testkit and pinned APKs | Task-addressed tests and graphics fixtures; multiwindow compositor coverage |

The rebase validation of this baseline passed native/Binder/system/JVM tests and
all 11 APK integration tests (38 recorded steps with no runtime issues). The
older baseline JVM failures recorded in `libbinder-facade-implementation.md`
were absent after rebase. Establish fresh results when implementation begins.

### Additional verified service and output work (2026-09-06)

Rebased again through committed main `fa575e3`, preserving the shared graphics,
input and Binder work. This adds the main worktree's stock performance-hint
clients, activity launch/configuration/affinity fixes and permission-usage values.
The union archive also initializes HWUI for hardware bitmaps before the first
window. The merged framework-module, Binder facade, build-tool, C++ and graph
checks pass (`/tmp/wayland-stock-readback-21.log`). The first-use hardware bitmap,
lazy hardware image and queued PixelCopy paths all verify their pixels. Full JVM, all 14 APK tests, RenderEngine, graphics-buffer and real-SF task/input
output regression gates pass (`/tmp/wayland-main-2-regressions-1.log`). The APK
artifacts contain 44 steps, no runtime issues and no failure records.

`Binder.blockUntilThreadAvailable` now waits on AZKB's actual dispatch capacity,
shared with the C++ facade. A real cross-process regression fills all 32 dispatch
workers, observes the monitor remain blocked, then releases one worker and
requires the monitor to resume. This is the stock Watchdog Binder-monitor
contract; Watchdog itself has not yet joined the running service graph.

The stock Watchdog, WatchdogDiagnostics, ResourcePressureUtil, TraceErrorLogger,
DropboxRateLimiter and SurfaceAnimationThread now compile in the live framework.
The host adapter retains stock checker/timeout/controller/debugger decisions,
uses session DropBox and paths, discovers native service owners through the
broker, and captures local process state instead of issuing global kernel reset
commands. Framework-module and build/audit gates pass
(`/tmp/wayland-watchdog-integration-1.log`), including monitor ordering, timeout
stages, nested/timed pauses, report truncation and dropped-count retention.
The full JVM and system-service suites also pass
(`/tmp/wayland-watchdog-regressions-1.log`). Watchdog is not started in service
boot yet. The current WMS/ATMS compile probe
reports 63 errors (`/tmp/wm-compile-probe-16.log`), down from 78.

The next source package adds stock SDK-sandbox clients and their six AIDL
interfaces, stock ColorDisplayService and its saturation controller, and stock
instant-app resolution helpers. SDK activity validation uses a host capability
boundary: ordinary launches do not query an absent sandbox package; explicit
sandbox hosting fails when no local manager exists. The optional verifier's
shrinker annotation retains its target by name. ColorDisplayService queries the
published UserManager client instead of requiring the full Android user service.
Neither new service is started by this checkpoint.

The Java, build-tool, C++ and graph gates pass
(`/tmp/wayland-wm-closures-java-1.log`). The framework-module runtime gate also
passes (`/tmp/wayland-wm-closures-runtime-1.log`), including SDK Parcel/Binder
identity, authority denials, per-app saturation arbitration and weak-controller
lifetime, and instant-app intent sanitization/installer handoff.
Full JVM and system-service regressions pass as well
(`/tmp/wayland-wm-closures-regressions-1.log`). The WMS/ATMS
compile probe now reports 33 errors (`/tmp/wm-compile-probe-17.log`). Remaining
families include shutdown, intent/receiver policy, wallpaper data, assist,
launcher shortcuts, camera callbacks, archiving and splash-screen transfer.

The stock window-client probe now compiles with no errors
(`/tmp/window-clients-compile-3.log`), after adding AudioManager's disabled
navigation-repeat-sound query. This follows adding the declared view-root
proto, view sysprops, HWUI Java stats, sensitive-content AIDL and view/secure-surface
flags. ViewRootImpl, ThreadedRenderer, SurfaceView, TextureView,
SurfaceControlViewHost, WindowlessWindowManager, accessibility interaction and
SplashScreenView remain a separate probe; their runtime cutover is still pending.

Stock receiver filtering now compiles against the hosted router's copied
IntentFilter/exported metadata. Stock wallpaper crop geometry and file processing
compile against a host image record, with their OpenGL utility JNI included.
The next WM probe reports 24 errors (`/tmp/wm-compile-probe-18.log`): shutdown,
assist, launcher shortcuts, archiving and splash-screen transfer remain.
The framework-module gate passes (`/tmp/wayland-glutils-wallpaper-integration-6.log`),
including an exact 8x8 wallpaper crop from a differently colored surrounding
region, failed-image cleanup, and GL texture/subimage readback. Its GLUtils pixel
test exposed the host Skia BGRA versus Android RGBA upload mismatch; the upload
boundary now converts a retained snapshot without mutating the Java bitmap,
preserving row stride and alpha/color-space metadata. Full JVM, system-service,
all 14 APK tests and the C++/graph audits pass
(`/tmp/wayland-receiver-wallpaper-regressions-1.log`). The APK artifacts contain
42 steps with no runtime issues or failure records; the NewPipe drawer screenshot
was inspected. Build-tool checks also pass at this checkpoint.

The WM compile probe now succeeds without errors (`/tmp/wm-compile-probe-19.log`).
Assist retains the absent-service result, archived-state detection follows the
stock predicate and reports an unavailable unarchive backend, and organizer
shortcut calls use the hosted launcher authority with the original caller UID.
Device reboot/shutdown requests report unsupported after their existing permission
checks. The legacy splash parcel now exposes cleanup of its empty resource state;
the real splash transfer still awaits the stock window-client cutover.
These adapters and their negative-result/identity tests pass the framework-module
gate (`/tmp/wayland-optional-wm-integration-4.log`). Full JVM and system-service
gates pass. The full APK run has one fullscreen timeout and no structured runtime
issues (`/tmp/wayland-optional-wm-regressions-1.log`); the retained failure is at
`/tmp/wayland-optional-wm-fullscreen-failure`. Main's `30762a3` addresses fullscreen
state changes without a new rendered frame; the rebased focused regression now
passes (`/tmp/wayland-main4-fullscreen-1.log`, eight issue-free steps).

The closure against the patched package identifies 289 sources and one JNI
boundary for the full WindowManagerInternal graph
(`/tmp/wayland-stock-wmi-closure-2.log`): TaskFpsCallbackController. The successful
WM probe was then promoted after rebasing through main `48f6f7b`, which includes
the fullscreen fix and stock network, TTS, USB, audio and voice foundations.
All 250 WM sources, the policy API, pending-intent records/controller and assist
requester now compile in the regular framework build. The full stock
WindowManagerInternal replaces its stub; the temporary legacy implementation is
generated from that exact API and exposes only its existing three operations.
The stub budget is now 20 pending, with 36 stubs total.

Package `N-V-__8AALLakU07fnHzI275LUUuao9EcLNyA_19-FBLCHi5` includes stock task-FPS
JNI and registration failure/death cleanup. The session publishes the distinct
`android.os.IPermissionController` used by C++ PermissionCache through the
existing activity, package and app-op authorities. The live Java and C++/graph
audits pass (`/tmp/wayland-live-wm-java-3.log`). The first FPS denial test exposed
native Binder reference destruction on an unattached JVM thread. Local payload
cleanup now borrows the caller's JVM attachment or acquires and releases one
around the destructor. The framework-module gate passes with repeated denied
registrations (`/tmp/wayland-live-wm-runtime-2.log`). Cross-process FPS delivery and native permission results now pass, including
process-specific denials, signed grants, package lookup, app-op results, duplicate
registration, death cleanup and concurrent registration/removal
(`/tmp/wayland-live-wm-binder-system-4.log`). The fixture activates the physical
display and submits a painted hardware buffer before expecting FPS: stock SF
intentionally does not compose color-only transactions before its first buffer.
Binder, C++ facade and NDK regressions pass in
`/tmp/wayland-live-wm-binder-system-1.log` (that earlier run's service fixture
failed before its first buffer submission). Main's subsequent stock Activity
commit `ed4c8da` is incorporated, including its lifecycle/result checks and JNI
loader-warning boundary. The source-package inputs are unchanged. Full JVM,
system-service, build-tool, C++/graph and all 14 APK tests pass
(`/tmp/wayland-main4-regressions-1.log`); the APK artifacts contain 46 steps with
no runtime issues or failure records.
Stock WMS/ATMS startup and the client/task presentation cutover remain pending.

Prior checkpoint validation against committed main `8d17396`:
- The combined framework builds, including stock AppOps, window-context
  transactions and resolver/chooser clients. Main supplies stock window clients,
  SurfaceSyncGroup, Debug, SystemConfig and the Linux Perfetto SDK.
- The merged Binder facade passes both branches' tests, including nullable
  output vectors, Unicode framing, generated interfaces and cross-process
  ownership. `cpp-audit` and `graph-audit` pass.
- The post-rebase WMS/ATMS compile probe reports 78 errors, down from 127
  before the rebase, after compiling SensorPrivacyManager and restoring
  ActivityThread display-specific system UI contexts and resource configuration. Remaining dependencies include watchdog/ANR reporting, shutdown,
  assist, sandbox services and desktop policy. Stock WMS/ATMS still do not boot.
- The rebased native Perfetto test and real-SF input/task-output gate pass.
  Runtime registration is ordered as stock memory factories, Binder/host memory
  overrides, then graphics JNI. EGL clients use the shared graphics allocator.
- The system-service suite and NewPipe launch integration pass after restoring
  SF to the cross-process SurfaceSyncGroup fixture. The launch step records no
  structured runtime issues (`/tmp/wayland-rebase-launch-1.log`).
- PixelCopy exposed an empty `platform/host/Readback.cpp` implementation. The
  native library now links pinned AOSP readback, texture-layer helpers and Skia
  AHardwareBuffer image factories through the host EGL context/fence seam. The
  framework regression checks asynchronous ERROR_SOURCE_NO_DATA for an empty
  queue and exact copied pixels after posting a frame. The framework-module
  gate passes (`/tmp/wayland-stock-readback-19.log`), including direct and lazy
  AHardwareBuffer readback. The lazy Skia TUs require the same `SK_GANESH` and
  `SK_GL` backend definitions as the base Skia library. Earlier passing results below apply to their recorded checkpoints.
- The diagnostic dependency probe also compiles stock CriticalEventLog and
  StackTracesDumpHelper with session data paths and the existing nano runtime.
  Their persistence, retention and app-data redaction regression passes in the
  framework-module gate. AssistDataRequester compiles
  alongside WM but still requires ActivityTaskManagerInternal before it can join
  the standalone framework source set.

- `HostInputManagerBridge` now passes the real-SF input gate with the WM callback
  contract, no-focused-window ANR, input monitoring, pointer capture, display
  removal, filter reinjection and rejection of a disconnected filter host.
  Compositor events enter `notifyKey`/`notifyMotion`; Android's injection API
  bypasses filters and is reserved for reinjection. The adapter preserves the
  pinned same-process permission shortcut. Stock WMS imports this boundary in
  place of the hardware-owning InputManagerService; WMS itself is not booted.
- The generated stock `TouchOcclusionMode` replaces the old placeholder enum,
  restoring its actual wire values. Additional stock WM policy values and
  activity clients compile. PendingIntent user/alarm adapters and WM/SF property
  alignment are staged for the remaining service compile and boot work.

- `input-dispatcher-jvm-test` now runs the production `HostInputDispatcher`
  policy/JNI adapter against real SF publication and stock Java InputEventReceiver.
  It passes focused keys, overlap and coordinate transforms, completion of a
  hidden window's gesture, removal cancellation, focus transfer, pointer-capture
  acknowledgement, channel death, rejection of reentrant close and idempotent
  teardown. Its WM lifetime endpoint and policy are test fixtures; stock WMS
  has not booted and Wayland injection is not wired to this adapter yet.
- Stock ImageReader/ImageWriter now pass pixel access, zero-copy queue transfer
  by HardwareBuffer identity, timestamps, acquisition limits, latest-image
  behavior, transition border luminance and zero retained imports. The JNI
  adapter selects real gralloc/HardwareBuffer paths instead of Layoutlib's
  zero-usage paths. The Zig mapper now accepts the all-zero full-buffer access
  rectangle required by the pinned IMapper contract.
- ProtoLog uses the pinned host logging backend instead of a no-op replacement.
  Bootstrap initializes both view and WM groups before use. WM local-service
  values, animation/display threads, snapshot/animation protos and SF sysprops
  are compiled; these types do not yet constitute registered stock services.
  The pre-rebase WM compile inventory reported 127 errors (down from 233 after
  adding the stock AppOps client and generated dependencies). Remaining gaps
  include watchdog/shutdown and assist collaborators, window-client contracts
  and desktop policy. Stock WM is still a separate compile probe; it does not
  yet form part of the running system service graph.
- The complete post-image/input regression run passes `jvm-test`,
  `input-dispatcher-test`, `graphics-buffer-test` and all 14 APK integration
  tests. Its 44 retained APK steps contain no structured runtime issues.

- `task-output-jvm-test` passes two Java-owned private SF outputs, relocation in
  synthetic Android coordinates, scaling, secure-buffer blackout, independent
  teardown and zero retained graphics imports. Java, C++ and NDK resolve the
  same installed Binder libraries and report the same caller identity.
- ActivityManager, TaskSnapshot and ActivityManagerInternal now use pinned
  sources. The local AM adapter routes implemented operations to the existing
  session process/user authority and reports unsupported operations explicitly.
  Stock WMS/ATMS still do not boot; this is dependency and contract work for M5.
- The stock ProcessCpuTracker now completes `init`/`update` using the Zig procfs
  natives. Tests cover sorted/reused PID arrays, command matching, numeric proc
  parsing, raw-kB RSS (five fields), byte-valued PSS and missing-process failures.
- AppTask queries and UID-bound operations now live on the existing task
  authority. The real client exposed the previously missing query during APK
  launch. All 14 APK integration tests pass after that fix; retained steps have
  no structured runtime issues. The stock AM local-interface change also passes
  `framework-module-test`, `activity-options-test` and `system-test`.

### Implementation evidence (2026-09-05)

- `build/graphics.zig` now builds pinned BufferQueue and libui with generated
  GUI AIDL, HIDL graphics enums, aconfig and C++ sysprop outputs. The host patch
  removes optional hwbinder producer wrappers and selects the GBM gralloc seam.
- `src/platform/graphics_buffer.zig` owns validated, versioned DMA-BUF handles,
  independent imports, CPU mapping and allocation metadata. Stock GraphicBuffer
  and stock AHardwareBuffer share the same native object and lifetime. HWUI's
  libui dependencies use this implementation; its GUI cutover is still pending.
- `gui-test` passed CPU and GPU pixels across processes, real sync_file fences,
  slot reuse, resize, bounded dequeue timeout and recovery, cancellation,
  authenticated oneway release callbacks, producer crash cleanup and consumer
  death. Repeated imports and AHardwareBuffer parcel round trips restore FD and
  allocation counts. Stock Surface/ANativeWindow also queues GPU frames into
  the remote queue.
- `binder-cpp-test` passed typed Binder references, nullable enum vectors,
  concurrent weak delegate caching and attachment cleanup, in addition to the
  existing object/FD ownership, generated interface, identity and death tests.
  Native libbase properties now read the same session property area as Java.
- A fresh regression run passed `build-tools-test`, `graph-audit`, `cpp-audit`,
  `native-test`, `binder-broker-test`, `binder-test`, `system-test`, `jvm-test`
  and all 11 APK integration tests: 38 steps, zero structured runtime issues.
  This run validated the initial shared-libui cutover; rerun affected checks as
  the composer closure and presentation integration evolve.
- Stock Surface links through the real composer, transaction, BLAST and window-info
  source closure. `surface-wayland-test` presents 12 GPU frames through separate
  producer and consumer processes using the existing Wayland importer. It checks
  resize, queue backpressure/resume, producer crash, consumer crash and broker
  loss. Each scenario verifies the screenshot and frame journal; graceful
  consumer teardown restores zero live graphics allocations. Fence waits and
  buffer returns run off the Wayland event thread. This is the implicit-sync
  fallback; explicit-sync negotiation and device/modifier selection are pending.
- `window-fixture` builds a Kotlin APK with task/activity, dialog, text editor,
  SurfaceView and lifecycle controls. Three integration tests currently prove
  launch/counter, attached dialog and saved counter across recreation. The
  fixture SHA is generated into the existing testkit registry at build time.
  Managed-only APKs now launch; APKs with only incompatible native libraries
  still fail ABI selection, following NativeLibraryHelper's distinction.
- The complete APK run after RenderEngine integration and the C++ logging
  ownership fix passed all 14 tests (41 retained steps, zero runtime issues).
  `surface-wayland-test` (five
  scenarios), `gui-test`, `binder-cpp-test`, `graphics-buffer-test`, `jvm-test`,
  `graph-audit`, `cpp-audit` and `build-tools-test` also passed. This validates
  the current buffer presentation path, not SurfaceFlinger or task windows.
- The stock GLConsumer regression now passes GPU texture acquisition, acquire
  fences, repeated slot use and resize. The host EGL seam imports registered
  DMA-BUF metadata and explicitly counts display owners: Mesa does not provide
  the initialization reference counting assumed by AOSP's image/probe code.
  HWUI context owners use the same balanced display references.
- `renderengine-test` passes the pinned threaded GL/Ganesh renderer: changing
  GPU input buffers, alpha, translation, six frames alternating between two
  persistent outputs of different sizes, real completion fences and zero live
  allocations after teardown. Updating either output preserves the other's pixels.
  Pinned Skia AHardwareBuffer utilities own imported textures and retain the
  Android object explicitly. Vulkan/Graphite/protected-context creation is
  rejected until the host supports those backends. This is not a running SF
  service; subtree composition is checked separately below.
- `surfaceflinger-frontend-test` passes 60 pinned AOSP hierarchy and lifecycle
  tests in an authenticated AZKB session, including mirrors, relative parents,
  reparenting, handle death and visibility changes. The build generates the real
  HIDL/NDK types and Perfetto protobuf/protozero/C++ headers used by SF's headers.
- `surfaceflinger-composition-test` passes the pinned hierarchy → snapshot →
  LayerFE → RenderEngine path with real GPU inputs and two persistent outputs.
  Private mirror roots isolate the two original subtrees on a shared layer stack;
  synthetic translation, crop, scale, alpha, changing frames and secure-buffer
  blackout are checked with pixels. Updates preserve the other output, and all
  allocations are released. This fixture fixes the legacy active-display rotation
  global to zero and uses resolved transactions directly. It does not yet prove
  SF service publication, transaction scheduling, private virtual-display IDs,
  output BufferQueues, release callbacks or restart behavior.
- The Binder facade now supports the pinned dump transaction, including remote
  UTF-16 arguments, caller identity, status propagation and borrowed caller FD
  lifetime. The corresponding cross-process regression passes.
- The standard `AIBinder_toPlatformBinder` / `AIBinder_fromPlatformBinder`
  conversions use the same Zig Binder object registry. Local and remote
  round-trip tests preserve identity and borrowed-input/owned-result references.
- The pinned C++ ParcelFileDescriptor implementation now links against the Zig
  parcel facade. Tests pass for cross-process transfer after source/reply
  destruction, reliable-channel detach framing, failed detach preserving the
  caller's descriptor, and invalid-descriptor rejection before parcel mutation.
- `perfetto-test` builds the pinned client SDK and its actual Soong generator
  closure, then verifies 24 ordered packets across three independent tracing
  sessions without requiring a tracing daemon.
- `host-composer-test` passes the real ComposerHal interface over the Zig layer
  state and `std.Io` software clock. It checks client-composition validation,
  bounded/stale layer handles, GPU completion fences, cached GraphicBuffer slots,
  reentrant vsync callbacks, power transitions and teardown without leaked
  allocations. The logical display has no physical scanout and explicitly
  advertises unreliable presentation fences. Private outputs still need the
  running SF/BufferQueue/Wayland integration gate; this test does not prove it.
- The full native SF core and generated statistics/power dependencies build in
  `surfaceflinger-core`. `surfaceflinger-service-test` passes publication from a
  separate process, stock application-UID client creation, a committed
  transaction callback, exact privileged-display denial, and service death.
  The retained result is `zig-out/test-results/surfaceflinger-service/run/results.json`.
  The bring-up fixed variable-size LightFlattenable framing in the Parcel
  facade; stock Region/Rect and full LayerState round-trip regressions pass.
- `surfaceflinger-output-test` now passes against the production
  `azk-surfaceflinger` executable. Two mirrored subtrees on one Android layer
  stack compose into separate private GPU virtual-display queues. It checks
  alternating GPU content, crop/scale/alpha, secure blackout, transactional
  resize, callback order, release fences, stalled-consumer isolation, independent
  output destruction and SF death with a retained frame. All local graphics
  allocations return to baseline; the physical display list stays unchanged.
  `surfaceflinger` installs the executable, whose Zig entry point owns readiness
  and stdin shutdown through `std.Io`. The normal launcher now starts it before
  the system process, as covered by the startup gate below.
- The full regression after the SF frontend integration passed the build/native
  audits, Binder/system/JVM tests, GUI/Wayland/RenderEngine checks, all 60 SF
  frontend tests, and all 14 APK integration tests (42 steps, zero runtime issues).
  The GPU subtree check passed separately after its final fixture changes.
- The full regression after running-SF integration also passed
  (`/tmp/sf-running-full-regression.log`): build/native audits, Binder/system/JVM,
  GUI/Wayland/RenderEngine, the SF frontend and both service/output gates,
  Perfetto, host composer, and all 14 APK integration tests. The APK suite
  recorded 43 steps with no structured runtime issues.
- Remaining native work includes compositor device/modifier negotiation,
  explicit-sync negotiation, display/input event integration and connecting the
  SF outputs to task-managed Wayland surfaces. M5–M11 are not complete.
- The M5 input-channel foundation now builds in `build/input.zig` with generated
  input flags and the pinned CXX header. `input-channel-test` passes all ten
  pinned InputChannel tests. `input-channel-broker-test` passes stock
  InputChannelCore transfer over AZKB, token identity, duplication after source
  destruction, input acknowledgements and peer close.
- The pinned InputPublisher/InputConsumer and unmodified Rust verifier now build
  through `build/input.zig`, `build/rust.zig` and the typed Soong closure in
  `build/rust_sources.zig`. The package contains AOSP's selected Rust 1.83.0
  compiler and host runtime. CXX 1.0.119 is built from pinned source; input
  constants and Binder NDK bindings use the actual AIDL/bindgen inputs.
  `input-publisher-test` passes 15 pinned C++ tests and `input-verifier-test`
  passes all 44 pinned Rust tests, covering gesture validation, keyboard
  classification and persisted-data compatibility. The AZKB channel test now
  uses the real publisher/consumer, checks KeyEvent fields and finished timing,
  and verifies peer death. All four input gates, `cpp-audit`, `graph-audit` and
  `build-tools-test` passed together (`/tmp/input-production-4.log`). The stock
  transport is complete; service integration and WMS/ATMS boot remain pending.
- `build/input_dispatcher.zig` now builds the pinned dispatcher, key maps,
  HMAC verification, input tracing, latency aggregation and StatsD host clients
  from the curated package. Package-manager/StatsD AIDL, KLL protobuf, native
  statistics and window/StatsD flags are generated by stock tools. The only
  dispatcher source patch selects OpenSSL's declaration of `CRYPTO_memcmp` on
  the host; signing and verification remain stock. `input-dispatcher-test`
  passes six interface tests: overlapping windows and coordinate transforms,
  gesture target retention, focus/key ordering, cancellation on window removal,
  UID mismatch rejection, broken-channel isolation and signed-event tampering
  (`/tmp/input-dispatcher-production-2.log`). This gate supplies window metadata
  directly and uses AOSP's fake WMS policy. It does not prove WMS boot or the
  production input-service connection to SF. Perfetto and statistics are linked;
  their full dispatcher-specific runtime behavior is not yet covered.
- `input-surfaceflinger-test` passes the native SF/dispatcher integration gate
  (`/tmp/input-surfaceflinger-8.log`): SF publishes window/display metadata over
  AZKB, native tokens preserve identity, scaled parent containers produce the
  expected frames/local coordinates, overlap selects the top window, SF focus
  requests reach the dispatcher, moving one window changes routing, and removing
  a touched window cancels its gesture while the other remains usable. All
  three supervised processes exit zero. The test supplies a WMS lifetime Binder,
  a bounded focus-service fixture and a DMS projection; it exercises real SF
  boot completion but **does not boot WMS/ATMS or provide a production IMS**.
  SF's input publication is gated on resolving inputflinger in `bootFinished`.
  Production bootstrap must satisfy that ordering with the real service graph.
- That integration uncovered an HWC adapter bug: SF unconditionally queries
  requested LUTs after validation, so a backend requesting none must return
  success with empty lists, as pinned HidlComposer does. Returning UNSUPPORTED
  aborted physical-display validation. The adapter and regression now implement
  that contract and accept observationally neutral unit layer brightness.
  No physical scanout/presentation completion is claimed.
- The generated package-manager interface needed Parcel's UTF-8 string-vector
  methods with UTF-16 wire storage. The facade now provides the pinned string
  and vector overloads (including optional/owned nullable values), rejects
  invalid UTF-8 before writing and preserves partial-read mutation. Binder
  tests check independent UTF-16 framing, nulls, malformed input and a Unicode
  vector round trip across AZKB. Zig still owns the Parcel storage/transport.
- `input-channel-jvm-test` passes stock InputChannel JNI over the shared Zig
  Binder/Parcel: channel pairs, native/Java token identity, nullable/uninitialized
  channels, Unicode names, duplication, Parcel framing, ownership transfer and
  disposal. The follow-up JVM and full 14-test APK regression passed
  (`/tmp/input-channel-full-regression-1.log`).
- Stock MessageQueue, InputEventReceiver and InputEventSender JNI replace the
  custom queue's native pointer boundary. A small host adapter borrows stock
  Looper poll state and combines native/Java deadlines. Two native outer-loop
  tests pass alongside the ten pinned InputChannel tests. The Java JNI gate
  passes key/motion delivery, explicit motion batching, handled/unhandled
  replies, cross-thread sends, receiver exception rethrow/recovery, fd listener
  unregistration and disposal (`/tmp/input-event-jni-4.log`). The follow-up
  native/Binder/GUI/input/SF, build audit/tool, JVM and full APK regression passes:
  14 APK tests, 41 steps, zero runtime issues
  (`/tmp/input-event-full-regression-1.log`). The retained NewPipe drawer frame
  was inspected as well.
- Stock InputApplicationHandle/InputWindowHandle JNI now registers in the
  application island, with pinned common JNI and region helpers. The JNI gate
  passes application/channel/window identity, frame/region conversion, cached
  metadata updates, token/region/focus-transfer clearing, and SF output fields
  (`/tmp/input-handle-jni-2.log`). The native test adapter is a separate test-only
  library. Real Java SurfaceControl crop handles and stock service boot remain
  pending. The APK launch check and full JVM/APK follow-up regression pass:
  14 APK tests, 41 recorded steps, zero runtime issues, with build audits passing
  (`/tmp/input-handle-full-regression-1.log`).
- The full post-dispatcher regression passed: native unit checks, Binder/GUI,
  HWC, both running-SF gates, six input gates, build audits/tools, JVM and all
  14 APK tests with 43 recorded steps and zero runtime issues
  (`/tmp/input-dispatcher-full-regression-1.log`). The updated production SF
  executable is installed by that run.
- The graphics rerun exposed two lifecycle/test issues. The shared runner now
  uses `build/command.zig` to inherit the invocation's Mesa paths across cached
  configurations, and it rejects unexpected service/broker exit codes even if
  the client succeeds. SF supervision now terminates its C++ runtime with
  `_Exit`, matching stock SF's process lifetime, rather than racing global
  destructors against live workers. Binder, BufferQueue, both running-SF gates,
  all four input gates and native audits pass with the stricter runner
  (`/tmp/input-native-regression-3.log`). Both SF processes exit zero; only the
  explicitly requested BufferQueue crash producer exits by signal. Runner
  regression tests verify that a failed service cannot be masked by its client.
- The broad JVM closure was rerun against this worktree's freshly built
  `framework-classes.jar` (`6e8abe6fdd98f0dbd6374662632bacb7` cache directory).
  It still reports 2,596 sources, 587 AIDL entries and 981 blocker entries.
  Existing replacements count as present, so the report is an inventory for
  narrowing the service seams, not evidence that those services can boot.

Local artifacts: `zig-out/test-results/bufferqueue/run/results.json`,
`zig-out/test-results/binder-facade/run/results.json`,
`zig-out/test-results/wayland-surface/`, `zig-out/test-results/renderengine/`,
`zig-out/test-results/surfaceflinger-frontend/run/results.json`,
`zig-out/test-results/surfaceflinger-composition/run/results.json`,
`zig-out/test-results/integration/`,
`/tmp/wayland-full-current.log`, `/tmp/wayland-regression.log`, and
`/tmp/wayland-implementation-closure.log`. The full post-RenderEngine regression
is `/tmp/wayland-graphics-full-regression.log`; the strengthened independent
output test is `/tmp/renderengine-independent-test.log`.
Current SF integration evidence is `/tmp/sf-foundation-full-regression.log` and
`/tmp/sf-composition-4.log`. Running-service evidence is retained in
`zig-out/test-results/surfaceflinger-service/run/results.json` and
`zig-out/test-results/surfaceflinger-output/run/results.json`; the first
production-process run is `/tmp/sf-process-1.log`.

A preliminary closure run during planning seeded ViewRootImpl,
WindowManagerGlobal, SurfaceControl, TaskOrganizer, WMS, ATMS, InputManagerService
and DisplayManagerService together. Against an available classes jar in the main
worktree it reported 2,596 source nodes, 587 AIDL entries and 981 blocker entries,
including generated sources, unselected modules and host-replacement linkage.
This is a broad static traversal, not 981 distinct implementation tasks or a
verified minimal closure: it reaches unrelated service branches and treats many
existing replacements as present. The jar was not rebuilt for this planning
pass. M0 must rerun against the implementation baseline and reduce the closure
at the documented host boundaries before adding sources. The raw planning report
is retained locally at `/tmp/wayland-plan-closure.log`.

Reproduce against a freshly built classes jar with:

```sh
python3 tools/aosp-closure.py --classes /absolute/path/to/framework-classes.jar \
  --aosp /absolute/path/to/pinned-aosp closure \
  android.view.ViewRootImpl android.view.WindowManagerGlobal \
  android.view.SurfaceControl android.window.TaskOrganizer \
  com.android.server.wm.WindowManagerService \
  com.android.server.wm.ActivityTaskManagerService \
  com.android.server.input.InputManagerService \
  com.android.server.display.DisplayManagerService
```

- The launcher now supervises broker → SurfaceFlinger → system-server startup
  and reverses that order on shutdown. `session-test` passes exact readiness,
  startup/shutdown timeouts, missing/malformed executables, EOF and descriptor,
  child and runtime-directory cleanup. The `--service-exec` trampoline transfers
  child ownership before exec failure, avoiding the pinned Zig process-spawn
  error path's leaked pipes and unreaped child.
- Stock HardwareBuffer, GraphicBuffer and SyncFence Java/JNI own the same libui
  objects as NDK clients. `buffer-jvm-test` passes independent Java/NDK/Parcel
  owners, CPU Canvas colors, GPU writes, real sync_file duplication and Parcel
  transfer, invalid/closed handles and zero live allocations. Host Skia uses
  explicit RGBA for Android RGBA/RGBX buffers; host-native N32 is BGRA and swapped
  red/blue channels when reused as the Android format.
- Stock SurfaceControl, SurfaceSession and Choreographer Java/JNI are enabled.
  `surface-client-jvm-test` passes creation, alias identity, Parcel transfer,
  transaction ownership, committed callbacks, per-surface vsync phase order and
  removal against the launcher's separate SF process. AZKB attaches every native
  dispatch worker before invoking C++ listeners in an ART process. Zig still
  owns Binder objects, Parcel object tables and transport.
- The full regression after this cutover passed `jvm-test`, `integration-test`,
  `native-test`, `cpp-audit` and `graph-audit`
  (`/tmp/stock-surface-full-regression-1.log`): all 14 APK tests, 40 steps,
  zero structured runtime issues. This validates the stock frame scheduler on
  the existing hosted window path; stock WMS/ATMS and HWUI Surface presentation
  are still required before M5/M6 can be marked complete.
- `surface-buffer-jvm-test` now passes stock Surface, SurfaceTexture and BLAST
  Java/JNI, CPU and hardware Canvas frames, Java/NDK Parcel ownership, resize,
  twelve HWUI frames across two independent consumers, sibling pixel isolation,
  consumer abandonment through ReliableSurface and BLAST transaction commits
  through SF. All local buffer allocations return to baseline. The production
  GPU adapter dequeues/imports/queues stock ANativeWindow buffers with completion
  fences and shares one GBM/Ganesh context across RenderThread window pipelines.
  It uses surfaceless EGL because GBM does not advertise pbuffer configurations.
- HWUI's host RenderThread now reuses pinned native Choreographer scheduling
  and the stock Looper-driven ThreadBase. This restores deferred-frame retries
  and callback cancellation/deduplication. Properties/Binder JNI registration
  precedes display JNI, matching AndroidRuntime's initialization dependency.
  The targeted graphics gates and NewPipe launch pass after this change
  (`/tmp/stock-window-launch-1.log`). The full regression exposed a legacy
  synchronized-child stall: autonomous RT frames exhaust Mesa buffers while
  the UI owner waits in `syncAndDraw`, preventing its parent commit. The host
  `CanvasContext::doFrame` adaptation leaves legacy targets/explicit offscreen
  jobs owner-driven and retains stock RT scheduling for native Surface targets.
  The correction passes the desktop APK and queue gates, followed by the full
  `jvm-test`, `integration-test`, `native-test`, `binder-cpp-test`, `system-test`,
  `cpp-audit` and `graph-audit` run (`/tmp/stock-window-full-regression-2.log`):
  14 APK tests, 41 steps, zero structured runtime issues.
  These gates do not yet replace the hosted window clients or WMS/ATMS.
- The stock UriGrantsManagerService, local interface and grant/owner records now
  replace the permissive hosted URI service. Hosted process attachment and PM
  permissions supply its collaborators; acquisition through an authority grant
  still leaves exact URI/mode checks to the stock provider Transport. The A/B
  process tests verify private-provider access, read/write separation, prefix
  grants, persistable grants and revocation (`/tmp/stock-uri-system-1.log`).
  The complete APK/JVM/RenderEngine/audit regression passes afterward
  (`/tmp/stock-uri-full-regression-1.log`). Cross-boot grant restoration still
  depends on restoring the package catalog before the stock service reads it.
- Stock IntentFirewall and FileObserver Java/JNI now compile with generated
  system-server event tags. `intent-policy-test` verifies activity/service/
  broadcast isolation, sender permission/signature checks, atomic replacement,
  close-write updates and deletion through the stock inotify/Handler reload
  path (`/tmp/stock-intent-policy-2.log`). Its PM collaborators use the hosted
  package catalog. Connecting this policy object to stock ATMS remains part of
  the service boot gate; the current hosted task manager is still authoritative.
- `TaskGeometry` implements the M7 bounded synthetic allocator independently of
  desktop coordinates. It preserves live origins when possible, reserves both
  old/new bounds until a transaction is acknowledged, and rejects capacity,
  oversized, stale-generation and overlapping requests without changing live
  bounds. Cancellation and task disappearance invalidate old acknowledgements.
  `task-geometry-test` passes deterministic placement/restore/relocation checks
  and 2,000 mixed operations (`/tmp/task-geometry-test.log`). The shell must
  commit only after its matching Android bounds transaction, and remove only
  after Android task removal. Organizer/presentation wiring is still pending.
  Non-resizable tasks retain their Android size and will be letterboxed by the
  presentation adapter; oversized/capacity failures leave an existing window
  at its last accepted size and reject creation of a new output.
- Stock ActivityOptions, ActivityTransitionState, enter/exit coordinators and
  SplashScreen now replace the option/splash interface stubs. The Activity and
  ActivityThread adaptations retain transition data, callback lifecycle and
  splash-listener cleanup. The transitional ViewRoot adapter drops nonterminal
  input while a transition is paused, preserves Back and cancels terminal
  events. Splash theme updates use the stock package user state and enforce
  package ownership; a pinned setter annotation is corrected to admit the
  documented null reset. `activity-options-test`, cross-process service checks
  and the APK launch gate pass (`/tmp/stock-activity-options-4.log`).
- Stock UserState, ProgressReporter, AppTimeTracker and HostingRecord compile
  with their generated proto outputs. The single hosted user's boot walks
  BOOTING → RUNNING_LOCKED → RUNNING_UNLOCKING → RUNNING_UNLOCKED and delivers
  SystemServiceManager callbacks before PHASE_BOOT_COMPLETED. Local/Binder user
  queries share that state. The complete APK/JVM/audit regression passes
  (`/tmp/stock-options-full-regression-1.log`): 14 APK tests, 42 steps and zero
  structured runtime issues. The stub audit has 63 pending replacements.

## Architecture and ownership

```text
APK processes
  stock Activity/ViewRootImpl/WindowManagerGlobal/SurfaceControl/HWUI
     | Binder transactions             | Surface/BufferQueue buffers
     v                                 v
hosted system-server                 hosted SurfaceFlinger process
  stock WMS/ATMS                    stock layer/transaction/composition logic
  display/input collaborators       RenderEngine + host graphics backend
  Kotlin WaylandTaskShell              |
     | task operations                | per-task output BufferQueues
     +-------------------+------------+
                         v
                Zig Wayland session process
                connection, seats, outputs,
                per-task xdg_toplevels and buffer presentation
```

Use the launcher as session supervisor. Define startup readiness, identity and
shutdown contracts for broker, graphics service, system server and APK peers.
Keep the Wayland connection and protocol objects in one session owner rather
than opening a connection for every task. Kotlin owns organizer callbacks and
Android task decisions; Zig owns Wayland objects, Linux resources and GPU buffer
allocation/import. A narrow source-compatible C++ adapter connects AOSP graphics
types to those operations. Do not send pointers or invented C++ layouts across
processes.

The control endpoint between shell and presentation must be session-authorized,
carry stable task IDs plus a generation, and express create/configure/focus/
present/destroy operations. Frame identity, buffer lifetime, configuration
generation and acknowledgements must be explicit. Use existing AZKB/FD transport
where appropriate; keep frame traffic and blocking waits off the Wayland event
thread and Android global lock. New Zig I/O, descriptor ownership and
synchronization use `std.Io`, including code extracted from older raw-FD paths.

## Decisions to resolve with executable gates

1. **Linux EGL versus Android native windows.** Host Mesa cannot be assumed to
   accept an Android ANativeWindow as a Wayland EGL native window. Keep stock
   Surface/BufferQueue and adapt the existing HWUI host GPU target to dequeue,
   import a DMA-BUF as an EGLImage/render target, render, and queue with a fence.
   Reuse stock rendering logic above that boundary. Do not build another fake
   Surface or a second queue scheduler to hide the platform mismatch.
2. **SurfaceFlinger host backend.** Port the pinned RenderEngine/composition and
   output interfaces onto GBM/EGL with GPU composition as the first backend.
   Trace the hardware composer, display scheduling, properties and service
   dependencies. Supply a bounded host backend with real timing/capabilities;
   do not implement a broad fake composer service whose methods silently succeed.
3. **Task subtree output.** Try the pinned `mirrorSurface`, display surface and
   layer-stack transaction path before introducing an SF extension. A private
   SurfaceFlinger virtual output is not necessarily an Android logical display
   published by DisplayManager. Keep per-task outputs out of the application's
   display list. Validate this distinction rather than ruling out useful SF
   output machinery because its name contains “virtual display”.
4. **Synthetic display size.** Stable non-overlapping task rectangles need a
   bounded allocation policy, not an enormous automatically growing display.
   Test candidate bounds against stock WMS clipping, app-visible display metrics,
   maximum window metrics and non-resizable activities before fixing constants.
   Allocation failure must produce a defined launch/resize outcome; never wrap,
   silently overlap, or resize every app's display on each new task.
5. **Internal service closure.** WMS takes concrete InputManagerService and ATMS
   objects and shares ATMS's global lock. ATMS initialization also needs
   IntentFirewall, PendingIntentController, ActivityManagerInternal, URI grants,
   package and other LocalServices. An existing remote Binder facade does not
   satisfy these in-process contracts. Reuse stock collaborators where feasible;
   integrate the hosted process/package infrastructure through narrow adapters.

If the preferred route fails a gate, record the observed dependency or behavior
and the smallest required adaptation. Do not quietly replace stock WMS, ATMS or
SurfaceFlinger with another host implementation and call the plan complete.

## Implementation milestones

### M0 — Baseline, source closure and test fixtures

- Record the source/hash baseline and run the existing checks. Inventory all
  window/graphics replacements, source patches, singleton targets and service
  registrations that will be retired.
- Trace native sources starting at `IGraphicBufferProducer.cpp`,
  the local `IGraphicBufferConsumer`/`IConsumerListener` headers, BufferQueue producer/consumer/core, `Surface.cpp`,
  GraphicBuffer, Fence, SurfaceComposerClient and LayerState. Use the pinned
  `libs/gui/Android.bp` lists to find AIDL, aconfig, sysprop, libui and utility
  dependencies. The `NO_BINDER` bufferqueue target is not the required transport.
- Run `tools/aosp-closure.py` on a live classes jar, seeding window clients and
  WMS/ATMS/input/display services together. Keep compile closure separate from
  runtime startup and LocalServices requirements.
- Add Kotlin fixture APK activities for two independent tasks, two activities
  sharing a task, handled/unhandled resize, saved state, dialog, SurfaceView,
  text editing and lifecycle journaling. Declare fixtures in the existing build
  and testkit, without application-specific framework branches.

Gate: reviewable dependency inventory, explicit host adapters and fresh baseline
artifacts. Future targets listed here are proposed until their milestone lands.

### M1 — Stock libgui source build over the existing Binder runtime

- Add a reusable native graphics build module, initially covering actual Bp/Bn
  producer/producer-listener interfaces and local consumer interfaces, BufferQueue, Surface and their libui
  dependencies. Keep a single shared copy of Binder utilities and native handles.
- Generate the real GUI AIDL interfaces and flags through typed declarations in
  `build/generator_sources.zig` and `build/generators.zig`. Add native flag/sysprop
  generation where missing; do not check in invented generated headers.
- Extend the Binder source facade only for contracts required by this closure:
  parcelables and containers, object attachment/lifetime, listener/delegator
  identity, caller/thread context, death and errors. Preserve bounds and FD
  limits; buffer batches must fail cleanly before partial export if too large.
- Adapt `BufferQueueThreadState` at the host boundary: AZKB has no HIDL Binder
  dispatch domain. Preserve the actual AZKB caller instead of returning the
  service process's Linux UID. Do not introduce a fake hwbinder runtime.
- Trace Surface's composer queries. Compile their real interfaces now; execute
  the independent BufferQueue operations first and defer composer-dependent
  behavior to M4. Unavailable queries return documented errors, not fabricated
  display statistics.
- Add producer/consumer cross-process tests, including a pure Zig Binder client
  regression sharing the runtime with generated C++ and Java clients.

Gate: the selected unmodified AOSP graphics sources compile and link against
production libraries; actual remote Bp/Bn calls, callbacks, identity, malformed
parcels and process death pass. Test output identifies source and library hashes.

### M2 — Shared host graphics allocation, import and synchronization

- Extract and extend the GBM-backed buffer functionality already in
  `platform_abi.zig`. GraphicBuffer allocator/mapper and AHardwareBuffer must
  converge on shared storage/lifetime without assuming their current pointer
  representations are interchangeable. Audit stock conversion/cast assumptions
  and update the C++/C boundary before enabling native consumers.
- Define a versioned host native-handle payload carrying dimensions, format,
  usage, layer count, plane count, strides, offsets and modifiers. Keep AOSP's
  GraphicBuffer/Fence outer serialization. Validate imported payloads, integer
  ranges, FD counts and backing sizes where queryable; never transport a GBM
  object pointer. Own imported resources independently of sender lifetimes.
- Implement allocation, import, free, map/unmap and capability queries. Start
  with the RGB/RGBA formats needed for UI; add the actual video/YUV formats for
  M10. Preserve usage, dataspace and layout metadata. Unsupported formats and
  protected usage fail explicitly.
- Negotiate render-device and compositor DMA-BUF format/modifier compatibility.
  Use linux-dmabuf feedback; account for device differences. A GPU conversion
  into a compatible output buffer is an explicit fallback, not CPU readback.
- Specify ownership for acquire/release fence FDs, duplicates and NO_FENCE.
  Import producer fences before GPU reads/writes and export completion fences.
  Cover import failure, cancellation, timeout, allocation exhaustion and teardown.

Gate: a second process imports an allocated buffer, sees verified GPU-written
pixels and waits on a real synchronization fence. Exercise supported CPU mapping
separately. Repeated allocation/import/destruction returns live handles and FDs
to baseline; malformed imports and unsupported requests leave no resources behind.

### M3 — One stock Surface/BufferQueue rendered to Wayland

- Run a native producer process using stock Surface/ANativeWindow and a separate
  consumer/presentation process using stock BufferQueue. Exercise the full
  dequeue/request/queue/acquire/release cycle and cache buffers by queue slot and
  generation according to AOSP semantics.
- Connect the host EGL/Skia render target to the acquired GraphicBuffer. Make
  producer completion visible through real fences; validate pixel content and
  changing frame identity. Buffer transport alone is not this milestone.
- Extend the existing Wayland DMA-BUF importer for task output rather than
  creating a separate demo Wayland client. Negotiate explicit synchronization
  when supported. Provide a correct fallback: wait for producer completion off
  the event thread and reuse only after compositor release according to the
  selected synchronization protocol. Frame callbacks do not release buffers.
- Keep queue pressure bounded. A blocked producer must not block Binder callback
  dispatch or Wayland event processing. Test withheld consumer release, dequeue
  timeout, resumed release, size changes with buffers in flight, producer crash,
  consumer crash and broker loss.
- Use `tools/shot.sh --runner` for the multiprocess fixture and retain compositor
  logs, queue/fence journals, resource counts and screenshots.

Gate: GPU-rendered frames cross process boundaries through stock BufferQueue and
AZKB and appear in one Wayland window. Resize and backpressure work, and every
failure path terminates or recovers without a leaked queue or hung peer. This
completes the immediate buffer-path milestone, not the full task-window project.

### M4 — Hosted SurfaceFlinger and per-task composition output

- Bring up a separately supervised SF process using the pinned transaction,
  layer-tree, scheduling and composition implementation and a host display/GPU
  backend. Publish the actual composer service interfaces over AZKB. Provide
  one hosted display and the display-event path needed by clients.
- Extend the libgui/Binder closure for SurfaceComposerClient, SurfaceControl,
  transaction completion, buffer release callbacks, permissions and frame events.
  Missing service identity or permission enforcement is a failure, not a reason
  to authorize every Binder peer as system.
- Compose a fixture layer subtree containing independently updating parent,
  child, crop, transform and alpha layers into a supplied output BufferQueue.
  Try mirror + private output/layer stack first. Prove no unrelated task layers,
  recursive mirrors or extra app-visible display IDs are introduced. If this
  fails, implement the smallest documented SF subtree-output extension.
- Preserve secure-layer exclusion, damage, dataspace, buffer retention and
  release synchronization. GPU composition is acceptable; continuous
  captureLayers/screenshot readback is not a production path.
- Drive scheduling from a real clock and Wayland presentation feedback where
  available. Keep compositor pacing, Android vsync requests, actual presentation
  timestamps and buffer-release events distinct. One stalled task output must
  not freeze other tasks or falsify timing reports.

Gate: two independent subtrees update through SF into two output queues, with
correct crop/alpha/transform and independent lifetime. Test transactional resize,
callback order, secure-layer handling and SF death before attempting WMS boot.

### M5 — Input/display infrastructure and stock WMS/ATMS boot

- Extend the existing hosted system-server bootstrap in pinned AOSP startup
  order. Bring up the concrete input/display and LocalServices dependencies of
  WMS/ATMS, including process attachment/launch, package state, URI grants,
  pending intents, policy, settings/resources, power and window animation threads.
  Reuse stock service implementations where possible; document each host adapter.
- Use the pinned WMS/ATMS container graph, global lock, TaskOrganizerController,
  transaction/lifecycle machinery and freeform policy. Remove duplicate hosted
  activity/task authority when registering stock ATMS. Retain host process
  spawning instead of requiring zygote, but connect it to real process records,
  IApplicationThread attachment, client transactions and death handling.
- Bring up stock input channels/dispatcher and window-info publication. Wayland
  supplies input at the host boundary; an evdev device reader is not required
  to invent a second path to the same input stream.
- Publish exactly one Android logical display with explicit metrics, density,
  refresh policy and capabilities. Ensure DisplayManager discovery does not
  publish the private SF task outputs. Enable pinned freeform/multiwindow
  resource/feature policy without enabling a second set of desktop decorations.

Closure note: the pinned InputManagerService seed is not an isolated Java
service import. The live-build inventory reaches WMS/AMS through Watchdog;
assuming Watchdog present still reaches WMS via WindowManagerPolicy →
DisplayRotation, and reaches AMS through Task. The inventory counts existing
host replacements as present, so it understates the source/contract work when
those replacements are removed. Restore the service graph and its collaborators
in the boot order above; an input-only compile does not prove WMS/ATMS readiness.

Gate: stock WMS/ATMS boots, accepts a real client window, manages task/activity
records and dispatches transactions through AZKB. No dummy successful startup
calls, competing service registrations or second task database remain active.

### M6 — Stock application window clients and HWUI cutover

- Seed closure for ViewRootImpl, WindowManagerGlobal/Impl, Surface/SurfaceControl,
  SurfaceSession, Choreographer, ThreadedRenderer, SurfaceView, TextureView and
  organizer/transaction value classes. Reuse AOSP JNI where it requires AOSP C++
  objects; implement host-specific JNI/state in Zig as the native policy requires.
- Replace each current client shim with its stock source only when its service,
  JNI and native object contracts are ready. Migrate ActivityThread's host window
  adapter and ApkHost launch path to the stock session/window lifecycle while
  preserving existing launch, result, relaunch and saved-state behavior.
- Route HWUI output through the M3 buffer backend and M4 composition, replacing
  the direct global Wayland render target. Keep exact AOSP native signatures,
  exception behavior, Surface validity and release semantics.
- Update source sets, generators, stub audit/budget and package hash in the same
  changes. Never compile a replacement and stock class under the same FQN.

Gate: one real APK runs on the stock window client/service/graphics path. Window
attach/detach, draw reporting, dialogs, input and handled/unhandled resize pass;
the existing full APK suite remains free of runtime issues.

### M7 — Task shell, synthetic geometry and multiple toplevels

- Add Kotlin `WaylandTaskShell` with production and in-memory presentation
  adapters. It owns organizer registration and initial task list, appearance,
  changes, disappearance, output creation and cleanup. Deduplicate callbacks by
  task token/generation and preserve leash ownership through transitions.
- Refactor `wayland.zig` into session connection/seat/output ownership and
  per-task surface state. Refactor `src/main.zig` and renderer singleton state so
  presentation is not tied to whichever APK happened to start the session.
- Implement a deterministic bounded synthetic rectangle allocator with stable
  origins, checked arithmetic and non-overlap. Prefer in-place resize; relocate
  only when necessary, using a coordinated task transaction. Define exhaustion,
  reuse, task restoration and oversized/non-resizable task policy. Synthetic
  changes must not be inferred from desktop movement. Preserve WMS's window
  container/buffer-child hierarchy: SF uses parent transforms for buffer-layer
  input geometry. Applying task position/scale to the buffer itself is not an
  equivalent input transform; the native SF input gate covers this distinction.
- Assign task bounds through WindowContainerTransaction. Map each organized
  application task to one toplevel and an M4 output. Keep home/system roots from
  accidentally becoming ordinary app windows. Map fullscreen/maximize/restore
  to Android task bounds/windowing policy; closing a toplevel requests task
  removal and lets Android run lifecycle callbacks before final destruction.
- Use compositor decorations when available; define a minimal host decoration
  fallback without duplicating Android desktop chrome. Keep task title/app ID
  and activation requests tied to authoritative task state and valid serials.

Gate: two tasks on the same display have separate rendered toplevels, stable
non-overlapping synthetic bounds, correct multi-activity grouping and independent
close/restore. Geometry tests cover capacity, adversarial resize and removal.

### M8 — Input, focus and visibility

- Route each Wayland event through its surface identity to the current task
  generation and then through stock Android input channels/window hit testing.
  Map surface-local coordinates through scale, viewport/crop and any letterbox
  transform before adding the synthetic origin. The simple origin-plus-local
  formula is valid only when those spaces have unit scale and no translation.
- Keyboard seat focus determines Android keyboard/task focus. Pointer enter
  selects a pointer target and must not steal keyboard focus on hover. Activated
  toplevel state is presentation information, not proof of input focus. Track
  touch targets through their gesture and cancel on target loss. Pinned
  InputDispatcher retains an active touch stream when its window becomes
  invisible; removal cancels it. Suspension/unmap actions that require gesture
  cancellation must explicitly end the input stream and cannot rely on hiding
  the SF surface. Match the input-window UID to its buffer producer so stock
  untrusted-overlay checks remain effective. Handle key
  release/repeat, capture, relative motion, stylus, wheel and seat removal.
- Update WMS focus and stock input window metadata coherently. Validate hit
  testing when synthetic rectangles are far apart and desktop windows overlap;
  do not bypass modal windows, touchable regions or trusted-input checks merely
  because Wayland identified the task.
- Keep mapped tasks conservatively visible when occlusion/minimization is
  unknown; apply pinned multi-resume policy, rather than forcing every activity
  resumed. Define suspension/unmap handling and optional compositor state inputs.

Gate: events reach the intended window with correct local and raw coordinates;
hover does not change keyboard focus; focus/cancel/lifecycle ordering matches
AOSP. Closing or crashing a focused task leaves no stuck keys or captured seat.

### M9 — Configure, frame pacing, scale and output changes

- Track configure serials and a separate content/configuration generation per
  task. Apply the latest selected compositor size via Android task configuration,
  respect handled versus relaunch behavior, and commit compatible content after
  acknowledging the applicable configure. Coalesce obsolete requests without
  losing terminal acknowledgements. Buffer dimensions alone are not proof that
  Android handled the corresponding configuration.
- Define what is shown while a new frame is pending and how stalled/dead clients
  are handled. Ensure stale frames cannot overwrite the new generation and old
  buffers remain owned until released. Task relocation and destruction must
  invalidate pending input/configuration work safely.
- Track all outputs and per-surface preferred scale/transform. Start with stable
  Android logical density and presentation scaling per task; test whether pinned
  task overrides justify optional per-task density changes later. Never mutate
  the shared display density whenever one toplevel moves between outputs.
- Negotiate fractional scale/viewport/presentation capabilities and fallback
  behavior. Distinguish requested frame rate from actual output scheduling;
  throttle inactive outputs without deadlocking Android's draw lifecycle.

Gate: resize storms, fullscreen/maximize/restore, fractional scales, mixed-scale
outputs and output unplug preserve Android configuration, input transforms,
frame ordering and bounded resource usage. Test delayed frames and fences.

### M10 — Media, IME and desktop interoperability

- Move SurfaceView/TextureView and FFmpeg decoder output into Android's surface
  hierarchy and the shared buffer path. Preserve crop, transform, color metadata,
  video/UI ordering and synchronization. Retire the independent per-app Wayland
  video subsurface shortcut after parity tests pass.
- Connect stock input-method client/service contracts to a hosted text-input
  bridge. Negotiate Wayland text-input support; implement preedit, commit,
  surrounding text, selection/deletion and focus loss with the proper UTF index
  conversions. Preserve Android editor actions and composition. Define a
  keyboard-only fallback and the insets policy where desktop IME geometry is
  unavailable; do not fabricate a screen-space IME rectangle.
- Connect the hosted clipboard service to Wayland data offers with MIME
  negotiation, seat serial rules, bounded asynchronous FD transfer, cancellation
  and ownership loss. Handle permissions and avoid clipboard echo loops.
- Implement Android-to-Android and Android/native drag-and-drop using Wayland
  surface-local targeting and data offers, including drag icons, URI grants,
  accepted actions, cancellation and process death. Do not infer movement through
  global desktop coordinates.
- Define and implement task-bound system-window/tooltip/popup policy and
  explicit behavior for task-spanning overlays, PiP, transitions and IME
  surfaces. Preserve secure-window rules and permission checks. Features that
  core Wayland cannot represent need a documented failure or constrained host
  presentation policy; they must not silently paint into an unrelated task.
- Scope accessibility trees, actions and bounds by task/window. Keep Android
  synthetic coordinates as the Android contract and label test/host local
  coordinates explicitly. Never present them as real global desktop positions.

Gate: text composition and editing, clipboard, cross-task/native drag-and-drop,
dialogs/popups, video and accessibility work through task-addressed integration
tests. Unsupported protocol combinations have verified, observable fallback
behavior. Existing NewPipe fullscreen, navigation and playback tests pass.

### M11 — Recovery, default cutover and removal of legacy paths

- Test startup failure and shutdown at each process boundary, app/producer/
  consumer death, organizer death, graphics-service restart, compositor loss,
  broker loss and GPU allocation/import failure. Recover the session when the
  contracts allow it; otherwise terminate affected clients with explicit Android
  errors and clean resources rather than reconnecting stale Binder/surface IDs.
- Bound queue depth, outstanding frames, callbacks, retries and cached imports.
  Run repeated launch/resize/destroy and pressure tests with FD/GPU-memory
  accounting, starvation/deadlock checks and delayed release fences.
- Make the task path the default. Remove the old window stack, fake surface
  implementations, global render target and superseded source patches only after
  their replacements pass all gates. Temporary development selection must select
  one complete service stack, not mix incompatible old/new object lifetimes.
- Update architecture, native boundary, source audit and user-facing capability
  documentation. Keep reproducible test commands and retained artifacts.

Gate: the default launcher completes the entire acceptance matrix below with no
required legacy graphics/window path and no unexplained runtime issues.

## Ordering and review boundaries

The critical path is M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8 → M9 → M10 → M11.
Source-closure work for M5, pure geometry tests for M7 and testkit task addressing
can be prepared while native graphics is being brought up. Actual service/window
cutover waits for its dependencies. M4 must prove composition before WMS/ATMS
integration commits to a particular per-task output mechanism.

Split milestones into reviewable source/build, host implementation, integration
and regression commits as needed. Each AOSP manifest change carries a regenerated
archive hash in the same commit; use an isolated packaging checkout if another
session is modifying the full checkout. Run audits at each native/JVM boundary
change. Do not expand every Binder or HAL API speculatively ahead of its consumer.

## Verification and definition of done

Add focused build targets as milestones land, for example `gui-test`,
`graphics-buffer-test`, `wayland-buffer-test` and `task-shell-test`. `gui-test`, `graphics-buffer-test`, `surface-wayland-test` and
`window-fixture` now exist; other names here remain proposed. Pure queue/geometry/state tests must run
without a display or GPU; hardware tests must report required capabilities and
must not count a skipped GPU path as a passing hardware gate.

Use the existing Cage harness for single-output DMA-BUF/HWUI regressions. Cage's
kiosk behavior cannot prove independent desktop placement, overlapping toplevels
or mixed-output behavior. Extend `tools/shot.sh` with an explicit compositor
selection for a headless desktop compositor for those cases, retaining the same
artifact and test-control conventions. Do not add a second headless runner.
Also run a desktop protocol smoke against supported real compositors.

Extend `azk-test`/the testkit with stable task/window selectors and session-level
diagnostic collection. Cross-process service/native failures must enter the
structured journal, not disappear into a separate stderr file. Every UI action
under test uses a step with its own mark/settle boundary. Add bounded settle
conditions for configuration applied, frame presented and task removed.

| Contract | Required evidence |
| --- | --- |
| Shared Binder runtime | Zig, generated C++, NDK and Java clients coexist; references/FDs and errors survive cross-process calls |
| Graphics | Verified changing GPU pixels through stock BufferQueue and SF; no continuous CPU capture; format/modifier and synchronization tests |
| Tasks | Two independent tasks, same display ID, stable bounds; several activities share one toplevel; proper launch modes and results |
| Windows | Dialog, popup, SurfaceView/video, alpha/crop/transform and secure-layer policy |
| Input | Real overlap, focus changes, touch cancellation, keyboard repeat/capture, scale/letterbox transforms and trusted-target checks |
| Configuration | Handled/unhandled resize, saved-state relaunch, stale frames, resize storms and callback ordering |
| Outputs | Fullscreen/restore, fractional scale, mixed outputs, output unplug and no accidental global density changes |
| Desktop bridges | IME preedit/commit, clipboard, DnD/URI grants, accessibility and explicit unavailable-capability behavior |
| Resources/recovery | Slow peers, queue exhaustion, delayed fences, task/service/compositor death; bounded resources and no hung peers |

Run the smallest affected check first, then the existing regression gates:

```sh
nix develop ./artzig -c zig build -j6 build-tools-test graph-audit cpp-audit
nix develop ./artzig -c zig build -j6 native-test binder-cpp-test binder-broker-test binder-test system-test jvm-test
nix develop ./artzig -c env AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  zig build -j6 integration-test
```

Supply the host Mesa EGL/GBM/VAAPI paths when required by this environment and use
`WLR_RENDERER=gles2`. Keep the pinned APK SHA check. Inspect `steps.jsonl` and
`app.log` on every integration failure, plus failure/a11y/screenshot artifacts.
Passing requires expected state and empty `diagnostics.runtime.issues` for every
step; `--allow-missing` is not a regression pass. Retain native queue/fence logs
and source/library hashes alongside UI artifacts.

## Pinned source anchors

Paths below are relative to the full checkout identified by `aosp/README.md`.
They are behavioral references even when not yet in the curated package:

- `frameworks/native/libs/gui/Android.bp`: bufferqueue source lists, Binder and
  HIDL dependencies, generated interfaces and flags.
- `frameworks/native/libs/gui/{BufferQueueProducer,BufferQueueConsumer,BufferQueueCore,BufferQueueThreadState,Surface}.cpp`:
  queue state, ownership, caller identity and composer-dependent Surface methods.
- `frameworks/native/libs/ui/{GraphicBuffer,Fence}.cpp`: native-handle and fence
  transport, import and release contracts.
- `frameworks/native/libs/gui/SurfaceComposerClient.cpp`: `mirrorSurface`,
  `Transaction::setDisplaySurface` and `Transaction::setDisplayLayerStack`.
- `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp`: `mirrorLayer`,
  `createVirtualDisplay`, display state and composition lifecycle; continue into
  the pinned CompositionEngine/RenderEngine backends during M4.
- `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java`:
  `main`, construction, policy initialization and the shared ATMS lock.
- `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java`:
  `initialize`, `onActivityManagerInternalAdded`, `setWindowManager`, process and
  LocalServices collaborators.
- `frameworks/base/core/java/android/window/{TaskOrganizer,WindowContainerTransaction}.java`:
  registration, task callbacks, bounds/reparent transactions and leash ownership.
- Wayland protocol XML selected by `build.zig`, plus the protocol XML for newly
  negotiated extensions from the development environment's pinned protocol
  package. Validate protocol requests, serials and fallback behavior against
  those definitions, not assumptions from an unrelated Android or compositor version.
