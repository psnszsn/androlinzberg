//! Stock inputflinger beyond the dispatcher: InputReader and its mappers,
//! PointerChoreographer, InputProcessor, the interaction blockers, InputFilter
//! and InputManager, with their external closure (ChromeOS touchpad gestures,
//! Chromium palm rejection over the libchrome units it links, expresslog).
//! The reader's device source is the host event hub (src/native/input), not
//! stock EventHub: /dev/input has no counterpart for an unprivileged session.
const std = @import("std");
const dispatcher = @import("input_dispatcher.zig");
const input = @import("input.zig");

pub const Options = dispatcher.Options;
pub const Generated = struct {
    headers: std.Build.LazyPath,
    sources: std.Build.LazyPath,
    /// libinputflinger_rs: the Rust filter, the input crate and their std.
    rust: std.Build.LazyPath,
};

/// NDK interfaces InputManager, InputProcessor and InputCommonConverter use:
/// the Rust InputFlinger bridge and InputFilter, the input-processor HAL and
/// its common motion types.
/// Frozen VINTF interfaces carry their version-1 hash from aidl_api.
pub const ndk_aidl = [_]struct { root: []const u8, files: []const []const u8, hash: ?[]const u8 = null }{
    .{ .root = "frameworks/native/services/inputflinger/aidl", .files = &.{
        "com/android/server/inputflinger/DeviceInfo.aidl",       "com/android/server/inputflinger/IInputFilter.aidl",
        "com/android/server/inputflinger/IInputFlingerRust.aidl", "com/android/server/inputflinger/IInputThread.aidl",
        "com/android/server/inputflinger/InputFilterConfiguration.aidl", "com/android/server/inputflinger/KeyEvent.aidl",
        "com/android/server/inputflinger/KeyEventAction.aidl",
    } },
    .{ .root = "hardware/interfaces/input/common/aidl", .files = &.{
        "android/hardware/input/common/Action.aidl",            "android/hardware/input/common/Axis.aidl",
        "android/hardware/input/common/Button.aidl",            "android/hardware/input/common/Classification.aidl",
        "android/hardware/input/common/EdgeFlag.aidl",          "android/hardware/input/common/Flag.aidl",
        "android/hardware/input/common/Meta.aidl",              "android/hardware/input/common/MotionEvent.aidl",
        "android/hardware/input/common/PointerCoords.aidl",     "android/hardware/input/common/PointerProperties.aidl",
        "android/hardware/input/common/PolicyFlag.aidl",        "android/hardware/input/common/Source.aidl",
        "android/hardware/input/common/SourceClass.aidl",       "android/hardware/input/common/ToolType.aidl",
        "android/hardware/input/common/VideoFrame.aidl",
    }, .hash = "a796f8e2fe447a0ddba069b6edd1aba2b1c9fc42" },
    .{ .root = "hardware/interfaces/input/processor/aidl", .files = &.{"android/hardware/input/processor/IInputProcessor.aidl"}, .hash = "8caabf520f562590b693af83b1d2a0c409fb0236" },
};

