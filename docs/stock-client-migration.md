# Stock client migration: bulk instructions

Goal: every class an application process runs on is the verbatim AOSP class,
except the seam that talks to PipeWire, Wayland, FFmpeg and EGL. That seam
stays exactly as it is until everything else is stock. The hosted services in
`src/java/dev/artzig/system` are the server side and remain host-owned.

Read `AGENTS.md` first; every rule there applies. This document only says what
to do, in what order, and where the traps are. State of the tree when this was
written: 225 stubs, pending budget 105, `docs/stubs.md` regenerated.

## Follow-up scope (2026-09-05)

The original batches reached the fence below; that is not completion of the
stock-client goal. The follow-up authorizes replacing the remaining activity
core, service-backed clients, isolated pending classes, and test machinery in
`android.*`. Java callers previously covered by the fence may now be changed
or replaced. Move necessary host behavior to service or native seams and keep
the pinned AOSP client behavior.

Wayland, PipeWire, and FFmpeg backend implementation work is out of scope.
Preserve those existing backends. A replacement that requires new behavior in
one of them remains a documented dependency, not a completed stock migration.
EGL was not excluded from the follow-up. The audit ratchet, AOSP closure check,
reproducible packaging, and module and full APK regression requirements remain
in force.

Follow-up milestones (newest first; earlier entries describe their state at that point):

- SystemServiceRegistry now compiles verbatim; its 820-line hosted adaptation is
  deleted. The batch selects 582 original Java sources covering the registry's
  module initializers and client dependencies, including UWB, Nearby, Thread,
  AdServices, contextual search, printing, blob storage and virtualization APIs.
  AIDL, aconfig, HIDL, sysprop and proto outputs come from pinned generators;
  ConfigInfrastructure's proto relocation and AVF's original BuildFlags template
  follow their Soong declarations. Twelve handwritten Ravenwood annotations are
  deleted; eleven have original replacements and one obsolete annotation has no
  remaining callers. All 582 added Java selections match the checkout verbatim.

  Original ArrayUtils JNI serves both the framework and relocated module class,
  retaining native non-movable arrays and zeroization. Original SerialPort JNI
  is registered. These imports do not add radio hardware or a virtualization
  backend: stock factories honor absent platform features. Registry tests cover
  per-context construction, concurrent waiters, interrupt preservation, failed
  construction retry, module names and absent virtualization. Wi-Fi service tests
  now assert stock lookup returns null without hardware, then construct the stock
  client explicitly to retain Binder permission and lock coverage.

  JVM, system, native, Binder and build checks pass, including 16 closure-tool
  regressions. WebView HTML/JavaScript/cookie and visible rendering tests pass.
  All 11 APK integration tests pass with 37 captured steps and zero structured
  runtime issues; WebView and fullscreen-video screenshots are inspected.
  The AOSP archive is rebuilt and repinned, both archive checksums are verified,
  and ART is unchanged. The inventory remains 11 stubs (6 pending, 5 survivors).
  Self-targeting overlays, window/client adaptations and the remaining server
  dependencies still require work.

- WebView, WebSettings, CookieManager and WebIconDatabase now use the original
  AOSP Java sources, with their original supporting classes. The 750-line hosted
  provider and its desktop Chromium/CDP path are deleted. The bundled Android
  Chromium 133.0.6943.137 APK loads its actual provider and native library through
  the APK class loader. Original WebViewUpdateServiceImpl2 owns provider selection,
  signature/version checks and preparation ordering behind a hosted Binder facade.
  The facade uses the package manager, settings and package-change broadcasts;
  optional RELRO, WebView zygote and pinning optimizations are absent.
  Service startup now preserves declared secondary process names, establishes
  package identity before native Binder initialization and deduplicates pending
  starts. Provider dependencies are tracked for termination on replacement.
  Child processes retain Chromium's seccomp filter; glibc's optional rseq thread
  optimization is disabled before exec. Android isolated/external-service UID and
  per-instance semantics remain unfinished; renderer processes currently use the
  provider package UID.

  Original NDK looper, input, bitmap, native-window and shared-memory wrappers
  are compiled. Java MessageQueue and NDK ALooper now share the original native
  Looper. Shared memory uses original libcutils memfd allocation and seals; tests
  cover descriptor ownership, immutable size, existing writable mappings and
  rejection of later write access. Native property handles use the same broker
  property area as Java, including long callback values and serial changes.
  Native font paths resolve to the existing hosted font inventory. Original HWUI
  functor ownership and drawing replace the host no-ops. An EGL adapter puts
  Chromium and HWUI on the same display with an OpenGL ES context, allowing their
  texture images to be shared. SurfaceFlinger overlays and image-reader producers
  remain explicit unsupported boundaries; no new Wayland, PipeWire or FFmpeg
  backend is introduced.

  The actual provider passes HTML/JavaScript/title, secure-cookie lifecycle and
  input-fallback tests. The Cage test clicks a DOM button, requires no structured
  issues, and checks visible page pixels; the retained screenshot shows text and
  the updated counter. JVM/system/native/Binder and build checks pass, as do 21
  testkit tests. All 11 APK integration tests pass with 38 captured steps and zero
  structured runtime issues, including visibly rendered fullscreen video. The
  AOSP archive is rebuilt and repinned; the 12 selected WebView Java sources match
  the pinned checkout verbatim. Both archive checksums are verified; ART is
  unchanged. Inventory is 11 stubs (6 pending, 5 survivors). The registry,
  window/client adaptations and remaining server closure are still unfinished.

- AAudio now exports 42 C ABI entry points over stock Java AudioTrack and the
  existing PCM stream, including every AAudio import in the staged Chromium
  library. The build directly reuses AAudioStreamParameters, AAudioUtilities and
  AudioContainers for native validation and channel/format conversion. HostedAAudio
  owns stream handles, buffer allocation, PCM submission, callback threads and
  lifecycle. It preserves callback-active write rejection, callback-close/stop
  rejection, optional state-wait output, stop/flush frame accounting and 64-bit
  extension of AudioTrack's wrapping positions. Fixed callback storage is allocated
  before open succeeds; close drains callback delivery. Positive write timeouts use
  the pinned legacy path's blocking behavior. PCM16 and float output use the same
  unchanged backend. Capture, MMAP, offload, other PCM formats and unavailable
  device selections retain failure results. Reported performance is NONE and sharing
  is SHARED; the 10ms burst is a host submission quantum, not a measured physical
  PipeWire period. Tests cover C ABI validation, real frame progress, timestamps,
  bounded writes, callbacks and lifecycle. JVM/system/native/Binder and build checks
  pass; all 11 APK tests pass with 38 captured steps and zero structured runtime
  issues. The AOSP archive is rebuilt and repinned with nine original inputs;
  ART is unchanged. Inventory remains 15 stubs (8 pending, 7 survivors). The native
  entry points are prerequisites: Android Chromium has not yet been initialized,
  and stock WebView/provider and window/server work remain unfinished.

- The NDK codec boundary now exports 24 AMediaCodec entry points over verbatim
  Java MediaCodec and the existing decoder. Native callers can create/configure
  decoders, submit compressed buffers, read decoded output, query independent
  formats, flush/restart, receive asynchronous callbacks and release ownership.
  C++ conversion helpers use original AMessage/AMediaFormat and the pinned
  key/value converters; JNI manages attachment for native caller threads.
  Callback deletion drains delivery before releasing native caller data. The
  libmediandk soname maps to the platform island, whose Binder dependency exports
  codec calls. A single library handle resolves both the original format and new
  codec symbols. Tests exercise the C ABI with synchronous/asynchronous MPEG-to-PCM
  decoding, timestamps, EOS, callbacks, format snapshots and failure/cleanup paths.
  Native window and secure-codec configuration remain explicit unsupported
  operations; this is not the full NDK media ABI or an initialized Chromium provider.
  JVM/system/native/Binder and build checks pass; all 11 APK tests pass with 37
  captured steps and zero structured runtime issues. No AOSP/ART archive inputs
  changed, and both tarballs remain current. Inventory remains 15 stubs (8 pending,
  7 survivors). Chromium still needs AAudio and provider/runtime integration.

- MediaCodec now compiles verbatim; the 833-line Java adaptation is deleted.
  Stock Java owns public validation, callback delivery and buffer invalidation.
  The pinned 48 outer JNI methods (including persistent-surface release) and four
  LinearBlock methods are registered. HostedMediaCodecBackend owns the existing
  decoder's state, bounded buffer queues, codec configuration, format snapshots,
  flush/restart and asynchronous notifications. JNI gives each returned buffer an
  independent view, with native byte order and read-only decoded output. Stop and
  reset clear asynchronous mode as in AOSP. Timestamped video releases retain a
  bounded number of frames until presentation; flush and release cancel them.
  The eight existing decoder JNI operations move to HostedCodecEngine; FFmpeg and
  presentation implementations are unchanged. Encoding, secure codecs, LinearBlock,
  image/block queues, frame-render notifications and additional surface/resource
  features retain explicit unsupported diagnostics or pinned capability failures.
  Tests decode MPEG audio to PCM and check timestamps, independent buffer access,
  invalidation, asynchronous flush ordering and stop/reset behavior. JVM/system,
  native/Binder and build checks pass. All 11 APK tests pass with 39 captured steps
  and zero structured runtime issues, including fullscreen playback. The original
  MediaCodec source was already in the current AOSP archive, so neither archive
  needs rebuilding. Inventory remains 15 stubs (8 pending, 7 survivors); this
  adaptation was outside the inventory. WebView/provider and window/server work
  remain unfinished.

- AudioTrack now compiles verbatim; the 344-line Java adaptation and its separate
  typed-write placeholder are deleted. All 54 pinned JNI entry points are registered
  and their Kotlin bridge descriptors checked at startup. HostedAudioTrackBackend
  owns PCM stream lifetime, bounded client queues, blocking/nonblocking submission,
  stop draining, flush/position resets, timestamp queries and partial playback-parameter
  updates. The existing platform/audio.zig backend changes only its JNI registration
  class name. Short and float arrays now submit PCM through that same backend, with
  PCM8/float conversion into PCM16 at the JNI boundary. Original Java owns public
  validation, buffer-position mutation, PlayerBase registration and lifecycle calls.
  Latency follows the native allocated-frame-count formula; downstream host-device
  latency is not measured. Client allocations are bounded by the existing one-second
  stream ring. Static/offload tracks, rate changes, independent-channel gain, marker
  callbacks, volume shaping and additional audio-policy operations remain unavailable;
  this is stock-client migration, not full audio-platform parity. Tests cover buffer
  limits, typed writes, playback progress, flush/release, released-object queries,
  partial parameter updates, latency and release interrupting a blocked writer.
  JVM/system/native/Binder and build checks pass. All 11 APK tests pass with 37 captured
  steps and zero structured runtime issues, including fullscreen playback and exit.
  The retained fullscreen frame shows video rendering. AOSP is rebuilt and repinned;
  the added AudioTrack source matches the pinned checkout verbatim. ART is unchanged
  and verified. Inventory remains 15 stubs (8 pending, 7 survivors), since AudioTrack
  was an adaptation outside the stub inventory. MediaCodec's remaining closure is
  one Java source and its JNI boundary; WebView/provider and window/server work remain.

- AudioManager now compiles verbatim with 65 shared media, TV, picture-profile and
  audio-focus Java dependencies; the 614-line placeholder is deleted. The hosted
  audio service delegates focus ordering, delayed grants and Binder death to the
  original MediaFocusControl and FocusRequester, with the original HardeningEnforcer
  behind the AudioService permission boundary. Players receive AOSP's focus-loss
  callback fallback when server ducking/fading is unavailable. Existing PCM output
  remains fixed-volume and reports its speaker route and absence of direct/offload
  profiles. MediaExtractor uses the original Java/JNI client, native Binder service,
  data sources, metadata and WAV/MP3/AAC parsers. A packaging patch registers the
  statically linked original extractors and accounts for their hosted service UID;
  selection, sniffing and sample parsing remain AOSP. Original native permission
  cache/controller clients reach the same hosted permission and AppOps services as
  Java callers. The batch generates 35 Java and 102 native AIDL declarations, with
  native protocols mostly consumed as headers, and adds the HIDL token protocol.
  MediaDescrambler JNI and CAS clients retain real service discovery; no CAS hardware
  service is added. MediaCodec's nested crypto values now follow the pinned Java
  implementation, while the outer codec and AudioTrack remain hosted adaptations.
  JVM/native tests cover focus callbacks across processes, permission checks, crypto
  ownership, WAV sample bytes/seek/EOS, and MP3/AAC sample framing. The framing fixtures
  do not test compressed-audio decoding. System, Binder/C++ Binder and build checks
  pass. All 11 APK tests pass with 37 captured steps
  and zero structured runtime issues. AOSP is rebuilt and repinned; all 341 added
  regular archive inputs match the checkout plus declared patches. ART is unchanged
  and verified. Inventory: 15 stubs (8 pending, 7 survivors). The AudioTrack/MediaCodec
  Java closure is now two sources with JNI blockers; WebView/provider, inline surfaces,
  the remaining server contracts and broader backend-dependent work are unfinished.


- MediaMetrics now uses verbatim AOSP Java, the original native serialization
  library and Binder client, and the original native PersistableBundle. Its JNI
  registration table has one compiler-portability patch (`constexpr` to `const`);
  the entries and runtime behavior are unchanged. The shared Parcel bridge uses
  the pinned Java pool allocation path. CAS and common-memory AIDL, native CAS
  HIDL protocols and TV input constants are generated from the pinned sources:
  15 Java AIDL inputs, seven native HIDL sources and their headers, and one native
  AIDL protocol. No native metrics collector or CAS hardware service is claimed.
  Tests cover Java and C typed metadata, buffer growth/clear, copy and string
  ownership, native serialized headers, service-absence and invalid-buffer errors,
  CAS parceling, and native-to-Java nested PersistableBundle conversion. JVM,
  system, native, Binder/C++ Binder and build checks pass; all 11 APK tests pass
  with 38 captured steps and zero structured runtime issues. AOSP is rebuilt and
  repinned; all 20 added archive inputs match the checkout plus the declared JNI
  portability patch. ART is unchanged and verified. Inventory remains 16 stubs
  (9 pending, 7 survivors). The remaining AudioManager/focus closure is 79 source
  nodes, 34 AIDL inputs and three blockers: MediaExtractor, MediaDescrambler and
  the nested MediaCodec API referenced by TV clients. This is a prerequisite
  checkpoint, not completion of AudioManager or the overall migration.


- The HIDL transport now uses the original eight Java runtime classes, five JNI
  translation units, libhidlbase and libhwbinder. The build generates 86 Java
  outputs for HIDL base/manager, CAS and GNSS protocols, plus the seven C++
  base/manager protocol sources and their headers. The hand-written GNSS
  measurement callback constants are deleted. Android's Binder UAPI header is
  isolated from host libc headers; shared Binder exception reporting follows the
  pinned logging and fatal-Error path. Stock HwNoService and native service
  discovery report the absence of a hardware service manager. This supplies real
  serialization and protocol code; it does not install GNSS/CAS hardware services.
  Tests exercise native scalar/vector/string blobs, bounds and array capacity,
  generated proxy transactions through HwNoService, service absence and deep
  equality/hash behavior. JVM, system, native, Binder/C++ Binder and build checks
  pass; all 11 APK tests pass with 37 captured steps and zero structured runtime
  issues. AOSP is rebuilt and repinned, all 133 added archive file inputs are
  byte-verified, and ART is unchanged and verified. Inventory: 16 stubs (9 pending,
  7 survivors). The remaining AudioManager/focus closure, including the original
  HardeningEnforcer, is 80 source nodes, 48 AIDL inputs and 18 blockers; 13 of those
  blockers are CAS AIDL include-root entries for the next bulk import.


- RingtoneManager and AccessibilityShortcutController now compile verbatim with
  their shared 39-file media, subtitle, widget and shortcut-dialog closure. The
  two placeholders and the compile-only MediaPlayer SDK header are removed.
  Original MediaPlayer Java now owns listener delivery, release cleanup and
  PlayerBase tracking. Its hosted JNI adapter follows the pinned native client's
  disconnected-service state and errors; it does not supply a MediaPlayer playback
  service. Ringtone asset queries still need MediaProvider, and haptic asset
  analysis remains explicitly unsupported at the audio-service boundary.
  The hosted audio service allocates session/player IDs across processes and
  tracks caller-owned playback configurations, Binder death, privileged snapshots
  and anonymized public callbacks. Audio focus and the remaining AudioManager
  adaptation still need replacement; this checkpoint does not claim audio-policy
  or playback parity. Tests cover JNI DRM and data-source errors, descriptor
  ownership, callback cleanup, snapshot isolation, cross-UID mutation rejection,
  ringtone URI restoration and shortcut lock-screen settings. JVM, system, native,
  Binder/C++ Binder and build checks pass; all 11 APK tests pass with 37 captured
  steps and zero structured runtime issues. All 39 Java archive inputs match the
  pinned checkout. AOSP is rebuilt and repinned; ART is unchanged and verified.
  The inventory is 17 stubs (9 pending, 8 survivors).


- Shared audio-client prerequisites now compile in one batch: 14 original HDMI
  client/model classes, the three AudioMetadata classes, and 19 generated AIDL
  inputs for HDMI callbacks/services and audio mix/offload transport. All 17 Java
  archive inputs match the pinned checkout verbatim. Tests cover typed metadata
  keys, copy isolation, both byte orders, Atmos metadata translation, malformed
  input, audio-mix union tags and Binder-token identity, HDMI soundbar-mode client
  selection, callbacks, executor delivery, and port connect/disconnect updates.
  HDMI tests use an in-memory Binder fixture; no CEC hardware service or new audio
  backend is added. JVM, system, native, Binder/C++ Binder and build audits pass,
  along with all 11 APK tests (39 steps without runtime issues). The AOSP archive
  and package pin are refreshed. The remaining combined AudioManager,
  RingtoneManager, AudioTrack and MediaCodec closure drops from 135 sources/68
  AIDL inputs to 109/48; its 36 native/service/generator blockers remain.
  The closure tool now reads AIDL roots from the build configuration, removing
  seven earlier false blockers. The stub inventory remains 19 (11 pending,
  8 survivors); this batch supplies dependencies for the remaining audio clients.



- DownloadManager now compiles verbatim and uses the original DownloadProvider
  in a separately built, host-signed system APK. The batch adds 19 provider Java
  classes, their resources, generated flags and supporting annotations; the
  682-line hosted DownloadManager is deleted. Boot scans bundled system APKs,
  and the stock activity-thread provider path starts the provider under its
  manifest process name and a separate broker UID. System APK data is scoped to
  the Binder session because those UIDs are session-assigned: download history
  and cached files do not persist across sessions. App-private external files
  can persist; the former client's binary download records are not imported.
  Hosted storage classifies the external volume using Android path conventions,
  skips media scans for unindexed private deletions, and retains an inode reference
  for asynchronous close-size accounting across a completed-file rename.
  Public downloads, media indexing and the complete documents-provider experience
  still require MediaProvider; this is not full download-platform parity.
  JobScheduler now accepts UPDATE_DEVICE_STATS-authorized proxy jobs while keeping
  service ownership separate from source attribution. NetworkStats records tagged
  operation reports with permission checks; UID byte accounting remains unavailable.
  Headless APK startup also fixes borrowed session-JSON strings and initializes
  the native-library search path for APKs without bundled JNI libraries.
  Tests cover real local HTTP transfers, stable size after rename, URI and query
  behavior, destination collisions, deletion, revoked grants and HTTP errors.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (39 steps,
  zero structured runtime issues). AOSP is rebuilt and repinned; all 115 added
  file inputs are byte-verified against the checkout and three provider patches.
  ART is unchanged and verified. Inventory remains 19 stubs (11 pending, 8
  survivors): DownloadManager was an adaptation outside that inventory.


- Hosted connectivity now validates Internet access with HTTP/HTTPS probes instead
  of treating a Linux route as proof. Original NetworkStack captive-portal result
  and probe-spec classes, validation metrics and their Java/AIDL closure compile
  verbatim. The hosted NetworkMonitor adaptation owns asynchronous probing, PAC
  fetches, fallback/partial/portal decisions, retry backoff, contradictory application
  reports and rejection of results from replaced routes. Stock ConnectivityManager
  reports cross the generated Binder interface with network-state and Internet
  permission checks. This is the default probe algorithm, not the full NetworkStack
  daemon: CAPPORT, resource overlays, private-DNS and data-stall monitoring remain
  unavailable. Deterministic local HTTP and fake-clock tests pass, along with JVM,
  system, native/Binder and build checks. All 11 APK tests pass (41 steps, zero
  structured runtime issues). An earlier fullscreen timeout passed unchanged in
  both the focused rerun on the same portrait stream and the complete suite.
  AOSP is rebuilt and repinned; all nine added inputs are byte-verified. ART is
  unchanged and verified. Inventory remains 19 stubs (11 pending, 8 survivors).
  The stock DownloadManager/DownloadProvider source closure now has no unresolved
  compile blockers; provider packaging, installation and storage integration remain.


- Hosted job scheduling now evaluates stock NetworkRequest capabilities and
  passes the selected Network through JobParameters. Network jobs wait for a
  matching network, start on arrival, and stop with the connectivity reason on
  loss; deadlines may run without a network, and jobs without network constraints
  receive none. Network changes defer until start acknowledgement. Binding cleanup
  also handles completion before bindService returns and late connection callbacks
  after cancellation. Tests exercise these contracts through stock Binder protocols;
  system tests reject modern connectivity jobs without ACCESS_NETWORK_STATE.
  The hosted permission check applies the pinned default target-SDK gate; server-only
  compatibility override metadata remains unavailable for the uncompiled scheduler.
  Linux Ethernet/Wi-Fi observations now include their non-VCN/non-roaming capabilities.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (38 steps,
  zero structured runtime issues). One earlier APK run showed NewPipe's external-feed
  parse error; the focused test and complete suite passed unchanged on rerun.
  AOSP/ART archives are unchanged and current. The inventory remains 19 stubs
  (11 pending, 8 survivors). Real Internet validation is still required before
  stock DownloadProvider's default network jobs can run; route presence alone
  does not set VALIDATED. Provider packaging and storage integration also remain.


- DownloadProvider's remaining filesystem support now compiles verbatim:
  MediaFile, MtpConstants, MetadataReader and FileSystemProvider. Tests exercise
  MIME/MTP mapping precedence, metadata support and stream ownership, document
  identity and ancestry, queries and filtered search, directory metadata, and
  descriptor reads/appends on a private temporary document root. JVM/system,
  native/Binder and build checks pass; all 11 APK tests pass (39 steps, zero
  structured runtime issues). AOSP is rebuilt and repinned; all four added
  sources are byte-verified. ART is unchanged. The inventory remains 19 stubs
  (11 pending, 8 survivors). These are supporting classes, not an installed
  downloads/documents provider: job network delivery, provider packaging and
  storage integration still precede replacing the hosted DownloadManager.


- The legacy DRM client now compiles verbatim: 14 Java classes and 17 native
  sources, including the original JNI registrar and AOSP's absent-service
  `NoOpDrmManagerClientImpl`. Tests cover public argument validation, empty engine
  discovery, native query/error values, rejected conversion sessions, borrowed
  descriptor ownership, asynchronous failure callbacks and repeated cleanup.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass
  (39 steps, zero structured runtime issues). AOSP is rebuilt and repinned;
  all 49 added source/header inputs are byte-verified. ART is unchanged.
  The inventory remains 19 stubs (11 pending, 8 survivors). This closes the
  legacy DRM prerequisite for DownloadProvider; provider integration and the
  stock DownloadManager replacement remain outstanding. No DRM service,
  plugins or content decryption are supplied by the absent-service fallback.


- FileObserver now compiles verbatim with its original Linux inotify JNI.
  Apps and provider code receive real filesystem events through AOSP's observer
  thread and weak listener registry. Tests exercise create/write/move/delete events
  across two directories, callback thread identity, directory-self deletion,
  idempotent start/stop, restart and paths missing when watching begins. JVM/system,
  native/Binder and build checks pass; all 11 APK tests pass (39 steps, zero structured
  runtime issues). AOSP is rebuilt and repinned; both added sources are byte-verified.
  ART is unchanged. The inventory remains 19 stubs (11 pending, 8 survivors).
  The pinned DownloadProvider project is now available for reuse analysis; this
  closes its filesystem-observer prerequisite, while the legacy DRM client,
  provider integration and stock DownloadManager replacement remain outstanding.


- SystemSensorManager and its SensorEventCallback/SensorEventListener2 closure
  now compile verbatim; the hosted Java manager is deleted. Its 19 JNI entries
  preserve discovery and failure behavior at the absent sensorservice boundary.
  Stock Java owns device policy, listener/callback validation, injection modes,
  channel validation and queue cleanup. Tests cover service caching and empty
  discovery, invalid/null listeners, duplicate dynamic-callback registration,
  injection enable/disable results, failed event connections, native discovery
  list ownership, and direct-channel failures that preserve caller-owned shared
  memory. JVM/system, native/Binder and build checks pass; all 11 APK tests pass
  (37 steps, zero structured runtime issues). AOSP is rebuilt and repinned; all
  three sources are byte-verified. ART is unchanged. The inventory remains
  19 stubs (11 pending, 8 survivors), since this was an adaptation outside the
  stub audit. Physical/runtime sensor production and injection remain unavailable;
  the existing presentation and media backends are unchanged.


- MediaMetadataRetriever now compiles verbatim; its constants/no-data Java stub
  is deleted. The 13-method JNI seam preserves the pinned native client's state
  when the media.player retriever service is absent: data-source setup fails,
  reads have no extracted data, and release changes subsequent calls to released-client
  errors. Stock Java owns path/URI dispatch, option and image/frame checks, bitmap
  parameters and genre parsing. Tests cover those contracts, header validation,
  borrowed descriptor and callback-source ownership, failed-read parameter mutation,
  all nine native operations after release, repeated cleanup and reuse. JVM/system,
  native/Binder and build checks pass; all 11 APK tests pass (37 steps, zero structured
  runtime issues). AOSP is rebuilt and repinned, and the added source is byte-verified.
  ART is unchanged. The inventory is 19 stubs (11 pending, 8 survivors); the removed
  retriever was a survivor. Metadata/frame extraction remains unavailable and would
  require a service/backend; this batch adds no FFmpeg implementation.


- The native platform now compiles AOSP's AMediaFormat implementation, its private
  message bridge, and ten foundation sources verbatim. Native callers get the original
  typed scalar/string/buffer/rectangle storage, copying, ownership and formatting rules.
  A dedicated C ABI test covers value round trips, null/missing-key behavior, copied
  input buffers and strings, retained copies after clear, and exported key constants;
  it also runs under native-test. JVM/system, native/Binder and build checks pass;
  all 11 APK tests pass (37 steps, zero structured runtime issues). AOSP is rebuilt
  and repinned; all 43 selected inputs are byte-verified against the checkout. ART
  is unchanged. The inventory remains 20 stubs (11 pending, 9 survivors). This supplies
  native format values needed by the Chromium media bridge; it does not yet supply
  libmediandk codec operations, AAudio or the embedded Chromium provider. The existing
  Wayland, PipeWire and FFmpeg backends are unchanged.


- SystemServerInitThreadPool's empty shutdown stub is replaced by the pinned
  implementation. Hosted system startup starts the pool; stock SystemServiceManager
  shuts it down at boot completion. Task submission, pending-work tracking, shutdown
  waits and failure behavior remain AOSP. A small server diagnostics patch replaces
  Android's debuggerd/watchdog dump request with the hosted process's ART thread dump.
  Tests cover startup and duplicate-start rejection, pending-work diagnostics, waiting
  for tasks during shutdown, post-shutdown rejection and restart. JVM/system,
  native/Binder and build checks pass; all 11 APK tests pass (37 steps, zero structured
  runtime issues). AOSP is rebuilt and repinned; the source and exact diagnostics patch
  are byte-verified. ART is unchanged. The inventory is 20 stubs (11 pending,
  9 survivors). This closes the server boot-pool placeholder; the remaining client
  and Chromium-provider work is still outstanding.


- WebChromeClient now compiles verbatim with JsPromptResult and PermissionRequest;
  the narrow callback stub is deleted. The complete AOSP callback surface supplies
  default web-permission denial, quota responses, legacy file-chooser cancellation,
  forwarding to the deprecated console callback, and JavaScript prompt results.
  ART tests exercise these observable callback contracts alongside the existing
  hosted WebView tests. JVM/system, native/Binder and build checks pass; all 11 APK
  tests pass (37 steps, zero structured runtime issues). All three sources already
  match the pinned AOSP archive, so both tarballs are unchanged. The inventory is
  21 stubs (12 pending, 9 survivors). WebView, WebSettings, cookie storage and the
  actual embedded Chromium provider remain unfinished.


- PermissionManagerService now supplies AOSP's Binder facade, attribution registry
  and permission checker. Its four-source closure includes the original one-time
  permission helper and PermissionThread. A small server startup patch selects the
  Kotlin hosted package-grant policy through PermissionManagerServiceInterface;
  permission checking and AppOps transactions are unchanged AOSP source. The former
  HostedPermissionManagerService and HostedPermissionCheckerService are deleted.
  Operation checks now enforce AppOps modes, preflight avoids starting accounting,
  data-delivery starts/finishes use stock nested accounting, and unregistered or
  modified attribution chains are rejected. Existing metadata and attribution tests
  pass, along with new Binder regressions for mode denial, chain trust, normal and
  runtime permissions, preflight and nested data delivery across the test processes.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (37 steps,
  zero structured runtime issues). AOSP is rebuilt and repinned; all four added
  archive inputs, including the exact server startup patch, are verified. ART is
  unchanged. The inventory remains 22 stubs (13 pending, 9 survivors). Permission
  grant/revoke policy and a PermissionController service remain unfinished; importing
  the one-time helper does not establish those capabilities. The remaining client
  adaptations and real Chromium provider are still outstanding.


- AppOpsService now compiles verbatim with its 42-source Java closure, two generated
  sensor-privacy Binder interfaces and sensor-privacy proto constants. The previous
  HostedAppOpsService is deleted. Stock code owns package/UID mode precedence, access
  accounting and history, nested start/finish lifetimes, mode/active/noted callbacks,
  user restrictions and usage-specific audio restrictions. Package UID binding now
  notifies AppOps, and PackageManagerLocal supplies isolated snapshots with the stock
  close/lifetime contract. SensorPrivacyManager is stock; its hosted policy service
  enforces permissions and applies microphone/camera restrictions through AppOps.
  The existing Environment host patch separates session-local system policy paths
  from persistent app data; launcher and system-test startup share that configuration.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (37 steps,
  zero structured runtime issues), including the app-data path regression. Tests also
  cover mode persistence, callbacks, nested accounting, restrictions, privacy permission
  and null-argument ordering, and package snapshot isolation. AOSP is rebuilt and
  repinned; all 45 added archive inputs and six system-directory redirects are verified.
  ART is unchanged. The inventory remains 22 stubs (13 pending, 9 survivors).
  This does not complete the permission stack: the hosted permission checker still
  lacks AppOps data-delivery integration, and permission policy mutations remain.
  Process foreground state still uses the hosted AMS attach/death model; sensor privacy
  has no hardware toggle or SystemUI reminder. Remaining clients and a real Chromium
  provider are still unfinished.


- AppOpsManager, PermissionManager and SystemServerClassLoaderFactory now compile
  verbatim with 48 supporting Java sources, five generated AIDL types,
  print proto constants and both WebView flag declarations. Their service registrations
  use the stock constructors and context caches. Permission metadata comes from the
  installed parsed package definitions; attribution registration now verifies package/UID
  ownership and registered chains, with weak token retention and value matching.
  AppOps rejects unknown/spoofed package claims and uses the stock permission-to-op map.
  The narrowed Process patch retains its original ZYGOTE_PROCESS client field.
  WebView's supporting nested contracts and AudioManager's microphone query are copied
  verbatim into their existing adaptations; their outer classes remain hosted.
  NetworkUtilsInternal's three JNI methods validate borrowed sockets and preserve the
  absent-fwmarkd result; process-wide socket/DNS denial reports the missing enforcement
  hooks. Six original ZygoteCommandBuffer JNI functions supply parsing, modified UTF-8,
  reset, EOF and descriptor ownership. Its fork entry reports the absent hosted zygote;
  the unused original fork loop is discarded at link time. ART tests cover these
  boundaries, metadata copying/errors, attribution forgery rejection and class-loader
  caching/prefetch rules. JVM/system, native/Binder and build checks pass; all 11 APK
  tests pass (37 steps, zero structured runtime issues). AOSP is rebuilt and repinned;
  all seven new archive inputs and the Process patch are byte-verified. ART is unchanged.
  The inventory is 22 stubs (13 pending, 9 survivors). This is a client import checkpoint:
  broader AppOps accounting/restrictions, permission policy mutations, the remaining
  Zygote/ZygoteInit and WebView-loader JNI, and a real Chromium provider remain unfinished.