/// Generated closure: the NDK interfaces above, InputProperties, the
/// virtual-device flags the reader reads, and expresslog's statsd atoms.
pub fn generate(b: *std.Build, aosp: std.Build.LazyPath, runner: *std.Build.Step.Compile, aidl_tool: *std.Build.Step.Compile, sysprop: *std.Build.Step.Compile, stats: *std.Build.Step.Compile, input_generated: input.Generated) Generated {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    for (ndk_aidl) |group| for (group.files) |file| {
        const run = b.addRunArtifact(aidl_tool);
        run.addArg("--lang=ndk");
        if (group.hash) |hash| run.addArgs(&.{ "--structured", "--stability=vintf", "--version=1", b.fmt("--hash={s}", .{hash}) });
        for (ndk_aidl) |include| run.addPrefixedDirectoryArg("-I", aosp.path(b, include.root));
        run.addArg("-h");
        _ = headers.addCopyDirectory(run.addOutputDirectoryArg("headers"), "", .{});
        run.addArg("-o");
        _ = sources.addCopyDirectory(run.addOutputDirectoryArg("sources"), "", .{});
        run.addFileArg(aosp.path(b, b.fmt("{s}/{s}", .{ group.root, file })));
    };
    const props = b.addRunArtifact(sysprop);
    props.addArg("--header-dir");
    const prop_headers = props.addOutputDirectoryArg("headers");
    props.addArg("--public-header-dir");
    _ = props.addOutputDirectoryArg("public");
    props.addArg("--source-dir");
    const prop_sources = props.addOutputDirectoryArg("sources");
    props.addArgs(&.{ "--include-name", "android/sysprop/InputProperties.sysprop.h" });
    props.addFileArg(aosp.path(b, "system/libsysprop/srcs/android/sysprop/InputProperties.sysprop"));
    _ = headers.addCopyFile(prop_headers.path(b, "InputProperties.sysprop.h"), "android/sysprop/InputProperties.sysprop.h");
    _ = sources.addCopyFile(prop_sources.path(b, "InputProperties.sysprop.cpp"), "InputProperties.sysprop.cpp");
    const flag_entry = for (@import("generator_sources.zig").aconfig) |entry| {
        if (std.mem.eql(u8, entry.package, "android.companion.virtualdevice.flags")) break entry;
    } else unreachable;
    const cache = b.addRunArtifact(runner);
    cache.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    cache.addArgs(&.{ "create-cache", "--package", flag_entry.package, "--container", flag_entry.container, "--default-permission", "read_only", "--allow-read-write", "false" });
    for (flag_entry.declarations) |file| {
        cache.addArg("--declarations");
        cache.addFileArg(aosp.path(b, file));
    }
    // The release values the Java flags use (high_resolution_scroll).
    for (flag_entry.values) |file| {
        cache.addArg("--values");
        cache.addFileArg(aosp.path(b, file));
    }
    cache.addArg("--cache");
    const flags_cache = cache.addOutputFileArg("flags.pb");
    const flags = b.addRunArtifact(runner);
    flags.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    flags.addArgs(&.{ "create-cpp-lib", "--cache" });
    flags.addFileArg(flags_cache);
    flags.addArgs(&.{ "--mode", "production", "--out" });
    const flags_dir = flags.addOutputDirectoryArg("flags");
    _ = headers.addCopyDirectory(flags_dir.path(b, "include"), "", .{});
    _ = sources.addCopyFile(flags_dir.path(b, "android_companion_virtualdevice_flags.cc"), "android_companion_virtualdevice_flags.cc");
    const stage = @import("stats_tool.zig").templates(b, aosp);
    const run = b.addRunArtifact(stats);
    run.setCwd(stage);
    run.addArgs(&.{ "--module", "expresslog", "--namespace", "android,expresslog", "--importHeader", "statslog_express.h", "--header" });
    _ = headers.addCopyFile(run.addOutputFileArg("statslog_express.h"), "statslog_express.h");
    run.addArg("--cpp");
    _ = sources.addCopyFile(run.addOutputFileArg("statslog_express.cpp"), "statslog_express.cpp");
    // libcap's cap_names.h (external/libcap/Android.bp): an awk list of the
    // uapi capability names, then the _makenames host tool over it.
    const names_list = b.addSystemCommand(&.{ "awk", "-f" });
    names_list.addFileArg(aosp.path(b, "external/libcap/generate_cap_names_list.awk"));
    names_list.addFileArg(aosp.path(b, "external/libcap/libcap/include/uapi/linux/capability.h"));
    const list_dir = b.addWriteFiles();
    _ = list_dir.addCopyFile(names_list.captureStdOut(.{}), "cap_names.list.h");
    const makenames = b.addExecutable(.{ .name = "libcap-makenames", .root_module = b.createModule(.{ .target = b.graph.host, .optimize = .ReleaseSafe, .link_libc = true }) });
    makenames.root_module.addCSourceFile(.{ .file = aosp.path(b, "external/libcap/libcap/_makenames.c"), .flags = &.{ "-Wno-unused-parameter", "-Wno-unused-result" } });
    makenames.root_module.addIncludePath(list_dir.getDirectory());
    makenames.root_module.addIncludePath(aosp.path(b, "external/libcap/libcap/include"));
    _ = headers.addCopyFile(b.addRunArtifact(makenames).captureStdOut(.{}), "cap_names.h");
    const rust = input_generated.rust.?;
    // Rust AIDL crates for the inputflinger crate: the frozen common types and
    // the InputFlinger bridge (ndk_aidl groups 1 and 0).
    for ([_]struct { group: usize, name: []const u8, imports: []const []const u8 }{
        .{ .group = 1, .name = "android.hardware.input.common-V1-rust", .imports = &.{} },
        .{ .group = 0, .name = "com.android.server.inputflinger-rust", .imports = &.{"android_hardware_input_common"} },
    }) |crate| {
        const group = ndk_aidl[crate.group];
        const tree = b.addWriteFiles();
        var files: std.ArrayList(std.Build.LazyPath) = .empty;
        for (group.files) |file| {
            const rust_aidl = b.addRunArtifact(aidl_tool);
            rust_aidl.addArg("--lang=rust");
            if (group.hash) |hash| rust_aidl.addArgs(&.{ "--structured", "--stability=vintf", "--version=1", b.fmt("--hash={s}", .{hash}) });
            for (ndk_aidl) |include| rust_aidl.addPrefixedDirectoryArg("-I", aosp.path(b, include.root));
            rust_aidl.addArg("-o");
            const output = rust_aidl.addOutputDirectoryArg("rust-aidl");
            rust_aidl.addFileArg(aosp.path(b, b.fmt("{s}/{s}", .{ group.root, file })));
            const rs = b.fmt("{s}.rs", .{file[0 .. file.len - ".aidl".len]});
            files.append(b.allocator, tree.addCopyFile(output.path(b, rs), rs)) catch @panic("oom");
        }
        const glue = b.addSystemCommand(&.{"python3"});
        glue.addFileArg(aosp.path(b, "system/tools/aidl/build/aidl_rust_glue.py"));
        const lib = glue.addOutputFileArg("lib.rs");
        glue.addDirectoryArg(tree.getDirectory());
        for (files.items) |file| glue.addFileArg(file);
        for (crate.imports) |name| glue.addArgs(&.{ "-I", name });
        rust.addGenerated(crate.name, lib);
    }
    const cxxbridge = rust.build("cxxbridge").file;
    for ([_]bool{ true, false }) |header| {
        const bridge = @import("command.zig").create(b);
        bridge.addFileArg(cxxbridge);
        bridge.addFileArg(aosp.path(b, "frameworks/native/services/inputflinger/rust/lib.rs"));
        if (header) bridge.addArg("--header");
        const output = bridge.run.captureStdOut(.{});
        if (header) {
            _ = headers.addCopyFile(output, "inputflinger_bootstrap.rs.h");
        } else {
            _ = sources.addCopyFile(output, "inputflinger_rs_bootstrap_cxx_generated.cc");
        }
    }
    return .{ .headers = headers.getDirectory(), .sources = sources.getDirectory(), .rust = rust.build("libinputflinger_rs").file };
}