- NsdManager and NsdServiceInfo now compile verbatim with four supporting NSD
  API sources and four generated Binder interfaces. The host DNS-SD backend is
  moved from `android.net.nsd` to `dev.artzig`; HostedNsdService owns subscriptions,
  serialized callbacks and Binder-death cleanup. Stock NsdManager owns listener
  identity, executors, cancellation and stale-event handling. The Avahi parser
  now preserves decimal-escaped UTF-8 names, quoted/binary TXT values and independent
  IPv4/IPv6 records. ART tests cover those records, parcel round trips, validation,
  discovery/update/lost ordering, resolution cancellation, close failures, listener
  reuse, and client-death cleanup. The test APK declares INTERNET for the stock
  service's permission check. JVM/system, native/Binder and build checks pass;
  all 11 APK tests pass (39 steps, zero structured runtime issues). All ten source
  inputs already match the current AOSP archive; both archives are unchanged.
  Production discovery requires avahi-browse and its daemon. The existing backend
  has no advertiser or offload engine, and interface-scoped queries explicitly
  report the missing Avahi D-Bus connection. These capabilities are not claimed
  complete. The inventory remains 24 stubs (15 pending, 9 survivors); these clients
  were adaptations outside that inventory.


- Choreographer now compiles verbatim from AOSP; the hosted replacement and its
  `$artzig` frame hooks are removed. DisplayEventReceiver's four JNI methods use
  an owned display-event connection below the stock Java classes. Pipe readiness
  dispatches on the receiver's supplied MessageQueue, with coalesced requests,
  weak receiver ownership, disposal, and frame timing metadata. The existing
  host frame source emits events after window preparation; background Loopers
  have a paced event source. ART tests cover all five callback phases, reentrant
  and delayed callbacks, cancellation, shared frame identities, latest timing,
  callback exceptions, cross-thread queue selection and disposed queues. JVM,
  system, native/Binder and build checks pass; all 11 APK tests pass (39 steps,
  zero structured runtime issues). AOSP is rebuilt and repinned, and the new
  archive input is byte-verified; ART and the presentation/media backends are
  unchanged. Layer-specific display-event connections remain explicitly unsupported
  pending the stock SurfaceControl integration. The stub inventory remains 24
  (15 pending, 9 survivors), since Choreographer was an adaptation outside that audit.


- SaProposal's constants-only placeholder is replaced by its verbatim AOSP
  implementation and 203 supporting IKE/EAP, message, crypto and configuration
  sources. IkeMetricsInterface is generated by the original stats-log tool for
  module `ike`. The closure uses the actual ART boot classes to avoid importing
  SSLEngines a second time. ART tests cover proposal validation and deduplication,
  negotiated selection, independent result lists, IKE/Child bundle round trips,
  and the pinned AES-GCM vector with ciphertext/associated-data tamper rejection.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (39 steps,
  zero structured runtime issues). AOSP is rebuilt and repinned, with all 201
  new archive inputs byte-verified; ART is unchanged. Tunnel establishment and
  kernel IPsec provisioning are not supplied or verified by this batch. The
  regenerated inventory is 24 stubs (15 pending, 9 survivors).


- The legacy Camera placeholder is replaced by the verbatim AOSP class. Its 29
  native methods preserve absent-service connection failures, invalid-ID ordering,
  released-object errors and cleanup, with generated Binder service queries below
  the Java API. AOSP now supplies parameter encoding, validation, copying and public
  lifecycle logic. The audio service supplies the host shutter-sound resource/property
  policy. ART tests exercise parameter round trips, service attribution and queries,
  connection rejection, all 24 initialized-camera native operations, and repeated
  release. JVM/system, native/Binder and build checks pass; all 11 APK tests pass
  (39 steps, zero structured runtime issues). Capture/preview producers remain absent;
  a service returning a camera is disconnected and reported as unsupported rather than
  published as a usable device. Both archives are unchanged; the source was already
  packaged. The regenerated inventory is 25 stubs (15 pending, 10 survivors).


- NativeHandle and HidlMemory now compile verbatim with their original JNI,
  EphemeralStorage and libhidl value-type support. ART tests verify constructor
  array copying, borrowed versus owned descriptors, close-on-exec duplication,
  handle transfer, close/error behavior, native conversion and its cached context,
  and duplicate lifetime after the source closes. JVM/system, native/Binder and
  build checks pass; all 11 APK tests pass (39 steps, zero structured runtime
  issues). AOSP is rebuilt and repinned with all five new inputs byte-verified;
  ART is unchanged. This supplies HIDL memory values, without hardware Binder
  transport or a hardware service. The inventory remains 26 stubs (15 pending,
  11 survivors); broader client migration is still underway.


- MediaHTTPConnection and MediaHTTPService now compile verbatim with their generated
  Binder interfaces and the original HTTP JNI. The C++ Binder facade uses AOSP's
  IMemory, MemoryHeapBase, MemoryBase and MemoryDealer over the existing host ashmem
  implementation. ART tests cover cookies and headers, ranged reads and seeking,
  redirects and redirect policy, HTTP failures, EOF, the 32 KiB native buffer and
  descriptor lifetime after disconnect. A cross-process native test verifies shared
  allocation lifetime, Binder identity and remote writes. The SDK-header audit now
  distinguishes javac's unused constant-owner entries from executable references;
  its regression tests reject class literals, calls, inheritance and stack-map uses.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (38 steps,
  zero structured runtime issues). AOSP is rebuilt and repinned with all nine new
  inputs byte-verified; ART is unchanged. This enables the stock HTTP data source,
  without changing MediaPlayer, demuxing or decoder backends. The inventory remains
  26 stubs (15 pending, 11 survivors); the broader migration remains unfinished.


- MediaDrm and its five exception classes now compile verbatim. JNI follows the
  pinned no-factory path: supported-scheme enumeration is empty, per-scheme queries
  report MakeDrm's BAD_VALUE error, construction reports UnsupportedSchemeException
  with NO_INIT, and native operations reject an uninitialized object before validating
  session arguments. ART tests cover public queries/construction, malformed UUIDs,
  package validation, all 38 native instance-dependent operations and repeated failed-object
  cleanup. JVM/system, native/Binder and build checks pass; all 11 APK tests pass (37 steps,
  zero structured runtime issues). These sources were already archived, so both tarballs
  remain unchanged. No DRM plugin, key/session service or protected-content decryption
  is supplied. The stub inventory remains 26 (15 pending, 11 survivors); migration continues.


- MediaCodecInfo and MediaCodecList now compile verbatim, replacing both hosted
  adaptations. The existing eight-decoder catalogue, backend names and advertised
  limits moved to a native boundary; AOSP owns capability parsing, profile/level limits,
  feature checks, format selection and returned-array behavior. MediaProperties comes
  from its original sysprop declaration, and the remaining MediaCodec adaptation uses
  AOSP's verbatim GetFlag helpers and stock catalogue lookup. ART tests cover enumeration,
  decoder identity, audio/video format rejection, profile/level and secure-playback requests,
  array isolation and native argument failures. JVM/system, native/Binder and build checks
  pass; all 11 APK tests pass (37 steps, zero structured runtime issues). AOSP is rebuilt
  and repinned with all three added inputs byte-verified; ART is unchanged. The catalogue
  retains the previous adapter's declarations, without native hardware discovery or new
  decoder functionality. MediaCodec remains adapted, and the broader migration is unfinished.
  The stub-directory inventory remains 26 (15 pending, 11 survivors).


- CameraMetadataNative now uses the original camera metadata JNI, C storage library,
  CameraMetadata and VendorTagDescriptor implementations. Camera2 values support stock
  type validation, deep copies, merge/move/swap, characteristics and Parcel round trips,
  including descriptor-backed large payloads. A small native service adapter follows the
  original vendor descriptor/cache discovery over the shared Java/NDK Binder, retaining
  stock errors and cache selection without importing the native camera capture stack.
  ART tests exercise typed values, malformed input, ownership, 128 KiB parcels and an
  in-memory vendor service. JVM/system, native/Binder and build checks pass; all 11 APK
  tests pass (41 steps, zero structured runtime issues). AOSP is repackaged and repinned,
  with all ten added inputs and patched JNI byte-verified; ART is unchanged. Camera
  capture and ImageReader/ImageWriter buffer queues remain unwired. The inventory remains
  26 stubs (15 pending, 11 survivors), and the broader migration remains unfinished.


- The hosted C++ Parcel now reuses AOSP's blob lifecycle and wire-format implementation,
  backed by the original ashmem-host implementation shared with HWUI. It supports the
  16 KiB inline threshold, descriptor-backed immutable/mutable blobs, descriptor policy
  and mapping lifetimes. The shared Binder storage now preserves the actual descriptor
  for C++ borrowed and ownership-taking writes; duplicate writes and copied Parcel ranges
  retain their own descriptors. Native tests transfer 2 MiB blobs between processes,
  observe mutable data, forward immutable descriptors and verify release, copy, malformed
  input and descriptor lifetimes. JVM/system, native/Binder and build checks pass; all
  11 APK tests pass with zero structured runtime issues. This supplies the Parcel dependency
  needed by camera metadata JNI; that JNI is subsequent work. Archive inputs are unchanged.


- The hosted service registry now reuses the pinned AOSP service fetchers, including
  per-context construction gates, publication, retry and waiter interrupt restoration.
  Concurrent lookups no longer run duplicate manager constructors. Stock static service
  registration and context-free lookup APIs now preserve explicit opt-in and reject
  registration after class initialization. ART tests use stock WifiManager construction
  through real Context lookups to check concurrency, per-context identity, interrupt
  preservation and retry after a constructor exception. The two obsolete ViewRootImpl
  functor declarations are copied verbatim from AOSP, where both are explicitly no-ops;
  these remove linkage blockers for WebViewDelegate without changing presentation.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (36 steps,
  zero structured runtime issues). No archive inputs changed. The registry's service
  table and ViewRootImpl remain hosted adaptations, and the broader migration is unfinished.



- MediaStore now compiles verbatim with CloudMediaProviderContract, its parcelable
  requests/errors and three provider callback/cancellation AIDL interfaces. The former
  311-line adaptation is deleted. MediaProvider's AIDL uses the pinned SDK framework.aidl
  to resolve platform parcelables; its aconfig package comes from the original declaration.
  MediaPlayer is only a Javadoc import, covered by the existing compile-only header and
  runtime bytecode audit. ART tests exercise local and cloud-picker URI routing, read-only
  enforcement, callback delivery, parcelled errors, cancellation and provider lifetimes.
  The original-format descriptor API now honors the provider's returned descriptor instead
  of duplicating the input; tests verify distinct contents and retained input ownership.
  Existing thumbnail and image-decoding tests also pass. This is the stock client and its
  protocol, not a production media catalogue or cloud provider. JVM/system, native/Binder
  and build checks pass; all 11 APK tests pass (37 steps, zero structured runtime issues).
  AOSP is repackaged and repinned with all 14 added inputs byte-verified. The inventory
  remains 26 stubs (15 pending, 11 survivors): MediaStore was an adaptation outside the
  stub directory. The broader migration remains unfinished.


- HttpEngine's public Java API and its Cronet implementation now compile verbatim,
  with 52 original generated sources covering configuration, protobufs, JNI bindings,
  switches and version information. The original final JNI generator retains the
  test-suffixed methods required by the pinned Java and replaces provisional bindings.
  A separate compile against pinned SDK/stats headers feeds AOSP's class collector;
  its 634-class preload list retains upstream exclusions and the framework's unrelocated
  package names. Those compile-only headers and intermediate classes are not shipped.
  Original AndroidX experimental annotations are imported from the pinned AAR.
  ART tests cover builder validation, configuration snapshots, every preload class link,
  native-readiness rejection and Java command-line behavior. The existing MediaStore
  adaptation gained the verbatim legacy getBitmap helper needed by Cronet, with pixel,
  malformed-input and missing-file tests alongside its modern ImageDecoder path.
  JVM/system, native/Binder and build checks pass; all 11 APK tests pass (37 steps,
  zero structured runtime issues). The inventory remains 26 stubs (15 pending,
  11 survivors). AOSP is repackaged and repinned with all 205 added inputs byte-verified;
  ART's checksum is unchanged. This supplies the Java dependency for AppOps/ZygoteInit;
  it does not provide Cronet's native shared library or working HTTP requests.
  MediaProvider's pinned project is now synced for the subsequent complete MediaStore
  replacement; AppOps, WebView and the broader stock-client plan remain unfinished.


- VintfObject and VirtualDeviceManagerInternal now compile verbatim. The VINTF native
  boundary follows AOSP's missing-manifest/matrix path: compatibility returns NO_INIT,
  metadata queries return null or empty arrays, and the platform-policy query throws
  the stock missing-matrix error. It no longer falsely reports a compatible vendor build;
  no vendor metadata parser or HAL compatibility engine is implemented. The hosted
  virtual-device local service implements stock empty-device queries, immutable identity
  snapshots, duplicate listener registration and asynchronous removal delivery. Its Binder
  service now consumes stock CompanionDeviceManager association callbacks, updates display
  names and removes revoked or ineligible persistent identities. Tests exercise the stock
  CDM client with an in-memory association service, including callback ordering and repeated
  updates. Virtual display/input creation remains explicitly unsupported. JVM/system,
  native/Binder and build checks pass; all 11 APK tests pass (37 steps, zero structured
  runtime issues). The inventory is 26 stubs (15 pending, 11 survivors). The rebuilt AOSP
  archive is repinned and both added sources are byte-verified; ART remains unchanged.


- Cronet's request configuration, HTTP flags and base-feature overrides now use
  11 original generated protobuf classes and AOSP's 91-source protobuf lite runtime.
  The build uses the pinned protoc lite backend. Protobuf compiles separately against
  AOSP's original private Unsafe header, as required by its Android.bp; that header
  is excluded from the shipped jar, which continues to use ART's Unsafe implementation.
  The closure index now recognizes multiple-file protobuf messages and default outer
  class names, including collisions. Tests on ART cover proto2 presence, builder
  isolation, unknown-field preservation, oneof replacement, delimited streams, binary
  buffer ownership and malformed input. All 12 closure tests, JVM/system, native/Binder
  and build checks pass; all 11 APK tests pass (37 steps, zero structured runtime issues).
  The rebuilt AOSP archive is repinned and its three added schemas are byte-verified;
  ART's checksum is unchanged. The inventory remains 28 stubs (16 pending, 12 survivors).
  This completes the protobuf dependency for the AppOps/HttpEngine source closure;
  it does not yet select AppOps or implement Cronet's native networking backend.


- The AppOps/process-initialization closure now has the pinned Cronet generators
  for nine Java enums, ApiVersion, BuildConfig and NetError. The build uses the
  original enum parser, version script and C-preprocessor templates, with declared
  inputs and outputs and a fixed release copyright year. Generated classes are
  covered by the source audit; handwritten replacements are rejected. The closure
  tool now selects the stable Cronet module from the pinned Android.bp instead of
  mixing it with the optional tip-of-tree variant. All 12 generated classes are in
  the live framework jar. Build-tool tests, JVM/system, native/Binder and build checks
  pass; all 11 APK tests pass (39 steps, zero structured runtime issues). All 24 added
  archive inputs are byte-verified after rebuilding and repinning AOSP; ART's checksum
  remains unchanged. The inventory remains 28 stubs (16 pending, 12 survivors).
  AppOps itself is still adapted: protobuf messages, JNI bindings, HttpEngine preload
  generation and the WebView dependency closure are subsequent work. This batch adds
  no native HTTP engine or WebView provider and changes none of the excluded backends.


- VirtualDeviceManager, VirtualDeviceParams, their Binder protocol and CameraManager
  now compile verbatim with the stock virtual input/audio/sensor/camera and camera2
  dependency closure. Context exposes both managers. The hosted virtual-device service
  validates permissions, identity and companion associations, owns listener lifetimes,
  and reports stock defaults when no virtual devices exist. CameraManager follows the
  stock absent-camera-service path: empty camera lists and CAMERA_DISCONNECTED for
  unavailable characteristics. VirtualDeviceParams now has stock validation, parceling
  and policy constants (clipboard policy is 4; the former placeholder incorrectly used 3).
  This does not implement virtual device creation, camera capture, ImageReader/ImageWriter
  buffer queues or RenderScript natives; those imported dependency paths still need native
  support. AudioTrack's required array overloads preserve AOSP argument/state errors and
  zero-length results; nonempty array submission reports structured unsupported behavior
  because the existing backend lacks that contract. No Wayland, PipeWire or FFmpeg backend
  changes were made. The AIDL closure tool now follows generic structured parcelables,
  with a dependency regression test. All 10 closure tests, JVM/system, native/Binder and
  build checks pass; all 11 APK tests pass (39 steps, zero structured runtime issues).
  The desktop drawer screenshot was inspected. The inventory is 28 stubs: 16 pending
  and 12 survivors. The rebuilt AOSP archive is repinned, with all 85 new inputs
  byte-verified; ART remains unchanged. The broader stock-client plan remains unfinished.

- MediaRouter2 now compiles verbatim with MediaRouter2Manager, the legacy MediaRouter,
  MediaRoute2ProviderService and their route/session models. The hosted Binder service
  exposes the existing desktop speaker as one fixed-volume system route. It owns
  registrations and Binder death, forwards discovery/listing preferences and device
  suggestions, validates package identity and privileged scanning/control requests,
  and preserves the persistent system session. The stock clients provide feature and
  provider filtering, executor callbacks and controller lifecycle behavior. Legacy
  routing now initializes through Context services and the stock unavailable-Wi-Fi-display
  status. AudioManager's public policy entry points follow pinned AOSP control flow;
  AudioManager itself remains adapted. There is no remote route-provider discovery,
  output-picker UI or framework master-volume implementation in this batch, and no
  Wayland, PipeWire or FFmpeg backend changes. Public-client and Binder tests cover
  filtering, suggestions, callback dispatch, duplicate registration, invalid requests,
  denied permissions and the persistent fixed-volume output. JVM/system, native/Binder
  and build checks pass; all 11 APK tests pass (39 steps, zero structured runtime issues).
  The inventory is 32 stubs: 19 pending and 13 survivors. The rebuilt AOSP archive is
  repinned, with all 21 new Java/AIDL inputs byte-verified; ART's checksum is unchanged.


- Spatializer and LoudnessCodecController now compile verbatim with their stock
  callback helpers. Hosted audio Binder policy follows AOSP's absent-spatializer
  behavior, including permissions, listener registration and effect-parameter errors.
  Loudness sessions track codecs and send the stock initial update; parameters remain
  empty because no hosted AudioPolicy device loudness model is available. This adds
  no spatial rendering or decoder loudness processing. The existing MediaCodec adapter
  now keeps independent compressed-input and decoded-output format snapshots, including
  buffer ownership and rejection of format queries before configuration or after stop.
  Stock loudness code can consequently identify and register AAC codecs instead of
  seeing the decoded PCM format. Module tests cover listener errors, codec registration,
  duplicate/rejected operations, deferred callbacks, session restart and format snapshots.
  JVM/system, native/Binder and build checks pass. All 11 APK tests pass (39 steps,
  zero structured runtime issues). The inventory is 33 stubs: 20 pending and 13 survivors.
  Three Java files were added to the rebuilt, repinned AOSP archive; all four newly
  selected sources are byte-verified. ART is unchanged. AudioManager and MediaCodec
  remain adaptations, and the existing PipeWire/FFmpeg backends are unchanged.

- ActivityThread now compiles verbatim. The 1,757-line adaptation and hosted
  IApplicationThread implementation are deleted. Bootstrap, package announcement,
  teardown and desktop presentation live in dev.artzig; the stock thread owns
  application binding, providers, services and activity transactions. Window
  membership and stop state drive presentation through the existing backend.
  The host registers the two ActivityThread JNI operations below the Java client.
  This completes the Activity/ActivityThread/Instrumentation/Dialog source swap;
  ViewRootImpl and the remaining service-backed client stacks are still pending.
  Stock application binding now applies target-SDK StrictMode and app network
  security policy. A private system-root layout exposes the selected Linux CA
  bundle to stock SystemCertificateSource, retaining application trust-anchor
  selection, certificate removal and chain verification. Hosted services now
  track provider connection references/death, filter size changes through reported
  resource qualifiers and ignore late stop acknowledgements after resume.
  JVM and cross-process system suites pass, including configuration overrides,
  coalesced relaunches, saved-state ordering, ActivityGroup and retained fragments.
  All 11 APK tests pass (38 steps, zero structured runtime issues); phone and desktop
  drawer screenshots were inspected. Native/Binder and build audits pass. The audit
  remains 35 stubs (22 pending, 13 survivors): ActivityThread was outside that count.
  Both tarball checksums still match the previous verified milestone, and the
  packaged ActivityThread is byte-identical to the pinned checkout. No archive
  inputs changed and no Wayland, PipeWire or FFmpeg backend work was added.

- Application attachment now waits for the stock finishAttachApplication handshake
  before releasing activity/service work. The service checks caller identity and start
  sequence, rejects mismatched acknowledgements and prevents duplicate work replay.
  The module regression exercises queued launches, rejection and one-shot completion;
  JVM/system and all 11 APK tests pass (36 steps, zero structured runtime issues).
  This is a prerequisite for selecting stock ActivityThread, not that selection itself.
  Neither tarball changes.

- Preserved-window rebuilds now update the root's resource/layout-direction cache,
  resume a stopped root and report its next submitted frame. ViewTreeObserver frame
  callbacks are captured before drawing, retained per root and delivered on its UI
  Handler only when that root is included in the desktop composite. Callbacks from
  an unsubmitted root stay pending and are canceled on root destruction. The hosted
  window session follows AOSP's no-WindowState completion behavior; BLAST transaction
  synchronization and SurfaceHolder/InputQueue takeovers remain unavailable.
  The Cage probe verifies callback thread/order, registration during onDraw, excluded
  roots, stopped-root rebuilding and the final blue frame. JVM/system suites,
  native/Binder/build checks and all 11 APK tests pass (37 steps, zero runtime issues).
  Neither tarball needs rebuilding. Stock ActivityThread now passes the standalone
  Java compile probe; selecting it still requires its JNI and hosted-caller migration.

- SplashScreenView, SurfaceControlViewHost and WindowlessWindowManager now compile
  verbatim. The former empty splash placeholder now uses stock material building,
  icon/branding bitmap Parcel transport, copy policy and removal/resource cleanup.
  The surface-host clients retain their stock API/protocol; embedded-surface creation,
  input and BLAST draw reporting remain unavailable and fail explicitly at the
  hosted root/surface boundary. This does not add animated
  cross-process splash surfaces. The two removed stubs were survivors, so the budget
  stays 22. The regenerated inventory is 35 stubs: 22 pending and 13 survivors.
  AOSP is rebuilt, repinned and all three source inputs are byte-verified; ART is
  unchanged. JVM/system, native/Binder/build checks and all 11 APK tests pass
  (36 captured steps, zero structured runtime issues).

- ThreadedRenderer now compiles verbatim. The existing desktop frame adapter lives
  in dev.artzig.HostedRenderer, and stock HardwareRenderer snapshots draw through
  HardwareBufferRenderer below JNI instead of returning transparent bitmaps.
  EGL/Ganesh context ownership follows RenderThread lifetime so temporary renderers
  cannot invalidate surviving hardware bitmaps or the desktop renderer. Offscreen
  rendering supports surfaceless EGL and waits for completion when native fences
  cannot be exported. Pixel tests cover clipping, repeat snapshots and RenderNode
  ownership; the Cage probe also verifies a new desktop frame after snapshot
  renderer teardown. JVM/system, native/Binder/build checks and all 11 APK tests pass
  (37 captured steps, zero structured runtime issues). The AOSP tarball is rebuilt,
  repinned and byte-verified; ART remains current. The stub inventory remains
  37 (22 pending, 15 survivors); this removes an adaptation outside that inventory.

- Stock ConnectivityManager and NetworkAgent replace their survivor stubs, with
  55 original Java sources, generated request protocols and FrameworkConnectivityStatsLog.
  A hosted Binder service observes Linux routes, addresses and DNS configuration,
  and owns request matching, callbacks, caller identity, release and timeouts.
  Untracked radio activity follows AOSP's always-active behavior independently of
  route availability. Original BPF JNI reports Linux errno; Android per-process
  routing domains, reservations and radio agents remain unavailable.
  All 126 selected inputs match the rebuilt, repinned AOSP archive; ART is unchanged.
  JVM/system tests and native/Binder/build checks pass. All 11 APK tests pass
  (41 captured steps, zero structured runtime issues).
  The inventory is 37 stubs: 22 pending and 15 survivors; the pending budget stays 22.

- ActivityThread's mainline closure now selects 120 original Java files
  for NFC/secure elements, SDK sandbox, profiling, media communication/transcoding
  and process support, with 45 additional AIDL inputs. Versioned AIDL uses the
  frozen OMAPI V1 schema, version and hash selected by the pinned framework build.
  MediaCommunicationManager replaces its stub; the redundant Session2Token AIDL
  placeholder is removed. Client/value contracts and generated protocols do not
  supply NFC hardware, a sandbox process, a profiler or a transcoding daemon.
  All 206 inputs match the rebuilt, repinned AOSP archive; ART is unchanged.
  JVM/system tests pass, including NDEF parsing and rejection, profiling validation,
  SDK result transport, media capability declarations and frozen AIDL metadata.
  The inventory is 39 stubs (22 pending, 17 survivors). Native/Binder/build checks
  and all 11 APK tests pass (41 steps without runtime issues).

- Activity now compiles verbatim and the 1,161-line hosted Activity replacement
  is deleted. ActivityThread uses stock lifecycle signatures, Instrumentation
  creation/post-create dispatch and super-call checks, outer-context attachment,
  and fresh persistent-state saving. Direct-object test helpers live in dev.artzig;
  the demo enters through ActivityThread. Window diagnostics describe the hosted
  root and frame queue, and the native linker-warning boundary preserves the
  no-deferred-warning result. Kotlin receives explicit aconfig output roots to
  resolve inherited flag annotations without modifying AOSP source. ActivityThread
  itself remains adapted. A registered hosted permission controller now returns
  the administrator-managed grant state through stock activity-result transactions,
  authenticating the caller's package, process and result token. This preserves the
  existing permission policy; it does not add an interactive grant dialog. Tests
  cover launch-time result deferral, fragment routing, denied and granted results,
  forged caller rejection and persistent-state lifecycle behavior. Activity and its
  JNI note add no new AOSP inputs, so both tarballs remain current. The task service
  also retains dispatched activities when another start beats their resume
  acknowledgement, with a deterministic regression for that race. JVM/system
  suites, native/Binder/build checks and all 11 APK tests pass (39 steps without
  runtime issues).

- VisibleActivityInfo and its voice-session/sound-trigger client closure now
  compile verbatim, with 40 additional generated AIDL inputs and two original
  flag packages. Activity identity now has the stock task/token type and Parcel
  validation. Module tests cover identity equality, rejected null tokens,
  keyphrase matching, result transport and validation, and failure recovery hints.
  The inventory is 40 stubs (22 pending, 18 survivors). All 98 selected inputs
  match the current AOSP archive; neither tarball needs repackaging. This imports
  client types and protocols; no assistant, recognition engine or sound-trigger
  hardware service is installed. JVM/system suites, native/Binder/build checks
  and all 11 APK tests pass (41 steps without runtime issues).

- The audio core foundation now compiles stock AudioFormat, AudioAttributes,
  AudioDeviceInfo, PlayerBase, AudioRecord, MediaRecorder, AudioEffect and their
  policy/value closure. The generated IAudioService protocol is available. JNI
  exposes the existing logical PCM output, pinned audio limits and enum mappings,
  and stock absent-service initialization failures for recording and effects.
  AudioManager and AudioTrack remain adaptations; policy operations without a
  hosted service report structured unsupported diagnostics. No new PipeWire or
  FFmpeg backend is introduced. Module tests cover format validation, Parcel
  behavior, device ports, enum conversion, retained playback defaults and absent
  capture/effect/camera paths. The inventory is 41 stubs (23 pending, 18 survivors).
  JVM/system suites, native/Binder/build checks and all 11 APK tests pass
  (37 steps without runtime issues). The AOSP tarball is rebuilt and pinned with
  PlayerBase and the original audio headers; ART is unchanged.

- The audio transport foundation now generates 66 pinned AIDL inputs from
  frameworks/av and the shared audio HAL schema. AudioHalVersionInfo,
  AudioRoutesInfo and AudioPresentation compile verbatim. Module tests cover
  nested configuration and address unions, invalid Parcel tags and sizes,
  presentation metadata and validation, and ordered player commands through the
  generated proxy/Stub protocol. JVM/system suites, native/Binder/build checks
  and all 11 APK tests pass (37 steps without runtime issues). All 69 selected
  source files already match the pinned AOSP archive; both tarballs remain
  current without repackaging. The inventory remains 44 stubs (26 pending,
  18 survivors). This supplies transport and metadata, not an AudioService or
  new playback, recording or HAL backend.

- UsbManager, UsbDeviceConnection, UsbRequest and the port/accessory closure now
  compile verbatim, with generated USB Binder interfaces, HAL enums, HIDL constants
  and release flags. The original connection/request JNI and usbhost JNI helper
  use the existing AOSP Linux USB library. The stock registry preserves missing-
  service behavior. Module tests exercise proxy/Stub device transport, descriptor
  duplication and reads through JNI, rejected ioctls on a regular file, closed
  connections, request lifecycle, port role bits and Parcel values. JVM/system,
  native/Binder/build checks and all 11 APK tests pass (39 steps without runtime
  issues), including the launcher fullscreen fix below. All 35 new manifest inputs
  match pinned AOSP; AOSP is rebuilt and pinned, ART is unchanged. The inventory
  is 44 stubs: 26 pending and 18 survivors. No USB discovery/permission service is
  hosted yet, and this container has no USB device nodes; the descriptor fixture
  does not claim a physical USB transfer.

- The launcher now synchronizes fullscreen requests after input/Handler work even
  when it submits no Java frame. A window-state change after the last rendered
  frame previously left the compositor unchanged, causing intermittent desktop
  video fullscreen timeouts. The desktop regression now explicitly pauses playback
  before entering fullscreen. The focused test and all 11 APK tests pass (39 steps
  without runtime issues); temporary state tracing is removed. This changes the
  launcher boundary, not the existing Wayland backend.

- TextToSpeech, TtsEngines, UtteranceProgressListener and Voice now compile
  verbatim, with five generated Binder interfaces and the stock TTS manager,
  per-user session service and shared server infrastructure. SystemServices
  starts the AOSP manager. Virtual-device playback uses the pinned absent-service
  session value; VirtualDevice itself is a Javadoc-only SDK header and is barred
  from runtime references. A separate-process protocol fixture verifies engine
  discovery and binding, missing-engine failure, voice transport, ordered progress
  and error callbacks, rate/pitch parameter merging, file-descriptor ownership and
  shutdown. JVM/system suites, native/Binder/build checks and all 11 APK tests pass
  (37 steps without runtime issues). The inventory is 45 stubs: 26 pending and
  19 survivors. All six new archive inputs match pinned AOSP; AOSP is rebuilt and
  pinned, and ART is unchanged. This supplies the client/session protocol, not a
  synthesizer: TextToSpeechService still needs the media playback closure, and no
  production speech engine is installed.

- NetworkPolicyManager and NetworkPolicy now compile verbatim, with their
  generated Binder interfaces and platform flags. The hosted policy service
  exposes the default unrestricted state, preserves permission and UID validation
  ordering, and rejects changes that need Android firewall or carrier enforcement.
  Applications obtain a cached stock manager through SystemServiceRegistry.
  Module tests cover policy thresholds, snoozes, Parcel round trips, empty policy
  state and rejected mutations; separate-process tests cover service lookup and
  denied privileged queries, mutations and factory reset. The inventory is
  46 stubs: 27 pending and 19 survivors. The six new archive inputs match pinned
  AOSP; the AOSP tarball is rebuilt and pinned, and ART is unchanged.
  JVM/system suites, native/Binder/build checks and the final full APK suite pass.
  Two earlier desktop fullscreen runs timed out without runtime issues; three
  traced retries and the final suite passed. The timeout is not claimed fixed;
  temporary tracing was removed and no presentation code changed in this batch.

- TrafficStats, NetworkStatsManager and the statistics/template/history closure
  now compile verbatim, with generated statistics Binder interfaces and proto
  constants. A hosted netstats service supplies Linux interface/total byte and
  packet counters; missing interfaces and unavailable UID accounting follow the
  stock UNSUPPORTED result. Activity startup initializes the stock client before
  application code. The stock socket tagger replaces HostedTrafficSocketTagger;
  its JNI boundary preserves descriptor errors and the absent-netd result while
  retaining the explicit missing-accounting diagnostic. Historical queries and
  provider/UID accounting remain unsupported backend work. Module tests cover
  snapshots, overflow, unavailable counters, Parcel transport, thread tag/UID
  isolation and JNI errors; a separate-process test observes a real 4 KiB UDP
  transfer through Binder. JVM/system suites, NewPipe launch, native/Binder/build
  checks and all 11 APK tests pass (38 steps without runtime issues). All 18 newly
  selected Java sources match the existing archive; both tarballs remain current
  and unchanged. The inventory is 47 stubs: 27 pending and 20 survivors.