const gestures_sources = [_][]const u8{
    "accel_filter_interpreter.cc",             "activity_log.cc",                    "box_filter_interpreter.cc",
    "click_wiggle_filter_interpreter.cc",      "file_util.cc",                       "filter_interpreter.cc",
    "finger_merge_filter_interpreter.cc",      "finger_metrics.cc",                  "fling_stop_filter_interpreter.cc",
    "gestures.cc",                             "haptic_button_generator_filter_interpreter.cc", "iir_filter_interpreter.cc",
    "immediate_interpreter.cc",                "integral_gesture_filter_interpreter.cc", "interpreter.cc",
    "logging_filter_interpreter.cc",           "lookahead_filter_interpreter.cc",    "metrics_filter_interpreter.cc",
    "mouse_interpreter.cc",                    "multitouch_mouse_interpreter.cc",    "non_linearity_filter_interpreter.cc",
    "palm_classifying_filter_interpreter.cc",  "prop_registry.cc",                   "scaling_filter_interpreter.cc",
    "sensor_jump_filter_interpreter.cc",       "split_correcting_filter_interpreter.cc", "stationary_wiggle_filter_interpreter.cc",
    "string_util.cc",                          "stuck_button_inhibitor_filter_interpreter.cc", "t5r2_correcting_filter_interpreter.cc",
    "timestamp_filter_interpreter.cc",         "trace_marker.cc",                    "tracer.cc",
    "trend_classifying_filter_interpreter.cc",
};