- AppOps prerequisites now include verbatim PermissionGroupUsage and generated
  AppOpEnums and SettingsLib flags. The latter uses the pinned bp2a read-only,
  enabled WRITE_SYSTEM_PREFERENCE permission value. Parcel tests preserve UID,
  timestamps, permission/device identity, state and optional attribution labels;
  the release flag is checked as well. JVM tests and build/C++/graph audits pass.
  All four inputs match pinned AOSP. The AOSP archive is rebuilt and pinned;
  ART remains current and unchanged. This is dependency preparation: AppOpsManager
  itself remains pending, and the inventory stays at 48 stubs.

- Activity.finishAffinity now uses the pinned ActivityClient call and result/
  embedded-activity guards. The hosted controller closes only the contiguous
  matching-affinity range at and below the caller, preserving a different
  affinity boundary and activities above it; equal null affinities also match.
  Group finishing queues canceled results for surviving recipients before their
  next resume. Module and separate-process tests cover covered and foreground
  callers, task preservation, null affinities, rejected results and exactly-once
  result ordering. JVM/system suites, the NewPipe launch regression, native,
  Binder and build checks, and all 11 APK tests pass (37 steps without runtime
  issues). Both tarballs are unchanged and current; the inventory stays at 48.

- Activity now inherits the verbatim ContextThemeWrapper, replacing its private
  theme/inflater implementation and empty applyOverrideConfiguration method.
  Stock resource isolation, one-time override checks, layered theme identity,
  explicit theme replacement and inflater context now apply to activities.
  ActivityThread combines the app override with server configuration before
  resource updates and callbacks, including the host configuration entry point;
  caller-owned configurations remain unchanged. Module tests cover themes and
  resources, both configuration paths and null initial server overrides. JVM
  and separate-process system suites, the NewPipe launch regression, native,
  Binder and build checks, and all 11 APK tests pass (37 steps without runtime
  issues). Both tarballs
  remain current and unchanged; the inventory stays at 48 stubs. Activity and
  ActivityThread still require the remaining stock activity/service closure.

- PerformanceHintManager and WorkDuration now compile verbatim from pinned AOSP.
  The eleven-method native adapter follows APerformanceHintManager::create's
  unavailable-service path: no Android hint manager, Power HAL or FMQ is hosted,
  so Java throws ServiceNotFoundException and no manager/session handles are
  issued. Invalid hidden handles fail explicitly instead of accepting fake hints.
  The service registry uses the stock registration and app-versus-system logging
  policy for missing services. Tests cover acquisition failure, argument checks,
  work-duration values/equality, rejected setter state and closed sessions.
  JVM/system suites, the NewPipe launch regression, native/Binder/build checks
  and all 11 APK tests pass (39 steps without runtime issues). The AOSP archive is rebuilt
  and pinned; the ART archive is unchanged and current. The inventory is now
  48 stubs: 27 pending and 21 survivors. This does not provide performance or
  power management, and the broader activity/service migration remains open.

- Activity attachment now captures the pinned launch component and UI thread.
  getComponentName/getLocalClassName use that component, including aliases,
  instead of a hard-coded package and runtime class; setIntent does not change
  activity identity. runOnUiThread uses AOSP's immediate-on-UI/post-from-worker
  behavior and final signature. Direct hosted attachment supplies its missing
  resolved component before calling the standard entry point. Relaunch now
  follows ActivityThread.performLaunchActivity: attach with the original launch
  intent, then restore the application's custom intent. This preserves both
  identity and getIntent even when the custom intent has no component.
  Module and separate-process tests cover alias/name boundaries, dispatch
  threading/order and recreation with a custom intent. JVM and system suites,
  the NewPipe launch regression, native/Binder/build checks and all 11 APK tests
  pass (37 steps without runtime issues). Both tarballs remain unchanged and
  current; the stub inventory stays at 49.

- The hosted activity controller now follows ActivityStarter/Task task reuse:
  component and document matches precede affinity candidates; repeated launcher
  intents preserve the existing top screen; changed intent filters add an
  activity; MULTIPLE_TASK creates a distinct task. SINGLE_TOP, CLEAR_TOP,
  CLEAR_TASK, REORDER_TO_FRONT and document policies select creation versus
  new-intent delivery while preserving task identity. Pending canceled results
  precede new intents and resume, and removed activities are destroyed after
  the replacement resumes. Explicit NEW_TASK result requests are canceled
  immediately. Activity.getTaskId, isTaskRoot and moveTaskToBack now use the
  stock ActivityClient transport instead of placeholder values. The task URI
  fixture supplies the real hosted package-manager dependency for document
  intents. Module and separate-process regressions pass, covering identity,
  alias normalization, affinity precedence, task/root/back queries, new-intent
  ordering, clearing and canceled results. The NewPipe launch regression and
  all 11 APK tests pass (37 steps without runtime issues), along with native,
  Binder and build checks. Both tarballs remain unchanged and current, and the
  inventory stays at 49 stubs. This closes common task reuse; it does not replace
  Activity/ActivityThread wholesale or implement the full WM task policy.

- Image and PublicFormatUtils now use verbatim AOSP Java, with the original
  format JNI and libui PublicFormat.cpp. Image's former compile-only SDK header
  is removed; ImageWriter remains Javadoc-only and barred from runtime use.
  Native public/HAL conversions now preserve JPEG, HEIC, depth and raw format
  distinctions instead of returning identity formats and unknown dataspaces.
  The Dataspace header comes from the pinned VINTF-stable AIDL's NDK backend.
  Tests cover crop clipping/copies, dataspace, closed-image behavior, fence
  defaults, format round trips and unknown-format fallbacks. JVM, system,
  native, Binder and build checks pass, together with all 11 APK tests
  (37 steps without runtime issues). This supplies the abstract Image base
  contract, not ImageReader/ImageWriter producers or codecs. All selected
  sources were already packaged and match pinned AOSP; both tarballs remain
  current and the inventory stays at 49 stubs.

- Activity component callback registration now follows the pinned compatibility
  rule and uses stock ComponentCallbacksController. Modern applications own
  callbacks on the activity, including registration before attachment; older
  applications retain application-level delivery. Configuration callbacks
  follow activity lifecycle notifications, and low/trim-memory callbacks follow
  fragment dispatch. Empty registration methods are removed. Regressions cover
  both compatibility modes, ownership, ordering, memory callbacks and removal.
  JVM, system, native, Binder and build checks pass, with all 11 APK tests
  (37 steps without runtime issues). The inventory stays at 49 stubs and both
  tarballs are unchanged and current.

- Process configuration dispatch now uses verbatim AOSP ConfigurationController
  and ActivityThreadInternal. The controller owns pending configurations,
  resource updates, locale selection, density, theme rebasing and component
  callbacks. The hosted thread supplies the application/service/provider list,
  deduplicating providers by identity, and retains its existing activity resize
  and recreation boundary. Activity now declares its stock ComponentCallbacks2
  interface. Regressions cover resource-before-callback ordering, service
  delivery, equivalent-update suppression and pending updates; existing stale
  sequence, handled/unhandled configuration and saved-state/lifecycle checks
  also pass. Lifecycle fixtures now supply real configuration changes instead
  of expecting callbacks from a change mask alone. JVM, separate-process,
  native, Binder and build checks pass, as do all 11 APK tests (38 steps with
  no runtime issues). Both sources were already packaged and match pinned AOSP;
  both tarballs remain current. The stub inventory remains 49.

- MediaCrypto now uses verbatim AOSP Java. Its seven-method Zig JNI adapter
  follows JCrypto's no-plugin path because the host has no Android AIDL/HIDL
  DRM factories: capability queries return false and construction throws the
  pinned NO_INIT MediaCryptoException. UUID validation, null-argument failures,
  released-object errors and failed-constructor cleanup retain the stock
  contract. This provides no decryption implementation and changes none of the
  excluded media backends. Java, JVM, system, native, Binder and build checks
  pass, together with all 11 APK tests (39 steps without runtime issues).
  The AOSP archive is rebuilt and pinned; the ART archive remains unchanged
  and current. The inventory is 49 stubs: 27 pending and 22 survivors,
  with 42 outside com.android.server (20 pending, 22 survivors).

- Finishing an activity now queues its result on the recipient independently
  of the activity that becomes visible. A covered recipient keeps its results
  until its own next resume, following ActivityRecord.finishActivityResults;
  the controller reveals the actual top activity instead of trying to resume
  the covered recipient and dropping the result. The cross-process regression
  finishes two children while their recipient remains covered and verifies
  exactly-once, ordered callbacks before onResume. JVM and system tests pass,
  as do the launch regression, native/Binder/build checks and all 11 APK tests
  (37 steps without runtime issues). Both tarballs remain current and unchanged;
  the inventory stays at 50 stubs. Task reuse is covered by the later batch above.

- Activity launch/calling identity queries now use the pinned ActivityClient
  transport. The controller records each delivery's share-identity choice and
  applies the stock compatibility, same-UID, system-UID and platform-signature
  checks to Activity and ComponentCaller getters. New intents retain their own
  sharing decisions; result callers cannot opt into sharing. Calling activity
  and package are exposed only for starts that requested a result. Parsed
  package metadata now carries the platform-signature flag following
  ScanPackageUtils, including packages installed before the platform.
  Two new fixture apps use a separate public test key so cross-process tests
  exercise restricted callers, alongside the platform-signed fixture exception.
  Tests cover hidden/shared identity, same-UID access, new intents, recreation,
  result callers and removed activity records. Java, JVM and system tests pass,
  along with native, Binder, build checks and all 11 APK tests (37 steps
  without runtime issues). The inventory stays at 50 stubs; both tarballs are
  unchanged and current. The later batches above
  cover task reuse and deferred results for covered recipients.

- GLSurfaceView now uses verbatim AOSP Java, including GLThread, EGL helpers,
  debug wrappers, GLU and Matrix. Legacy EGLImpl and its Java handles/interfaces
  are also stock. Its 28-method Zig JNI adapter follows the pinned native
  contract over the existing EGL backend, including EGL 1.0 version reporting,
  handle/configuration arrays, errors and unsupported pixmap behavior. Display
  initialization is reference-counted across legacy EGL and EGL14 callers,
  preserving resources until the last owner terminates the display.
  Tests cover concurrent owners, pbuffer pixels, renderer callback ordering,
  queued work, dirty-mode redraws, pause/resume with and without preserved
  contexts, surface replacement, and render-thread cleanup. The GLSurfaceView
  test uses its public EGLWindowSurfaceFactory to create real pbuffers;
  default native-window presentation still needs an excluded host compositor
  adapter. Java, JVM, separate-process system, native, Binder and build checks
  pass, as do all 11 APK tests (37 steps without runtime issues). All 20 new
  Java sources were already packaged and match AOSP byte for byte; both
  tarballs remain current. The inventory falls to 50 stubs: 27 pending and
  23 survivors, with 43 outside com.android.server (20 pending, 23 survivors).

- The pinned GLImpl and four remaining Khronos GLES1 interfaces now compile
  directly with AOSP's GLImpl JNI. Native patch 32 retains the original JNI
  array bases for positioned heap buffers, guards failed acquisitions, and
  converts existing typed-buffer bounds checks from bytes to elements. The
  Zig native ABI supplies the Android Bounds exports and resolves GLES
  extensions through GLVND; core GLES1 calls use its GLESv1_CM library.
  Regression coverage exercises fixed-function pixels, direct and heap buffer
  positions/slices, texture and viewport bounds, OES framebuffer pixels, and
  OES matrix queries with independently positioned outputs. Java, JVM,
  separate-process system, native, Binder and build checks pass, along with
  all 11 APK tests (38 steps without runtime issues). The first APK run hit a
  transient NewPipe live-feed parse failure with no framework issues; the
  complete rerun passed. The Androlinzberg AOSP
  archive was rebuilt and its content hash pinned; the ART archive is
  unchanged and current. The inventory remains 51 stubs: this closes GLImpl's
  native prerequisite, while stock GLSurfaceView still needs legacy EGLImpl.

- EGL15, EGLExt and EGLImage now use pinned AOSP Java, with the original EGL15
  JNI. EGLExt's native adapter follows the pinned JNI handle/exception contract
  and resolves Android extensions through Mesa/GLVND; the original JNI requires
  unavailable Android GLConsumer definitions and libEGL extension exports.
  Tests cover real sync waits, output-array offsets, error returns, texture-image
  lifetime, and an exported GPU sync_file that survives destruction of its
  EGLSync and original SyncFence owners. Java, JVM, separate-process system,
  native, Binder and build checks pass, along with all 11 APK integration tests
  (38 steps without runtime issues). The stock sources were already packaged;
  both tarballs remain current and the inventory stays at 51 stubs. Native-window
  presentation remains at the existing backend boundary.

- The closure audit now resolves wildcard imports and treats legacy Khronos
  APIs as framework sources instead of assuming they are in libcore. Static
  wildcard imports retain their declaring class. GLSurfaceView's live closure
  now includes the EGLImpl/GLImpl Java dependencies and reports their JNI
  blockers. The refreshed inventory remains 51 stubs; build-tool regressions
  pass. This corrects migration accounting without changing runtime behavior.

- SyncFence and its JNI now use verbatim pinned AOSP sources, backed by the
  stock native Fence/libsync implementation. The stock EGLSync handle is also
  selected. Two Javadoc-only camera imports use exact headers from the pinned
  SDK; a mandatory bytecode audit rejects packaging those headers or any runtime
  reference to them. No camera implementation enters the runtime. Tests exercise
  invalid/empty fences, descriptor transfer and duplication, shared copies,
  close idempotence, poll readiness, and Parcel descriptor lifetime through JNI.
  Java, JVM, separate-process system, native, Binder and build checks pass, along
  with all 11 APK integration tests (38 steps without runtime issues) and the
  hardware-buffer Canvas/pixel-readback probe. The rebuilt AOSP tarball includes
  the SDK header source archive and notice; the ART tarball is unchanged.

- ComponentCaller now checks immutable delivery-time URI access snapshots for
  launches, new intents, and activity results. The hosted controller follows
  stock ActivityCallerState, including nested ClipData/EXTRA_STREAM, separate
  read/write access, denied versus absent URIs, and the receiver's current-access
  requirement. ActivityThread retains the initial caller token through launch
  and recreation. Starting a child before the parent's resume acknowledgement
  now preserves the parent and its result delivery. JVM and separate-process
  regressions pass, along with all 11 APK integration tests (38 steps without
  runtime issues). The inventory remains 52 stubs; package inputs are unchanged
  and both tarballs remain current. Task reuse and
  identity-sharing gates are covered by the later batches above.

- PerfettoTrace and PerfettoTrackEventExtra now use their verbatim pinned AOSP
  sources, backed by the original JNI, libtracing_perfetto, and Linux Perfetto C
  SDK. Zig builds the SDK and its three protoc plugins; all 648 generated native
  message files match the original GN/Ninja output. A small native adapter fixes
  the pinned register method's FastNative calling convention. OS flags use the
  stock generator's read-only default permissions, preserving all 50 constants
  and their defaults while avoiding a DeviceConfig lookup before SystemServer
  publishes the activity service. Runtime DeviceConfig overrides for this flag
  package are consequently disabled. The in-process trace regression decodes
  typed arguments, slices, counters, flow IDs, and category filtering. JVM,
  system, native, Binder and build checks pass, along with all 11 APK integration
  tests (38 steps without runtime issues). The rebuilt AOSP tarball includes the
  native SDK and generators. The inventory is 52 stubs: 28 pending, 24 survivors.
  No system tracing daemon is added; the SDK's system IPC backend remains
  dependent on an external Perfetto service.

- Activity launch, new-intent and result URI permissions now flow through the
  stock `UriGrantsManagerService`. Grants are checked before activity state
  changes and applied before client delivery. Each activity record has a stock
  `UriPermissionOwner`; recreation preserves it, while final removal, canceled
  launch cleanup and process-record removal release it. Persisted grants keep
  their independent lifetime. `Activity.finishAndRemoveTask` now dispatches the
  pinned root-task finish mode through the shared finish path, including its
  stock autofill finish callback. Separate-process tests cover data and nested
  ClipData, unauthorized grants, read/write isolation, recreation, new intents,
  shared owners, results, persistence, and root versus non-root task removal.
  JVM, system, native, Binder and build checks pass, along with all 11 APK
  integration tests (38 steps without runtime issues). The inventory remains
  53 stubs at that checkpoint. Delivery-time caller snapshots are now implemented
  as described above.

- R8 keep annotations now come from AOSP's pinned `keepanno-annotations` Java
  import. The three handwritten KeepTarget, TypePattern and UsesReflection
  stubs are deleted; all 22 original annotation classes are included and match
  the pinned artifact byte for byte. The compiler itself stays outside the
  framework classpath. The rebuilt AOSP package includes the jar and its license.
  Java, JVM, system, native, Binder and build checks pass, along with all 11
  integration tests (38 steps without runtime issues). The inventory is now
  53 stubs: 28 pending and 25 survivors.

- EmulatorClipboardMonitor now uses its pinned AOSP source. ClipboardService
  already selects it only for an emulator, and hosted SystemServer verifies
  `Build.IS_EMULATOR` is false. The replacement therefore needs no QEMU/vsock
  endpoint or desktop clipboard integration. Existing cross-process clipboard
  reads, writes and listeners pass, along with JVM, system, native, Binder and
  build checks and all 11 integration tests (38 steps without runtime issues).
  The inventory is 56 stubs: 28 pending and 28 survivors.

- HWUI now uses stock native libui Fence and Linux libsync, replacing the
  hostgraphics fence placeholder. Every native translation unit sees the same
  stock layout. Tests cover timeouts, readiness, shared/duplicate ownership,
  Parcel descriptor lifetime and malformed flattening input. The JVM regression
  also exposed a missing ActionBarView; its pinned source is now selected, with
  explicit Holo PhoneWindow inflation and title-update coverage. JVM, system,
  native, Binder and build checks pass, as do all 11 integration tests (38 steps
  without runtime issues) and the hardware-buffer Canvas/pixel-readback probe.
  That native prerequisite left the inventory at 57 stubs. Java SyncFence is
  now stock as described above; its camera imports require only Javadoc headers.

- The latest validated inventory is 50 stubs: 27 pending and 23 documented
  survivors, down from 134 at the start of this follow-up. Of these, 43 are
  outside `com.android.server` (20 pending, 23 survivors). The inventory does
  not count the adapted Activity and ActivityThread implementations and is not
  an overall completion percentage. Those classes and the remaining service
  and native dependencies still prevent completion of the stock-client goal.

- `GraphicsEnvironment`, its JNI, GraphicsEnv and GPU-service client metadata
  now use pinned AOSP sources. The host seam supplies the library loader's
  absence of Android linker namespaces and explicitly reports unsupported
  namespace creation/linking. The ANGLE override flag comes from pinned
  aconfig in force-read-only mode for both Java and C++. The Binder facade now
  supports the stock vector-count, nullable UTF-16 vector and typed result
  receiver contracts required by this closure, including malformed UTF errors.
  Seven selected Java/JNI/C++ files and six generated flag outputs match the
  pinned sources/generator. JVM, separate-process, native, Binder and build
  checks pass, along with all 11 integration tests (38 steps, no runtime issues).
  This adds no GPU service or new Wayland, PipeWire or FFmpeg implementation.

- Nine Activity support classes are stock: CursorLoader/AsyncTaskLoader,
  DirectAction, FullscreenRequestHandler, ScreenCaptureCallbackHandler,
  AutofillClientController, ToolbarActionBar, and the Dumpable container pair.
  All nine selected Java files match pinned AOSP. Importing the autofill
  controller exposed the missing caller-aware IntentSender overload; Activity's
  sender entry points now use the existing task-manager service with stock
  cancellation, fill-in preparation and child/fragment result routing. Activity
  and Window are marked destroyed before `onDestroy`. Tests cover cursor worker
  versus main-thread delivery and closure, action parceling, dumpable identity,
  canceled senders, embedded result delivery, and destruction during recreation.
  JVM, system, native, Binder, C++ and build checks pass; all 11 integration tests
  pass with no structured runtime issues. These support imports do not complete
  autofill, screen capture or fullscreen services. A direct compile of stock
  Activity now has ten errors: nine voice-interaction references and
  `ViewRootImpl.dump`; its JNI and ActivityThread integration also remain.

- Explicit activity recreation now follows AOSP's queued relaunch protocol,
  coalesces requests, ignores local embedded tokens, checks the main thread, and
  uses stock transactions to restore the prior lifecycle state. The synchronous
  hosted recreation helper is deleted. Separate-process coverage verifies real
  Binder tokens, callback ordering, saved and retained state, and normal finish
  after recreation. JVM, system, native, Binder, C++ and build checks pass;
  all 11 integration tests pass with no structured runtime issues. Window
  preservation remains disabled at the existing hosted presentation boundary.

- `HostedActivityRuntime.kt` and the three remaining Activity result-target hooks
  are deleted. `ApkHost` launches through stock `Context.startActivity`, and
  ActivityThread uses the package context's class loader and package manager.
  Its host adapter now covers window presentation only. Lifecycle fixtures use
  the production task manager and stock client transactions; this exposed and
  fixed a missing start callback when returning from the stopped state. Tests
  cover embedded activities, fragments, saved state, results and configuration
  changes. JVM, system, native, Binder, C++ and build checks pass; all 11
  integration tests pass with no structured runtime issues. Activity and
  ActivityThread remain adapted; explicit recreation is now queued as above.

- Activity monitor results now use `ActivityThread.sendActivityResult` and stock
  `ActivityResultItem` transactions instead of being dropped by the adapted
  Activity. Regression coverage checks asynchronous pause/result/resume ordering,
  result preparation and the negative request-code case. Five `$artzig` lifecycle
  accessors are removed in favor of stock fields/methods; top-resumed callbacks
  now emit generated AOSP activity event tags. The generated Java matches the
  pinned logtags generator. JVM, system, native, Binder, C++ and build checks pass,
  as do all 11 integration tests with no structured runtime issues. The three
  legacy result-target hooks and `HostedActivityRuntime` are now removed as
  described above.

- Application startup now crosses `IActivityManager.attachApplication` and
  `IApplicationThread.bindApplication` before activity transactions or pending
  service work. The launcher and service entry points no longer call
  `handleBindApplication$artzig`. Stock `AppCompatCallbacks` and
  `SafeZipPathValidatorCallback` install compatibility decisions before application
  code. The hosted package manager queries enabled providers by process/UID and
  sorts by `initOrder`; binding installs them before `Application.onCreate`.
  The process announcement carries a copied window configuration override.
  Tests cover callback ordering, provider access during Application startup,
  disabled/other-process provider exclusion, compatibility decisions, and
  LoadedApk construction versus binding. JVM, system, native, Binder, C++ and
  build checks pass; all 11 integration tests (nine NewPipe, two ViewHost) pass
  with no structured runtime issues. Both newly selected Java files match pinned
  AOSP. Instrumentation/sandbox binding modes and the complete stock
  ActivityThread remain unfinished.

- `PlatformCompat` and `PlatformCompatNative` now serve compatibility queries;
  the hosted always-enabled service is deleted. Pinned ChangeIdProcessor and
  Soong's merger generate 162 change definitions from the selected framework
  sources, installed under the ART root's `etc/compatconfig`. Generated output
  matches an independent processor/merger run. Stock package and settings
  observers are registered after their services start. Tests verify target-SDK
  gates, disabled/unknown changes, application configuration sets, and permission
  rejection through Java and native Binder interfaces. JVM, system, native,
  Binder, C++ and build checks pass; all 11 integration tests pass with no structured
  runtime issues. App-process callback installation is now part of application
  binding, described above; ActivityThread itself remains adapted.

- `SharedUserSetting` and its eight-class compatibility closure are stock.
  The config and override XML models are generated by pinned xsdc (nine Java
  files), and both compatibility flag packages use aconfig. Tests cover shared
  UID flag aggregation, package snapshots, target-SDK gates, version-bounded
  overrides and XML save/restore. These are the stock compatibility service's
  prerequisites; the running stock service is described below.
  All eight selected Java files and nine generated models are byte-identical to
  their pinned source/generator output. The inventory is 58 stubs, 28 pending.
- Runtime shutdown now follows `AndroidRuntime::exit` through the native `_exit`
  boundary, including Binder disconnect termination. Zig's libc-linked
  `std.process.exit` ran destructors under live ART workers and produced a
  system-server shutdown crash. The focused `jvm-exit-test` installs an atexit
  trap: it failed with code 99 before the fix and passes with code 23 afterward.
  The final JVM, system, native, Binder, C++ and build checks pass without that
  crash; all 11 integration tests pass with empty structured runtime issue lists.

- `ApexManager` and `ApexSystemServiceInfo` are stock, with seven generated
  apexd Binder declarations. The hosted service exposes the empty APEX mount
  and session inventory with stock root/system/keystore caller checks; package
  scanning initializes stock mappings before consumers query them. Installation
  and mount mutations remain explicitly unsupported. Tests cover scan ordering,
  cache isolation, module/package mappings, system-service order, unknown
  sessions, duplicate scans, and remote permission rejection. JVM, system,
  native, Binder, build checks and all 11 integration tests pass (39 steps, no runtime
  issues). Both Java files match the pinned checkout byte for byte.

- `WifiManager` and its 35-class closure are stock, with generated service
  interfaces and explicitly staged Connectivity parcelable declarations.
  The hosted service reports disabled Wi-Fi and preserves permission checks,
  Binder lock ownership, work attribution, and death cleanup. The stock client
  owns reference counts and its 50-lock limit. No radio, scanning, power mode,
  or multicast filter backend is implemented. Module, separate-process, full
  APK, native, and both Binder suites pass; the 11 integration tests have no structured
  runtime issues. All 35 Java sources match the pinned checkout byte for byte.

- `HandwritingInitiator` is compiled from the pinned AOSP source; the stub is
  deleted. The hosted root supplies `ViewConfiguration` and `InputMethodManager`
  and follows the AOSP IME-window input guard. The no-IME service reports that
  neither handwriting mode is available, with the AOSP cross-user permission
  check. Current counts are maintained in `docs/stubs.md`.
- `ArtzigTestDiagnostics` is Kotlin in `dev.artzig`; the Zig liblog sink,
  launcher, hosted services, and generated package-manager adapter use that
  class. Its cursor protocol and JNI method descriptors are retained. The
  framework integrations are removed: `ThreadedRenderer.SimpleRenderer` now
  reuses the AOSP nested renderer, and `RavenwoodEnvironment` is compiled from
  AOSP with its normal device-side exceptions.

- `BinderCallsStats` and its latency observer, bucket, device-state, and package
  helpers are stock. The latency proto is generated from the pinned definition.
  Module tests exercise charging exclusion, call and exception counts, sizes,
  transaction names, and reset.
- `AconfigFlags` and its Java storage readers are stock. `DeviceProtos` and the
  aconfig nano message are generated from pinned inputs. The manifest flag uses
  the pinned BP2A release value, so package parsing applies the same feature
  gates as the platform manifest expects. Module tests and the full APK suite
  pass with these replacements.

- `ProtoLog` and its implementation closure, `AudioDeviceCallback`,
  `AudioRouting`, `MediaCryptoException`, `RouteDiscoveryPreference`,
  `MediaProjection`, `PictureProfileHandle`, and the legacy `EGLConfig`, `GL`,
  and `GL10` interfaces are compiled from AOSP. The hosted root follows the
  stock ProtoLog initialization order. No graphics or media backend behavior
  is added by this source replacement.

- `ArtzigAccessibilityBridge` is Kotlin in `dev.artzig`. It observes the stock
  accessibility manager through `AccessibilityPolicy`, assigns window identities
  outside the framework, and rejects stale actions after comparing node and
  window snapshots. Ordinary `OnDrawListener` notifications keep the idle
  cursor sensitive to draws without node changes. The root and window stack no
  longer call test machinery.
  The hosted root reuses AOSP accessibility event ordering, content-change
  throttling, and focus bookkeeping; it still invalidates the whole hosted root
  and has no WindowManager keep-clear regions.

- `HealthPermissions` and its framework dependency closure are stock, from the
  pinned HealthFitness module. Its AIDL interfaces and flags are generated from
  the module's inputs, including its own aconfig container. Permission-category
  mappings and failure behavior pass module tests and the full APK suite.

- `ActivityOptions`, `SplashScreen`, and the activity transition coordinators
  and state are stock. The adapted activity core now carries scene metadata,
  saves transition state, and releases splash listeners on destruction. The
  hosted package service implements splash-theme lookup, owner checks, and
  clearing. Module tests cover parcel round-trips, transition-state restoration,
  listener cleanup, and theme clearing. This does not provide splash-window
  rendering or complete task/window animation behavior.

- `IWindowManager` is generated from the pinned AIDL. Window-context tokens,
  controllers, providers, and configuration/removal transaction items are stock.
  The process-local Kotlin window service supplies display-area attachment,
  ownership checks, configuration delivery, and detachment. The adapted window
  manager follows AOSP token and window-type validation. Module tests cover
  resource-before-callback ordering, parcel copies, duplicate attachment, and
  removal cleanup; the full APK suite passes.

- `AbstractInputMethodService`, `InputMethodService`, their Binder wrappers,
  navigation and extracted-text views are stock; diagnostic protos are generated.
  A declared fixture IME passes creation, binding, and destruction tests. The
  hosted root queues injected input in arrival order and recycles pending events
  on detachment. Window sessions now expose unserved operations explicitly.
  Hosted windows still lack server WindowStates and input channels: IME visibility
  tracking follows AOSP's missing-window failure path. Selecting and presenting a
  third-party IME remains service/backend work; replacing these Java classes does
  not complete that capability.

- `Debug` and `TypedProperties` are stock. Zig JNI supplies Linux memory and
  allocator observations, AOSP heap categories and parcel fields, and hosted
  Binder object counts. Module tests cover output-array mutation, failed PID
  queries, parcel round-trips, live reference counts, and allocator reports that
  preserve descriptor ownership. Android debuggerd, malloc_debug, memtrack/BPF,
  and heap-tracing facilities remain unavailable; kernel configuration queries
  require the running kernel's exposed configuration.

- `Instrumentation`, `UiAutomation`, `PerformanceCollector`, and
  `TestLooperManager` are stock. The adapted Activity now supplies the attachment
  signature and persistable lifecycle callbacks they require. Tests cover
  lifecycle ordering, state restoration, application/token identity, activity
  monitor results, and referrer propagation. Legacy lifecycle fixtures install
  their own task-service adapter; production Instrumentation uses the stock
  activity-task-manager path. The JVM and full APK suites pass. UiAutomation
  surface screenshots still require a compositor transaction service; the hosted
  service reports that missing operation explicitly.

- `EGL14`, its four handle classes, and `EGLObjectHandle` are stock. Zig JNI
  exposes real Mesa EGL contexts, configurations, pbuffers, current handles,
  queries, and errors. The EGL backend provides offscreen GBM drawables on the
  existing render device, so default window configurations also support Android
  clients that create pbuffers. Native tests read rendered pixels, check that
  swaps retain them, and destroy a surface while its context is still current.
  JVM tests require a real context and 16x8 pbuffer when
  `AZK_TEST_REQUIRE_EGL=1`; they also cover array bounds, handle equality, and
  AOSP argument failures. Window surfaces still require a compatible host
  presentation adapter; no Wayland implementation changes are included.
  The JVM/native checks and all 11 integration integration tests now pass, including the
  two fullscreen regressions exposed by the initial surfaceless implementation.
  Hosts without an available GBM device retain the more limited surfaceless
  display. EGL texture binding and Android window presentation remain separate
  capabilities; replacing these Java classes does not provide either backend.

- `AudioFocusRequest`, `MediaRoute2Info`, and `MediaRouter2Utils` are stock.
  Focus request validation, listener/handler identity, flags, force-ducking
  metadata, route visibility, and parcel copies pass module tests and the full
  APK suite. The adapted `AudioAttributes` retains AOSP bundle-copy semantics and
  parcel layout, and the hosted output descriptor is constructed outside the
  framework. Audio-focus arbitration and dynamic routing still need hosted
  services; this batch does not change PipeWire.

- `AppGlobals` is stock. Application and package identity now come from the bound
  ActivityThread application, with the stock core-setting defaults and cached
  permission-service lookup. The hosted permission service supplies package/UID
  permission reads over installed grants; the outer PermissionManager and its
  remaining operations are still adapted. Module and full APK suites pass.

- `ActivityManager` and its backup/process-statistics dependency closure are
  stock. Process-statistics protos and localization sysprops are generated, and
  the backup streams and tar transport use the pinned AOSP JNI/C++ code.
  Hosted AM answers process identity and Linux memory queries and retains
  session-local exit records (unknown reason when Binder death supplies no
  wait status). The stock client's AppTask handles now refer to owned hosted
  tasks, with package/caller checks and lifecycle operations. Wire-format,
  parcel, separate-process, JVM, and full APK tests pass. Backup transport
  services, persistent exit history/tombstones, and the full AMS policy remain
  separate capabilities.

- `ActivityManagerInternal` and its nine-source closure are stock. A generated
  adapter in `dev.artzig.system` preserves every abstract signature; Kotlin owns
  boot readiness, process queries, and incoming-user permission checks. Other
  server operations report structured unsupported diagnostics. AppTask tests
  cover caller ownership, exclusion from recents, starts within an existing
  task, pause-before-launch/destroy ordering, immediate removal from recents,
  and stale handles. Moving the resumed task to front no longer sends a second
  resume. JVM, Binder, native, separate-process service, and full APK checks pass;
  the APK suite captured 38 steps with no runtime issues.