const palm_sources = [_][]const u8{
    "chrome_to_android_compatibility.cc",
    "ui/events/ozone/features.cc",
    "ui/events/ozone/evdev/touch_evdev_types.cc",
    "ui/events/ozone/evdev/touch_filter/neural_stylus_palm_detection_filter.cc",
    "ui/events/ozone/evdev/touch_filter/neural_stylus_palm_detection_filter_model.cc",
    "ui/events/ozone/evdev/touch_filter/neural_stylus_palm_detection_filter_util.cc",
    "ui/events/ozone/evdev/touch_filter/palm_detection_filter.cc",
    "ui/events/ozone/evdev/touch_filter/shared_palm_detection_filter_state.cc",
    "ui/events/ozone/evdev/touch_filter/palm_model/onedevice_train_palm_detection_filter_model.cc",
    "ui/events/ozone/evdev/touch_filter/palm_model/onedevice_train_palm_detection_filter_inference.cc",
    "ui/events/ozone/evdev/touch_filter/palm_model/onedevice_train_palm_detection_filter_inference_beta.cc",
    "ui/events/ozone/evdev/touch_filter/palm_model/onedevice_train_palm_detection_filter_inference_v2.cc",
};

/// libchrome units palm rejection links (its FeatureList, field-trial
/// parameters, JSON, logging, time and gfx geometry), resolved from symbols
/// against libchrome's host source list. The glibc allocator shim is excluded:
/// USE_ALLOCATOR_SHIM is off in libchrome's buildflags, and its dispatch object
/// would interpose malloc for the whole platform library.
const chrome_sources = [_][]const u8{
    "base/at_exit.cc",
    "base/base_switches.cc",
    "base/build_time.cc",
    "base/callback_helpers.cc",
    "base/callback_internal.cc",
    "base/command_line.cc",
    "base/debug/activity_tracker.cc",
    "base/debug/alias.cc",
    "base/debug/debugger_posix.cc",
    "base/debug/stack_trace.cc",
    "base/debug/stack_trace_posix.cc",
    "base/feature_list.cc",
    "base/files/file.cc",
    "base/files/file_enumerator.cc",
    "base/files/file_enumerator_posix.cc",
    "base/files/file_path.cc",
    "base/files/file_path_constants.cc",
    "base/files/file_posix.cc",
    "base/files/file_tracing.cc",
    "base/files/file_util.cc",
    "base/files/file_util_posix.cc",
    "base/files/important_file_writer.cc",
    "base/files/memory_mapped_file.cc",
    "base/files/memory_mapped_file_posix.cc",
    "base/files/scoped_file.cc",
    "base/json/json_parser.cc",
    "base/json/json_reader.cc",
    "base/json/json_string_value_serializer.cc",
    "base/json/json_writer.cc",
    "base/json/string_escape.cc",
    "base/lazy_instance_helpers.cc",
    "base/location.cc",
    "base/logging.cc",
    "base/md5.cc",
    "base/memory/ref_counted.cc",
    "base/memory/shared_memory_handle.cc",
    "base/memory/shared_memory_handle_posix.cc",
    "base/memory/shared_memory_helper.cc",
    "base/memory/shared_memory_posix.cc",
    "base/memory/weak_ptr.cc",
    "base/metrics/bucket_ranges.cc",
    "base/metrics/dummy_histogram.cc",
    "base/metrics/field_trial.cc",
    "base/metrics/field_trial_param_associator.cc",
    "base/metrics/histogram.cc",
    "base/metrics/histogram_base.cc",
    "base/metrics/histogram_functions.cc",
    "base/metrics/histogram_samples.cc",
    "base/metrics/histogram_snapshot_manager.cc",
    "base/metrics/metrics_hashes.cc",
    "base/metrics/persistent_histogram_allocator.cc",
    "base/metrics/persistent_memory_allocator.cc",
    "base/metrics/persistent_sample_map.cc",
    "base/metrics/sample_map.cc",
    "base/metrics/sample_vector.cc",
    "base/metrics/sparse_histogram.cc",
    "base/metrics/statistics_recorder.cc",
    "base/observer_list_threadsafe.cc",
    "base/pickle.cc",
    "base/posix/global_descriptors.cc",
    "base/posix/safe_strerror.cc",
    "base/process/internal_linux.cc",
    "base/process/memory.cc",
    "base/process/memory_linux.cc",
    "base/process/process_handle.cc",
    "base/process/process_handle_linux.cc",
    "base/process/process_handle_posix.cc",
    "base/process/process_metrics_posix.cc",
    "base/process/process_posix.cc",
    "base/rand_util.cc",
    "base/rand_util_posix.cc",
    "base/run_loop.cc",
    "base/sequenced_task_runner.cc",
    "base/strings/string16.cc",
    "base/strings/string_number_conversions.cc",
    "base/strings/string_piece.cc",
    "base/strings/string_split.cc",
    "base/strings/string_util.cc",
    "base/strings/string_util_constants.cc",
    "base/strings/stringprintf.cc",
    "base/strings/sys_string_conversions_posix.cc",
    "base/strings/utf_string_conversion_utils.cc",
    "base/strings/utf_string_conversions.cc",
    "base/synchronization/lock_impl_posix.cc",
    "base/sys_info_posix.cc",
    "base/task_runner.cc",
    "base/third_party/icu/icu_utf.cc",
    "base/third_party/nspr/prtime.cc",
    "base/threading/platform_thread_internal_posix.cc",
    "base/threading/platform_thread_linux.cc",
    "base/threading/platform_thread_posix.cc",
    "base/threading/post_task_and_reply_impl.cc",
    "base/threading/sequenced_task_runner_handle.cc",
    "base/threading/thread_id_name_manager.cc",
    "base/threading/thread_local_storage.cc",
    "base/threading/thread_local_storage_posix.cc",
    "base/threading/thread_task_runner_handle.cc",
    "base/time/time.cc",
    "base/time/time_conversion_posix.cc",
    "base/time/time_exploded_posix.cc",
    "base/time/time_now_posix.cc",
    "base/timer/timer.cc",
    "base/token.cc",
    "base/unguessable_token.cc",
    "base/value_iterators.cc",
    "base/values.cc",
    "base/vlog.cc",
    "ui/gfx/geometry/point_f.cc",
    "ui/gfx/geometry/vector2d_f.cc",
};

/// External libraries whose Soong modules inputflinger links statically.
pub fn buildExternal(b: *std.Build, o: Options, generated: Generated) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    dispatcher.configure(b, mod, o);
    for ([_][]const u8{
        "external/libchrome-gestures/include", "external/libchrome-gestures",
        "external/jsoncpp/include",            "external/libpalmrejection",
        "external/libchrome",                  "system/core/libstats/expresslog/include",
        "external/libtextclassifier/native",   "external/googletest/googletest/include",
    }) |path| mod.addIncludePath(o.aosp.path(b, path));
    mod.addIncludePath(generated.headers);
    const base = input.cppFlags(b);
    var gestures_flags: std.ArrayList([]const u8) = .empty;
    // libchrome-gestures is built with RTTI (rtti: true in its Android.bp).
    for (base) |flag| if (!std.mem.eql(u8, flag, "-fno-rtti")) gestures_flags.append(b.allocator, flag) catch @panic("oom");
    // libchrome-gestures_defaults.
    gestures_flags.appendSlice(b.allocator, &.{ "-std=c++20", "-fno-strict-aliasing", "-Wno-unused-parameter", "-Wno-unused-variable", "-Wno-non-virtual-dtor", "-DVCSID=\"Android\"", "-D__STDC_FORMAT_MACROS=1", "-D_FILE_OFFSET_BITS=64", "-DGESTURES_INTERNAL=1" }) catch @panic("oom");
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "external/libchrome-gestures/src"), .files = &gestures_sources, .flags = gestures_flags.items });
    var chrome_flags: std.ArrayList([]const u8) = .empty;
    chrome_flags.appendSlice(b.allocator, base) catch @panic("oom");
    chrome_flags.appendSlice(b.allocator, &.{ "-std=c++20", "-DDONT_EMBED_BUILD_METADATA", "-Wno-unused-parameter", "-Wno-deprecated-declarations", "-Wno-implicit-fallthrough" }) catch @panic("oom");
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "external/libpalmrejection"), .files = &palm_sources, .flags = chrome_flags.items });
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "external/libchrome"), .files = &chrome_sources, .flags = chrome_flags.items });
    var express_flags: std.ArrayList([]const u8) = .empty;
    express_flags.appendSlice(b.allocator, base) catch @panic("oom");
    express_flags.append(b.allocator, "-DNAMESPACE_FOR_HASH_FUNCTIONS=farmhash") catch @panic("oom");
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "system/core/libstats/expresslog"), .files = &.{ "Counter.cpp", "Histogram.cpp" }, .flags = express_flags.items });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "external/libtextclassifier/native/utils/hash/farmhash.cc"), .flags = express_flags.items });
    mod.addCSourceFile(.{ .file = generated.sources.path(b, "statslog_express.cpp"), .flags = express_flags.items });
    // The libcap units stock EventHub.cpp calls (cap_get_proc, cap_get_flag, cap_free).
    mod.addIncludePath(o.aosp.path(b, "external/libcap/libcap/include"));
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "external/libcap/libcap"), .files = &.{ "cap_alloc.c", "cap_flag.c", "cap_proc.c" }, .flags = &.{ "-std=gnu11", "-D_GNU_SOURCE", "-Wno-pointer-arith", "-Wno-tautological-compare", "-Wno-unused-parameter", "-Wno-unused-result", "-Wno-unused-variable" } });
    const library = b.addLibrary(.{ .name = "inputflinger_external", .root_module = mod });
    b.step("inputflinger-external", "Compile inputflinger's external closure: touchpad gestures, palm rejection, expresslog").dependOn(&library.step);
    return library;
}