- `NetworkScoreManager`, the Wifi score/model closure, `NetworkScoreService`,
  and `NetworkScorerAppManager` are stock. The existing boot manager starts the
  stock service and its settings observers. A Kotlin legacy-permission adapter
  connects default open-Wifi location grants to the hosted package store.
  Stock cache registration/invalidation, score ranking/parcel behavior, and
  cross-process permission denial pass. A fixture now verifies default location
  grants, scorer discovery, provider binding, score delivery, and invalidation
  when its service component is disabled. The stock PackageMonitor dispatcher
  receives hosted installs and enabled-state changes. No Wifi scanner or
  associated Android Wifi interface is advertised. The closure tool now excludes
  historical AIDL API dumps when a live declaration exists.

- `FingerprintManager` and its HAL interface/parcelable closure are stock.
  The hosted Binder service models the absence of fingerprint providers, with
  permission checks, empty sensor-registration callbacks, absent authenticator
  IDs, and the stock absent-provider authentication result. Separate-process
  tests cover allowed/denied queries and cross-user enrollment checks. Sensor
  enrollment/management, biometric prompts, and actual biometric hardware remain
  separate work.

- `JankTracker`, `JankDataProcessor`, and `StateTracker` are stock. The adapted
  SurfaceControl contains the verbatim jank data/listener contracts and the
  root exposes its own Choreographer. AOSP's AttachedSurfaceControl default
  reports no compositor subscription; no frame samples are invented. Module
  tests verify app/compositor jank classification and aggregation.
  `SoftApConfiguration` is stock, with its Wifi environment closure and generated
  VNDK properties; `AppBackgroundRestrictionsInfo` is generated from the pinned
  framework proto. These changes pass JVM, separate-process service, Binder,
  native, and full APK checks (11 integration tests, 37 steps, no runtime issues).

- `ApplicationSharedMemory` is stock, including its mapping, feature-cache, and
  atomic-access JNI. A Kotlin allocation adapter uses libcore's stock POSIX JNI
  to supply sealed Linux memfds in place of the host ashmem implementation.
  Tests verify read-only duplication, size seals, shared updates, descriptor
  ownership, and mapping lifetime. JVM, separate-process service, Binder, native,
  and all 11 integration tests pass.

- `CompanionDeviceManager`, its association/request models, the Bluetooth
  client closure, `TetheringManager`, and local sockets are stock. Local sockets
  use the pinned JNI implementation, with credential, descriptor-transfer, and
  EOF tests. The hosted companion service reuses AOSP's association store,
  persistence, ownership checks, and listener dispatcher. Immediate self-managed
  associations without a device profile pass separate-process creation, query,
  callback, sync-flag, removal, and permission tests. Consent/discovery, role
  grants, presence binding, transports, and a tethering service remain unfinished.
  No Bluetooth adapter is advertised. The closure tool now recognizes an AIDL
  interface following a nested parcelable forward declaration.

- Hosted root attachment and destruction now maintain the WindowManagerGlobal
  registry. Its window, token, child-window, debug-name, and listener queries
  follow the pinned methods, including listener replay and snapshot copies.
  These changes pass JVM, separate-process service, Binder, native, launch, and
  full APK checks (11 tests, 37 steps, no runtime issues).

- `DefaultMimeMapFactory` is stock. Debian, Android, and vendor MIME tables are
  generated from the pinned inputs using Soong's minimization rules. The runtime
  jar packages these resources alongside D8's output, since D8 discards them.
  Module tests verify Android overrides, vendor fallback, and malformed-input
  failures; the JVM, service, Binder, native, and full APK suites pass.

- `SystemConfig`, `PermissionAllowlist`, and `VintfRuntimeInfo` are stock.
  `RoSystemFeatures` comes from the pinned Kotlin generator and BP2A feature
  arguments. The native runtime-info seam reads the host kernel and system
  properties, preserving independently cached query groups and failure values.
  The host reports no incremental filesystem. Module tests cover permission XML,
  feature version merging, existing versus missing libraries, and runtime queries.

- `InteractionJankMonitor`, `FrameTracker`, display classification, and the
  debug overlay are stock; flags and event log tags are generated. The hosted
  root supplies AOSP's surface callback registry and draw notifications. Its
  unallocated SurfaceControls retain the stock missing-layer and empty-listener
  behavior. Module tests cover configuration rejection, listener cleanup, and
  draw callback ordering. Actual compositor jank data remains unavailable.

- `AudioTimestamp`, `PlaybackParams`, and `MediaFormat` are stock. Module tests
  cover 64-bit parcel data, unset/default playback parameters, invalid pitch,
  typed format getters, and live key/feature views. The existing AudioTrack
  adapter marks its returned default playback parameters as set, following
  AOSP JNI. No PipeWire or FFmpeg implementation is changed.

- The closure tool now retains every static import owner when multiple classes
  share the name `Flags`, and indexes logtag package declarations. Regression
  tests cover both cases. Source prerequisites for the remaining client stacks
  and the pinned x86_64 Chromium WebView APK are curated, but staging them does
  not select those clients for compilation or provide a working WebView provider.
  The combined batch passes JVM and service tests, Binder/native checks, and all
  11 integration tests (37 steps with no runtime issues).

- `RuntimeInit`, Android logging/print streams, and DDMS initialization are stock.
  The native launcher enters non-zygote Java mains after common initialization
  and dispatches escaped main exceptions through Android's uncaught handlers.
  Hosted AM stores crash/WTF reports in DropBox and retains reported crash
  reasons on process death. Registered broadcasts use stock application-thread
  delivery, preserving sender identity and initial/final ordered result state.
  Socket tagging installs the stock SocketTagger contract; absent per-tag kernel
  accounting reports an explicit unsupported diagnostic and counters return -1.
  The full TrafficStats client and graphical ActivityThread bootstrap remain
  unfinished. Deliberate process-crash, broadcast, JVM, service, Binder/native,
  launch, and all 11 integration tests pass (38 steps without runtime issues).

- `InputEventInjectionSync`, `PointerIconType`, and `ViewProtoEnums` are generated
  from pinned AIDL/proto declarations. The closure tool now indexes explicit
  proto Java outer classes and emits their generator declarations; seven tool
  regression tests pass.

- `GLES20`, `GLES30`, `GLES31`, and `GLES32` are stock Java APIs backed by
  the pinned AOSP JNI bindings and the existing Mesa GLES library. Native patch
  28 fixes heap-buffer array releases and typed-buffer bounds checks in those
  bindings. Required-GPU module tests pass pixel readback, texture lifetime,
  shader compilation, position/limit preservation, invalid arguments, and GL
  errors. `TouchOcclusionMode` is generated from the pinned GUI AIDL.

- `SystemFeaturesMetadata` is generated by AOSP's Kotlin annotation processor
  from the pinned `PackageManager` declarations. Its 186 SDK features replace
  the placeholder count and unknown-only lookups. Module tests cover distinct
  feature indices, version thresholds, unavailable features, custom-feature
  fallback, and cache reconstruction. The GLES/metadata batch passes JVM,
  system-service, Binder/native, launch, and all 11 integration tests (35 recorded
  steps without runtime issues). The audit is 80 stubs: 34 pending, 46 survivors.
  Build configuration explicitly tracks both package manifests so changing
  an AOSP archive hash cannot reuse source paths from the previous package.

- `NativeLibraryHelper`, `SELinux`, and the incremental-install clients are
  stock, with pinned native extraction and IncFs implementations. The IncFs
  C++ sysprop accessor is generated from its pinned declaration. SELinux JNI
  uses the host's libselinux policy backend; Android seapp policy is not
  installed. IncFs reports disabled on this host and ordinary files have no
  incremental signature. The launcher delegates ABI selection and extraction
  to NativeLibraryHelper, retaining extracted x86_64 files for the host linker
  and accepting APKs with no native code. Tests cover CRC-based replacement,
  executable permissions, ABI mismatch, Java-only archives, failed handle
  creation, and native/SELinux queries. JVM, system, Binder/native, launch, and
  all 11 integration tests pass (36 steps without runtime issues). The audit is
  78 stubs: 34 pending, 44 survivors.

- `StrictJarFile`, its manifest readers, and its verifier are stock, with the
  pinned ZIP JNI. The permissions fixture is signed with both v1 and v2
  signatures. Tests verify certificate availability only after reading a full
  entry, tampered-content rejection, signature-stripping protection, and owned
  descriptor closure.
- `MemoryIntArray` is stock Java with stock native atomic access and cleanup.
  Its allocation and mapping ports use sealed Linux memfds through libcore
  Posix. Size seals prevent resizing; recipients cannot write through a file
  descriptor or acquire a writable mapping. Tests cover parcel ownership,
  bounds, closed-state failures, and separate-process updates that remain
  readable after the owner closes its mapping. SettingsProvider still does not
  publish cache-generation trackers; this replacement supplies that dependency.
- `WebViewClient` is stock. Unhandled events enter the hosted root's asynchronous
  synthesis queue; trackball, joystick, touch-navigation, and keyboard handlers
  are reused from pinned ViewRootImpl. Detachment cancels joystick repeats and
  pending input. Tests cover motion-event ownership, joystick cancellation,
  exclusion of relative mouse and already-synthesized keys, and default client
  certificate rejection. A working Chromium provider remains unfinished.
  The archive/shared-memory/WebViewClient batch passes JVM, separate-process,
  Binder/native, launch, and all 11 integration tests (36 steps without runtime issues).
  The audit is 75 stubs: 33 pending, 42 survivors.

- `HardwareBuffer`, `GraphicBuffer`, and `TaskSnapshot` are stock Java. Their
  native buffer ports retain GBM storage, carry DMA-BUF descriptors through
  Binder with the stock GraphicBuffer envelope, and reuse pinned ACanvas code
  for software Canvas access. Imported buffers retain their identity and
  storage after the sender closes. Dirty rectangles clip drawing without
  shifting the buffer origin; patch 29 preserves RGBA byte order when host
  Skia uses BGRA for ordinary bitmaps. The TaskStackListener reflection patch
  is removed, restoring stock snapshot release behavior. Module tests cover
  local versus remote listener ownership, snapshot parcel metadata, closed
  buffers, and Canvas clipping. Separate-process buffer transfer and exact
  red/blue pixel readback pass, as do JVM, native, Binder, build audits, launch,
  and all 11 integration tests (37 steps without runtime issues). The audit for this
  batch is 73 stubs: 33 pending, 40 survivors.

- `OverlayProperties` and `LutProperties` are stock Java, backed by pinned JNI
  and C++ parcelables generated from the pinned GUI AIDL. The default object
  exposes AOSP's RGBA/sRGB virtual-display combination. Native Parcel now
  supports the enum and nullable-parcelable vectors used by those generated
  sources. Patch 30 replaces an unsafe native-pointer-to-Java-array conversion
  with JNI array marshalling. The pinned Java LUT constructor currently rejects
  every dimension; that observable failure is preserved and tested, so this
  change does not advertise working display LUTs. Default capability queries,
  parcel round trips, malformed data, and native enum wire bytes pass. JVM,
  separate-process, native, C++ Binder, build audits, launch, and all 11 integration
  tests pass (36 steps without runtime issues). The overlay batch audit is
  72 stubs: 33 pending, 39 survivors.

- `SurfaceSyncGroup`, `PixelCopy`, and `SurfaceControlInputReceiver` are stock;
  remote-transition and embedded-surface callbacks are generated from pinned
  AIDL. The nested `SurfaceControlViewHost.SurfacePackage` is reused verbatim.
  AOSP's SurfaceSyncGroupController runs in the shared system process, with
  the process-local window service forwarding its sync operations over the
  stock IWindowManager protocol. Tests verify parent/child readiness ordering,
  completion across processes, caller-UID ownership, and remote-transition
  finish callbacks and token identity. PixelCopy retains stock validation and
  callback delivery; patch 31 returns SourceInvalid when the hosted renderer
  cannot translate a Java Surface into an Android native window. It does not
  implement compositor readback or video-layer BLAST synchronization.
  JVM, system, native, Binder/C++ Binder, and build audits pass. All 11 integration tests
  pass (36 steps without runtime issues), after correcting the testkit's missing
  cursor for relative action waits. Playback checks wait for frame presentation
  because a live stream need not become idle; controls and screenshot checks
  remain in place. All 21 testkit tests pass. The window batch audit is
  68 stubs: 33 pending, 35 survivors.

- `VerityUtils` is stock Java with its pinned Bouncy Castle CMS dependency
  closure and unmodified fs-verity JNI. AOSP's external signature/certificate
  fixture verifies successfully; altered signatures and digests, wrong digest
  lengths, and embedded certificates are rejected. Filesystem queries and
  setup retain Linux fs-verity error behavior. This host filesystem does not
  support enabling fs-verity; the tests exercise that failure without claiming
  verity-protected storage. JVM, separate-process, native, Binder/C++ Binder,
  and build audits pass.

- `UsbDevice`, `UsbConfiguration`, `UsbInterface`, `UsbEndpoint`, and
  `UsbConstants` are stock. Device IDs use pinned JNI and the host-supported
  libusbhost source. Serial readers and display connection constants are
  generated from pinned AIDL; USB enum constants come from the proto generator.
  Tests verify descriptor parcel data, endpoint attributes, serial-reader
  permission failures, and usbfs name/ID conversion. This does not add a USB
  device service or open physical devices. The combined fs-verity/USB build
  passes JVM, separate-process, native, Binder/C++ Binder, build audits, launch,
  and all 11 integration tests (37 steps without runtime issues). Its audit is
  65 stubs: 33 pending, 32 survivors.

- `WindowManagerGlobal` and `WindowlessWindowLayout` are stock. Hosted
  activity roots and overlay windows enter its add/update/remove path;
  `HostedWindowRoots` supplies desktop geometry. Deferred root destruction
  preserves the stock registry iteration order, and overlay removal now
  destroys its root rather than merely detaching its view. Tests cover token
  lookup, parent/child stop propagation, unrelated roots continuing to draw,
  deferred close callbacks, layout-parameter preservation, and configuration
  callback ordering, queued copies, stale sequences, and RTL/LTR changes.
  HWUI profile dumping and root hierarchy accounting reuse pinned code.
  The roots still share the hosted renderer and main-loop presentation;
  windowless SurfaceControl presentation reports unsupported. This does not
  replace ViewRootImpl, ThreadedRenderer, or the activity core. JVM, system,
  native, Binder/C++ Binder, build audits, launch, and all 11 integration tests pass
  (38 steps without runtime issues). The stub audit remains 65: 33 pending,
  32 survivors; this batch removes a framework adaptation outside that inventory.

- `WindowManagerImpl` is stock. The Kotlin `HostedWindowStack` in `dev.artzig`
  observes the stock registry to track overlay composition and input, and the
  launcher identifies its activity root separately. Stock add/update/remove
  calls preserve overlay entries and refresh layout parameters without framework
  hooks in the client manager. Host configuration now includes window bounds,
  app bounds, and maximum bounds; stock window metrics use the context resources
  and window service, including density changes and immutable snapshots.
  Module tests cover overlay focus, update/removal, and metric behavior. JVM,
  separate-process, native, Binder/C++ Binder, build audits, launch, and the full
  APK suite pass. The packaged manager matches the pinned source verbatim.
  Surface mirroring reports unsupported at the existing SurfaceControl seam.
  The stub inventory remains 65 (33 pending, 32 survivors); the removed manager
  and Android-package overlay stack were framework adaptations.

- `WifiP2pManager` and 26 supporting classes are stock, including configuration,
  peer/group models, DNS-SD records, and AsyncChannel. Both Wi-Fi Direct Binder
  interfaces are generated from pinned AIDL. Tests cover configuration failures,
  peer parsing, parcel copies, DNS-SD wire encoding, asynchronous error callbacks,
  listener removal, channel cleanup, permission errors, and feature queries over
  generated Proxy/Stub transactions. JVM, separate-process, native, Binder, build
  checks, and all 11 integration tests pass (39 steps without runtime issues). All 27
  packaged Java files match the pinned checkout. No Wi-Fi Direct hardware or
  supplicant service is added; the client tests use an in-memory service fixture.

- URI grants now use stock `UriGrantsManagerService`, its local interface,
  owners, persistence and metrics helpers. The permissive hosted grant service
  is deleted. The hosted activity and package services delegate explicit grant,
  revoke, provider-access and permission checks to the stock implementation.
  Separate-process tests cover a private provider, prefix and read-only grants,
  denied writes and unoffered persistence, persistence release, and revocation.
  Activity-launch Intent grants and activity-record lifetime are now connected
  to this service, as described in the later follow-up above.