const reader_sources = [_][]const u8{
    // libinputreader_sources. Stock EventHub.cpp is compiled for the axis and
    // logging helpers the mappers share; HostEventHub is the device source.
    "EventHub.cpp",
    "InputDevice.cpp",                                     "InputReader.cpp",
    "Macros.cpp",                                          "TouchVideoDevice.cpp",
    "controller/PeripheralController.cpp",                 "mapper/CapturedTouchpadEventConverter.cpp",
    "mapper/CursorInputMapper.cpp",                        "mapper/ExternalStylusInputMapper.cpp",
    "mapper/InputMapper.cpp",                              "mapper/JoystickInputMapper.cpp",
    "mapper/KeyboardInputMapper.cpp",                      "mapper/MultiTouchInputMapper.cpp",
    "mapper/RotaryEncoderInputMapper.cpp",                 "mapper/SensorInputMapper.cpp",
    "mapper/SingleTouchInputMapper.cpp",                   "mapper/SlopController.cpp",
    "mapper/SwitchInputMapper.cpp",                        "mapper/TouchCursorInputMapperCommon.cpp",
    "mapper/TouchInputMapper.cpp",                         "mapper/TouchpadInputMapper.cpp",
    "mapper/VibratorInputMapper.cpp",                      "mapper/accumulator/CursorButtonAccumulator.cpp",
    "mapper/accumulator/CursorScrollAccumulator.cpp",      "mapper/accumulator/HidUsageAccumulator.cpp",
    "mapper/accumulator/MultiTouchMotionAccumulator.cpp",  "mapper/accumulator/SingleTouchMotionAccumulator.cpp",
    "mapper/accumulator/TouchButtonAccumulator.cpp",       "mapper/gestures/GestureConverter.cpp",
    "mapper/gestures/GesturesLogcatAdapter.cpp",           "mapper/gestures/HardwareProperties.cpp",
    "mapper/gestures/HardwareStateConverter.cpp",          "mapper/gestures/Logging.cpp",
    "mapper/gestures/PropertyProvider.cpp",                "mapper/gestures/TimerProvider.cpp",
};