- `StatsManager`, its query models, initializer, and `StatsManagerService` are
  stock, with generated StatsD Binder interfaces. No statsd daemon is added;
  the stock service caches pull-atom registrations while it is absent. Module
  tests cover permission rejection, offline registration, replacement, removal,
  reconnect replay, and URI-owner lifetime. JVM, separate-process, native,
  Binder, C++ and build checks pass; all 11 integration tests pass with 38 steps and no
  runtime issues. All 18 packaged Java files match the pinned checkout. The
  inventory is 63 stubs: 32 pending and 31 survivors.

- The audio-policy V7_0 XML model and enums are generated from the pinned
  schema using AOSP xsdc. Typed XSD declarations now drive both audio-policy
  and APEX generation; the source audit rejects handwritten replacements of
  these outputs. All 31 audio-policy sources match direct xsdc generation.
  Parser tests cover nested modules, device routes, profiles, enum values,
  defaults, unknown extensions, and invalid input. JVM, separate-process,
  native, Binder, build checks and all 11 integration tests pass (39 steps with no
  runtime issues). This supplies an audio-client prerequisite; it adds no
  PipeWire behavior and does not yet replace `AudioAttributes` or `AudioManager`.

- `Installer`, `PackageSetting`, `SettingBase`, and the legacy permission-data
  interface are stock. The installd interface and parcelables, package dump
  protos, and server logtags are generated from pinned declarations. Hosted
  package records now use stock mutable user and transient state; the helper
  exposing package-private defaults is deleted. The hosted install service
  remains responsible for installation; no installd daemon is added.
  Tests cover separate users, snapshot invalidation and isolation, MIME-group
  changes, isolated-installer results, batch boundaries and per-request errors.
  JVM, separate-process, native, Binder, C++ and build checks pass, along with
  all 11 integration tests (39 steps without runtime issues). All four packaged Java
  files match the checkout. The inventory is 61 stubs: 30 pending, 31 survivors.

`Dialog` was already compiled verbatim from the pinned AOSP source before this
follow-up. Activity, ActivityThread and Instrumentation now also compile verbatim,
as recorded in the milestones above. Window/client adaptations and the remaining
service-backed stacks still prevent full stock-client completion.
The initial activity-family closure reported 495 sources, 273 AIDL interfaces,
and 229 blockers or missing members. That is a historical dependency report,
not the current gap count or 229 independently verified runtime failures.

## Original fence (superseded by the follow-up scope)

The original batches did not modify, delete, or replace the following classes
and patches. This list records their previous boundary, not a prohibition on
the follow-up work.

- `src/framework/android/media/*`, `android/view/*`, `android/graphics/*`,
  `android/hardware/HardwareBuffer.java`,
  `android/content/res/Hosted*.kt`.
- stub families `audio`, `opengl`, `media`, and every survivor whose reason
  starts with `window seam`.
- patches 7, 8, 9, 13, 16 in `aosp/package-androlinzberg-aosp.py`.

## Rules that are easy to get wrong

- A hosted service never delegates to `IFoo.Default()`. Unserved methods must
  fail loudly so the integration tests record them as `unimplemented`. Use the
  generated `Unsupported*` base, or after batch 0 the `unsupported<T>()`
  delegate. `HostedInputMethodManagerService` currently violates this; batch 0
  fixes it.
- Framework natives are written in Zig (`src/binder/framework_jni.zig` is the
  pattern), never in `src/native/*.cpp`. C++ is only for HWUI, Skia and AOSP
  C++ interfaces (`docs/native-boundary.md`). The `android.util.Log` sink was
  added in C++; batch 0 moves it.
- Never raise `pending-budget`. Delete a stub and its audit line together.
  A stub that cannot go yet gets a `survivor: <reason>` the closure tool can
  confirm, not a family.
- Every repository JVM file is Kotlin unless it is a directly reused AOSP or
  generated source.
- No attribution lines in commit messages.
- A commit that changes `aosp/androlinzberg-files.txt` must repackage and bump
  the `.aosp` hash in `build.zig.zon` in the same commit. The archive is
  reproducible: anyone can run `python3 aosp/package-androlinzberg-aosp.py`
  and get the committed hash, so never commit a hash you did not obtain from
  the packager on the committed manifest.
- The APK integration suite is mandatory for every batch, not only
  `jvm-test`.

## Per-batch workflow

Group related stubs and their shared dependency closure into each batch. Run one
combined preflight, import the complete supported group, and share the packaging,
rebuild and regression cycle. Split a batch when a real backend blocker would
hold up independently usable imports; commit each verified batch incrementally.

1. Build once so a current classes jar exists:
   `zig build -j<n> java` (add `--system zig-pkg` on the machine described in
   the section at the end). The jar is the newest
   `.zig-cache/o/*/framework-classes.jar`.
2. Run the closure tool with every stub the batch deletes as a seed:

   ```sh
   tools/aosp-closure.py --classes <jar> closure \
     --assume-present <known false positives> <fqn> <fqn> ...
   ```

   Seed with all of them at once: the tool counts classes in the jar as
   present, so a stub you forget to seed hides the closure of the ones you
   did seed. It prints the `aosp/androlinzberg-files.txt` lines,
   `build/java_sources.zig` lines, and `build/generator_sources.zig` entries
   (AIDL, aconfig) the real classes need, and names every blocker.
3. Apply those lines. New AIDL include roots go in `addAidlIncludes` and,
   where needed for output naming, `aidlOutputPath` in `build/generators.zig`.
   The closure tool reads the include and staging roots from that configuration;
   there is no separate root catalog. Proto outputs go in `proto_outputs`.
4. Delete the stubs, remove them from `build/project_jvm_sources.zig`, delete
   their lines from `tools/stub-audit.txt`, lower `pending-budget` by the
   number of pending stubs deleted.
5. Repackage, bump the hash, `zig build -j<n> gen-aidl`.
6. Write or extend hosted services where a real class needs one.
7. Verify, in this order, and fix before moving on:

   ```sh
   zig build -j<n> build-tools-test graph-audit cpp-audit
   zig build -j<n> native-test binder-test system-test jvm-test
   zig build -j<n> integration-test -- --class NewPipeTest --method launchesHomeWithoutRuntimeIssues
   zig build -j<n> integration-test
   ```

   `jvm-test` runs the JVM-source audit (stub ratchet, generated-class
   shadowing) through the java step.
8. Regenerate the audit and the doc:
   `tools/aosp-closure.py --classes <jar> audit --update tools/stub-audit.txt --markdown docs/stubs.md`.
9. One commit per batch, subject `framework: ...`, body stating what moved and
   what was cut.

## Batch 0: pay down the two review findings

Small, independent, do it first so the later batches are cleaner.

1. Add a reflective delegate and drop the generated bases:

   ```kotlin
   inline fun <reified T : IInterface> unsupported(): T = Proxy.newProxyInstance(
       T::class.java.classLoader, arrayOf(T::class.java),
   ) { _, method, _ -> throw ArtzigTestDiagnostics.unsupported(T::class.java.simpleName, method.name, "not hosted") } as T

   class HostedPowerManagerService : IPowerManager.Stub(), IPowerManager by unsupported<IPowerManager>() {
       override fun asBinder(): IBinder = this
       // served subset only
   }
   ```

   Convert all 37 services, delete `src/java/dev/artzig/system/Unsupported*.kt`
   and `tools/gen-unsupported-stub.py`, and fix the comments in the services
   that say they were generated. `asBinder` must be overridden in every
   service.
2. Rewrite `HostedInputMethodManagerService` on the delegate instead of
   `IInputMethodManager.Default()`, keeping only the empty-IME-list methods it
   serves.
3. Move the `android.util.Log` natives (`isLoggable`, `println_native`,
   `logger_entry_max_payload_native`) from `src/native/AospJniBridge.cpp` to
   Zig next to the other framework natives, keeping the liblog write and the
   `ArtzigTestDiagnostics.recordLog` call. `std.Io` policy applies.

## Batch 1: every replaceable stub outside the fence and the activity family

The audit lists 59 pending stubs and 18 survivors as `replaceable`. Take all
of them except:

- families `audio`, `opengl`, `media`, window-seam survivors (fence);
- families `activity` and `ui` (batch 3: the real `Fragment`, `AlertDialog`,
  `ActivityOptions`, `TaskInfo`, `IActivityTaskManager` expect the real
  `Activity`).

That leaves roughly 50 classes: webkit leaves and `WebViewClient`, runtime
utilities (`CancellationSignalBeamer`, `PermissionEnforcer`,
`SynchronousResultReceiver`, `Immutable`, `BrightnessSynchronizer`,
`IPlatformCompat`), jank and metrics logging, `UiThread`, clipboard monitors,
textclassifier and voice contracts, `AbstractInputMethodService`,
`InlineContentView`, `HandwritingInitiator`, `PackageInstaller` and
`IPackageInstaller`, `PackageManagerInternal`, `UserManager` and
`UserManagerInternal`, `PermissionControllerManager`, `PreferenceManager`,
`TextToSpeech`, `VirtualDeviceParams`, the `com.android.server.pm` value
classes, `Counter`, `UnboundedSdkLevel`, and the hand-written
`src/framework` replacements that are pure Java: `Browser`, `FontsContract`,
`KeyGenParameterSpec`, `MimeTypeMap`, `OverlayConfig`, `Xml`, `ProtoUtils`,
`SslCertificate`, `SslError` (add the BouncyCastle X509Name sources; the
project is synced).

Expect these to need more than deletion:

- `UserManager`: the real class depends on `UserManagerCache` from the
  `property_cache` annotation processor. `external/guava` is synced; wire the
  processor into the javac step in `build/java.zig` instead of stubbing.
- `IPlatformCompat` is served by `HostedPlatformCompatService` already; just
  delete the stub.
- `DateFormat`, `Time`, `TimeUtils`: replace only if the vendored libcore ICU
  provides what they need; otherwise leave them and record the reason as a
  survivor. Do not extend the shims.
- `TextToSpeech`, `PreferenceManager`, `PermissionControllerManager` may
  reach a service at runtime that nobody hosts. The integration suite decides:
  a `ServiceNotFoundException` or an `unimplemented` issue in `steps.jsonl` on
  a NewPipe path means a hosted service is needed in this batch.

If the first compile shows a class whose closure the tool did not predict,
seed it and rerun the tool rather than hand-fixing the source list.

## Batch 2: module trees

68 survivors exist only because their project is not in
`aosp/aosp-projects.txt`. Sync, in `aosp/`:

```sh
for p in platform/packages/modules/Connectivity platform/hardware/interfaces \
         platform/external/libphonenumber platform/packages/modules/Wifi \
         platform/packages/modules/Media platform/packages/modules/Permission \
         platform/packages/modules/Bluetooth \
         platform/packages/modules/SdkExtensions platform/packages/modules/IPsec \
         platform/packages/modules/ConfigInfrastructure platform/packages/modules/AppSearch \
         platform/packages/services/Telecomm; do repo sync -c -j5 $p; done
```

The pinned Android 16 manifest keeps the Tethering sources under the
`platform/packages/modules/Connectivity` project; there is no separate
`platform/packages/modules/Tethering` Repo project to sync or inventory.

Add each to `aosp-projects.txt`, keep it sorted. Then:

1. Run the closure tool with all 68 survivors as seeds. Add only the framework
   source directories it names (`framework/src`, `framework/java`,
   `framework-t/src`), never a whole module tree; the packager prunes by the
   manifest and the archive should stay a few hundred megabytes.
2. HAL enums (`android.hardware.radio.*`, `thermal`, `vibrator`, `usb`,
   `health`, `gnss`, `graphics.common`) are AIDL under
   `hardware/interfaces/<hal>/aidl/...`. Add those directories as AIDL include
   roots and list the `.aidl` files in `generator_sources.aidl`; the existing
   generator produces the Java. The `V1_0`/`V2_1` HIDL constant classes are
   checked-in Java under the same tree.
3. Telecom `Flags`/`FeatureFlags`: add the aconfig declaration from
   `packages/services/Telecomm` to `generator_sources.aconfig` and delete the
   three hand-written flag classes.
4. Delete the survivors, change nothing else in their audit lines except
   removing them, repackage, bump the hash.
5. The classes that stay survivors after this batch: JNI-only classes with no
   host meaning, the WebView provider, camera, DRM, `ToneGenerator`, and the
   window seam. Their reasons already say so.

This batch is manifest work and can run on a second branch in parallel with
batch 1; merge conflicts are confined to the source lists and the audit file.

## Batch 3: activity core

The largest deviation and real code, not deletion. Order matters.

1. Hosted `IActivityTaskManager` and `IActivityClientController` in
   `SystemServices.kt`: start, finish, result delivery, relaunch decisions on
   configuration changes, task info for the single desktop task. Serve them
   on the delegate; override only what the client path exercises.
2. Fill the handlers in `src/framework/android/app/ActivityThread.java` that
   throw `notHosted` with the AOSP bodies: `handleLaunchActivity`,
   `handleStartActivity`, `handleResumeActivity`, `handlePauseActivity`,
   `handleStopActivity`, `handleDestroyActivity`, `handleSendResult`,
   `handleNewIntent`, `handleActivityConfigurationChanged`,
   `prepareRelaunchActivity`. Keep the source notes naming the AOSP method.
3. Real `Activity`. It attaches its `PhoneWindow` through the hosted
   `WindowManagerImpl` and `ViewRootImpl`; both are fenced and stay. The
   shim's `requestWindowFeature` and result plumbing disappear with it.
4. Real `Instrumentation` (the `execStartActivity` half) and real `Dialog`.
5. Delete `HostedActivityRuntime.kt`; `ApkHost` starts the main activity
   through the activity manager like a launcher.
6. Delete the `activity` and `ui` families: `ActivityManager`,
   `ActivityOptions`, `ActivityTaskManager`, `IActivityTaskManager`,
   `TaskInfo`, `AppGlobals`, `LocalActivityManager`,
   `ClientTransactionListenerController`, `AlertDialog`, `Fragment`,
   `FragmentManager`, `FragmentTransaction`, `ActionBar`, `Presentation`,
   `DatePicker`, `TimePicker`, `FloatingToolbar`.

Tests for this batch must cover callback ordering, handled versus unhandled
configuration changes, saved-state restore and result delivery
(`src/java/dev/artzig/FrameworkModuleSmoke.kt` is where the module tests
live). Run the full APK suite after every commit here; the NewPipe fragment
navigation and desktop fullscreen tests are the regression surface.

## After the batches

What remains pending is per-seam work, one commit each: `StorageManager`
(hosted `IStorageManager` over the emulated volume), `DevicePolicyManager`
(needs the biometrics HAL from batch 2 and a `SecurityLog` native seam),
`StatusBarManager`, `IncidentManager`, `VirtualDeviceManager`, `BatteryStats`
(proto outputs), `ProtoLog` and `ImeTracingPerfettoImpl` (perfetto protos;
`external/perfetto` is synced), and the remaining patches, each of which
needs a written reason in its header or a deletion.

Then the test seams leave `android.*`: `ArtzigAccessibilityBridge` becomes
the real `AccessibilityManager` over a hosted service, and
`ArtzigTestDiagnostics` moves to `dev.artzig` with the launcher's liblog sink
feeding it.

## Machine notes

- Build steps that must see the invoking shell's environment (the APK
  harness) pass their variables through `Command.setEnvironment`, never
  `Step.Run.setEnvironmentVariable`: this zig caches the build configuration
  and a step that owns an environment map replays the environment captured
  at cache time.
- On the LXC host with the Radeon render node, the suite needs Mesa from the
  nix store exported before `zig build`:
  `__EGL_VENDOR_LIBRARY_DIRS=$M/share/glvnd/egl_vendor.d LIBGL_DRIVERS_PATH=$M/lib/dri GBM_BACKENDS_PATH=$M/lib/gbm LIBVA_DRIVERS_PATH=$M/lib/dri LD_LIBRARY_PATH=$M/lib XDG_RUNTIME_DIR=/run/user/1000`
  with `M` the mesa store path, plus `AZK_APK_NEWPIPE_0_29_1`. Builds there
  use `--system zig-pkg` and need the archive extracted under
  `zig-pkg/<hash>` (`tar xzf <archive> --strip-components=1 -C zig-pkg/<hash>`).
- Every archive bump leaves a full application tree in `.zig-cache/o`, which
  zig never collects. Check `df` before a batch; delete `zig-pkg` trees whose
  hash no zon file references, and remove `.zig-cache` entirely when the
  disk passes 90%.
- The ART archive hash in `artzig/build.zig.zon` does not reproduce from the
  committed patch series (patch 0008 overlaps the build-time
  `art-x86_64-memory-optimization.patch`). Leave it alone; it is a separate
  fix.