const flinger_sources = [_][]const u8{
    // libinputflinger_sources, InputManager.cpp, and the libinputflinger_base
    // sources the dispatcher archive does not already carry.
    "InputCommonConverter.cpp",  "InputDeviceMetricsCollector.cpp", "InputFilter.cpp",
    "InputFilterCallbacks.cpp",  "InputProcessor.cpp",              "PointerChoreographer.cpp",
    "PreferStylusOverTouchBlocker.cpp", "UnwantedInteractionBlocker.cpp", "InputManager.cpp",
    "InputReaderBase.cpp",       "VibrationElement.cpp",
};

pub fn build(b: *std.Build, o: Options, generated: Generated) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    dispatcher.configure(b, mod, o);
    for ([_][]const u8{
        "frameworks/native/services/inputflinger/reader",        "frameworks/native/services/inputflinger/reader/controller",
        "frameworks/native/services/inputflinger/reader/mapper", "frameworks/native/services/inputflinger/reader/mapper/accumulator",
        "frameworks/native/services/inputflinger/reader/mapper/gestures",
        "external/libchrome-gestures",                           "external/libchrome-gestures/include",
        "external/jsoncpp/include",                              "frameworks/native/services/batteryservice/include",
        "external/libpalmrejection",                             "external/libchrome",
        "system/core/libstats/expresslog/include",               "external/googletest/googletest/include",
        "frameworks/native/services/inputflinger/rust/ffi",
    }) |path| mod.addIncludePath(o.aosp.path(b, path));
    mod.addIncludePath(generated.headers);
    var flags: std.ArrayList([]const u8) = .empty;
    flags.appendSlice(b.allocator, input.cppFlags(b)) catch @panic("oom");
    flags.appendSlice(b.allocator, &.{ "-DARTZIG_HOST_GRAPHICS", "-Wno-inconsistent-missing-override" }) catch @panic("oom");
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "frameworks/native/services/inputflinger/reader"), .files = &reader_sources, .flags = flags.items });
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "frameworks/native/services/inputflinger"), .files = &flinger_sources, .flags = flags.items });
    mod.addCSourceFiles(.{ .root = generated.sources, .files = &.{ "InputProperties.sysprop.cpp", "android_companion_virtualdevice_flags.cc", "inputflinger_rs_bootstrap_cxx_generated.cc" }, .flags = flags.items });
    for (ndk_aidl) |group| for (group.files) |file|
        mod.addCSourceFile(.{ .file = generated.sources.path(b, b.fmt("{s}.cpp", .{file[0 .. file.len - ".aidl".len]})), .flags = flags.items });
    // The reader's device source and factory for a hosted session.
    mod.addCSourceFiles(.{ .root = b.path("src/native/input"), .files = &.{ "HostEventHub.cpp", "HostInputReaderFactory.cpp", "HostInputDispatcherFactory.cpp" }, .flags = flags.items });
    // libinput units the reader uses beyond the transport and dispatcher closure.
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "frameworks/native/libs/input"), .files = &.{ "AccelerationCurve.cpp", "KeyboardClassifier.cpp", "VelocityControl.cpp" }, .flags = flags.items });
    mod.addIncludePath(o.aosp.path(b, "external/libcap/libcap/include"));
    const library = b.addLibrary(.{ .name = "inputflinger", .root_module = mod });
    b.step("inputflinger", "Compile stock InputReader, PointerChoreographer, InputProcessor, blockers, InputFilter and InputManager").dependOn(&library.step);
    return library;
}
