//! artzig — embed the Android Runtime (ART) in a Zig program.
//!
//! `libart.so` and friends come from the relocatable runtime tree assembled by
//! `zig build dist`; this module locates the tree, sets up the environment ART
//! expects, dlopen()s libart and creates the VM through the JNI invocation API.

const std = @import("std");
pub const jni = @import("jni_c");

pub const JavaVM = jni.JavaVM;
pub const JNIEnv = jni.JNIEnv;
pub const jclass = jni.jclass;
pub const jobject = jni.jobject;
pub const jstring = jni.jstring;
pub const jmethodID = jni.jmethodID;
pub const jint = jni.jint;

pub const Error = error{
    RuntimeTreeNotFound,
    HostCaBundleNotFound,
    LibartLoadFailed,
    SymbolNotFound,
    VmCreationFailed,
    JavaException,
    ClassNotFound,
    MethodNotFound,
    OutOfMemory,
};

/// Boot classpath in the relocatable runtime tree (relative to the tree root)
/// and the canonical locations the boot image was compiled with.
pub const boot_jars = [_][]const u8{
    "com.android.art/javalib/core-oj.jar",
    "com.android.art/javalib/core-libart.jar",
    "com.android.art/javalib/okhttp.jar",
    "com.android.art/javalib/bouncycastle.jar",
    "com.android.art/javalib/apache-xml.jar",
    "com.android.i18n/javalib/core-icu4j.jar",
    "com.android.conscrypt/javalib/conscrypt.jar",
};

/// Pluggable native-library loader (see AOSP art/runtime/jni/artzig_native_loader.h).
/// Install with `Runtime.setNativeLoader` before `create`, e.g. to route
/// System.loadLibrary() through bionic_translation's linker.
pub const NativeLoader = extern struct {
    dlopen: *const fn (path: ?[*:0]const u8, flags: c_int, library_path: ?[*:0]const u8) callconv(.c) ?*anyopaque,
    dlsym: *const fn (handle: ?*anyopaque, symbol: [*:0]const u8) callconv(.c) ?*anyopaque,
    dlerror: *const fn () callconv(.c) ?[*:0]const u8,
};

/// Host-only native loader used when an embedder does not need the Android
/// compatibility linker. Conscrypt statically embeds BoringSSL, so its DSO
/// must bind its own SSL symbols even when another host library has already
/// loaded ABI-incompatible OpenSSL symbols into the global namespace.
pub const HostLoader = struct {
    var host_lib_dir: ?[:0]const u8 = null;
    var host_lib_dir_buf: [4096]u8 = undefined;

    pub const loader: NativeLoader = .{ .dlopen = dlopen, .dlsym = dlsym, .dlerror = dlerror };

    pub fn init(root: []const u8) Error!*const NativeLoader {
        const dir = std.fmt.bufPrint(host_lib_dir_buf[0 .. host_lib_dir_buf.len - 1], "{s}/lib64", .{root}) catch
            return Error.OutOfMemory;
        host_lib_dir_buf[dir.len] = 0;
        host_lib_dir = host_lib_dir_buf[0..dir.len :0];
        return &loader;
    }

    fn dlopen(path: ?[*:0]const u8, flags: c_int, library_path: ?[*:0]const u8) callconv(.c) ?*anyopaque {
        const p = path orelse return host.dlopen(null, flags);
        const name = std.mem.span(p);
        const host_flags = flags | if (std.mem.endsWith(u8, name, "libjavacrypto.so"))
            @as(c_int, 0x8) // RTLD_DEEPBIND
        else
            0;
        if (host.dlopen(p, host_flags)) |handle| return handle;
        if (std.mem.indexOfScalar(u8, name, '/') != null) return null;

        if (library_path) |paths| {
            var it = std.mem.splitScalar(u8, std.mem.span(paths), ':');
            while (it.next()) |dir| {
                if (dir.len == 0) continue;
                if (openFrom(dir, name, host_flags)) |handle| return handle;
            }
        }
        if (host_lib_dir) |dir| return openFrom(dir, name, host_flags);
        return null;
    }

    fn openFrom(dir: []const u8, name: []const u8, flags: c_int) ?*anyopaque {
        var full_buf: [4096]u8 = undefined;
        const full = std.fmt.bufPrint(full_buf[0 .. full_buf.len - 1], "{s}/{s}", .{ dir, name }) catch return null;
        full_buf[full.len] = 0;
        return host.dlopen(full_buf[0..full.len :0].ptr, flags);
    }

    fn dlsym(handle: ?*anyopaque, symbol: [*:0]const u8) callconv(.c) ?*anyopaque {
        return host.dlsym(handle, symbol);
    }

    fn dlerror() callconv(.c) ?[*:0]const u8 {
        return host.dlerror();
    }

    const host = struct {
        extern "c" fn dlopen(path: ?[*:0]const u8, flags: c_int) ?*anyopaque;
        extern "c" fn dlsym(handle: ?*anyopaque, symbol: [*:0]const u8) ?*anyopaque;
        extern "c" fn dlerror() ?[*:0]const u8;
    };
};

/// NativeLoader backed by bionic_translation's shim linker (libdl_bio.so from the
/// runtime tree): System.loadLibrary() can then load Android (bionic-linked)
/// native libraries, falling back to the host libc for host libraries.
pub const BionicLoader = struct {
    var lib: ?std.DynLib = null;
    var guest_dir: ?[*:0]const u8 = null;
    var host_lib_dir: ?[:0]const u8 = null;
    var host_lib_dir_buf: [4096]u8 = undefined;
    threadlocal var last_was_bionic = false;
    var dlopen_fn: *const fn (?[*:0]const u8, c_int) callconv(.c) ?*anyopaque = undefined;
    var dlsym_fn: *const fn (?*anyopaque, [*:0]const u8) callconv(.c) ?*anyopaque = undefined;
    var dlerror_fn: *const fn () callconv(.c) ?[*:0]const u8 = undefined;

    pub const loader: NativeLoader = .{ .dlopen = dlopen, .dlsym = dlsym, .dlerror = dlerror };

    /// Load libdl_bio.so from `<root>/lib64` and return the loader to put in
    /// `Options.native_loader`.
    pub fn init(a: std.mem.Allocator, root: []const u8, overlay_dir: ?[]const u8) Error!*const NativeLoader {
        if (lib == null) {
            var lib_dir_buf: [4096]u8 = undefined;
            const lib_dir = overlay_dir orelse std.fmt.bufPrint(&lib_dir_buf, "{s}/lib64", .{root}) catch return Error.OutOfMemory;
            const p = try std.fs.path.joinZ(a, &.{ lib_dir, "libdl_bio.so" });
            defer a.free(p);
            var l = std.DynLib.openZ(p) catch return Error.LibartLoadFailed;
            errdefer l.close();
            dlopen_fn = l.lookup(@TypeOf(dlopen_fn), "bionic_dlopen") orelse return Error.SymbolNotFound;
            dlsym_fn = l.lookup(@TypeOf(dlsym_fn), "bionic_dlsym") orelse return Error.SymbolNotFound;
            dlerror_fn = l.lookup(@TypeOf(dlerror_fn), "bionic_dlerror") orelse return Error.SymbolNotFound;
            // bionic_translation normally requires a helper linked into the
            // main executable to copy glibc's debugger rendezvous pointer.
            // An embedder loads libdl_bio dynamically, so initialize the same
            // exported slot here before the first guest library is mapped.
            const debug_slot = l.lookup(*?*anyopaque, "_r_debug_ptr") orelse return Error.SymbolNotFound;
            debug_slot.* = host.dlsym(null, "_r_debug") orelse return Error.SymbolNotFound;
            guest_dir = host.getenv("ARTZIG_NATIVE_LIB_DIR");
            const persistent_dir = std.fmt.bufPrint(host_lib_dir_buf[0 .. host_lib_dir_buf.len - 1], "{s}/lib64", .{root}) catch return Error.OutOfMemory;
            host_lib_dir_buf[persistent_dir.len] = 0;
            host_lib_dir = host_lib_dir_buf[0..persistent_dir.len :0];
            // These are glibc DSOs exporting bionic_* ABI adapters. Keep them
            // in the host link-map (and globally visible) rather than letting
            // the guest linker parse them as if they came from an APK.
            inline for (.{ "libpthread_bio.so", "libc_bio.so", "libstdc++_bio.so" }) |name| {
                const shim = std.fs.path.joinZ(a, &.{ lib_dir, name }) catch return Error.OutOfMemory;
                defer a.free(shim);
                if (host.dlopen(shim.ptr, 2 | 0x100) == null) return Error.LibartLoadFailed; // RTLD_NOW|RTLD_GLOBAL
            }
            lib = l;
        }
        return &loader;
    }

    fn dlopen(path: ?[*:0]const u8, flags: c_int, library_path: ?[*:0]const u8) callconv(.c) ?*anyopaque {
        // ART itself loads host/glibc JNI libraries (libicu_jni, libjavacore,
        // etc.). Only APK libraries extracted into guest_dir are bionic ELF.
        const guest = guest_dir;
        const is_guest = if (guest) |dir|
            (if (path) |p| std.mem.startsWith(u8, std.mem.span(p), std.mem.span(dir)) else false) or
                (if (library_path) |p| std.mem.indexOf(u8, std.mem.span(p), std.mem.span(dir)) != null else false)
        else
            false;
        last_was_bionic = is_guest;
        if (is_guest) return dlopen_fn(path, flags);
        // Conscrypt statically embeds BoringSSL, whose public symbol names
        // overlap with a host OpenSSL already present in many processes.
        // Prefer this DSO's own definitions or its SSL objects can call into
        // OpenSSL with ABI-incompatible BoringSSL data structures.
        const host_flags = flags | if (path) |p|
            (if (std.mem.endsWith(u8, std.mem.span(p), "libjavacrypto.so")) @as(c_int, 0x8) else 0) // RTLD_DEEPBIND
        else
            0;
        if (host.dlopen(path, host_flags)) |handle| return handle;
        // ART asks for its built-in JNI libraries by soname. Since the runtime
        // is relocatable, resolve a failed bare name against <root>/lib64.
        if (path) |p| if (host_lib_dir) |dir| {
            const name = std.mem.span(p);
            if (std.mem.indexOfScalar(u8, name, '/') == null) {
                var full_buf: [4096]u8 = undefined;
                const full = std.fmt.bufPrint(full_buf[0 .. full_buf.len - 1], "{s}/{s}", .{ dir, name }) catch return null;
                full_buf[full.len] = 0;
                return host.dlopen(full_buf[0..full.len :0].ptr, host_flags);
            }
        };
        return null;
    }
    fn dlsym(handle: ?*anyopaque, symbol: [*:0]const u8) callconv(.c) ?*anyopaque {
        // bionic_dlsym recognizes foreign handles and delegates to glibc, while
        // also performing ABI wrapper lookup for guest handles. Its direct
        // guest-handle lookup traverses the handle's DT_NEEDED scope, matching
        // Android's dlsym semantics without accidentally selecting the same
        // symbol name from an unrelated JNI library.
        last_was_bionic = true;
        return dlsym_fn(handle, symbol);
    }
    fn dlerror() callconv(.c) ?[*:0]const u8 {
        return if (last_was_bionic) dlerror_fn() else host.dlerror();
    }

    const host = struct {
        extern "c" fn dlopen(path: ?[*:0]const u8, flags: c_int) ?*anyopaque;
        extern "c" fn dlsym(handle: ?*anyopaque, symbol: [*:0]const u8) ?*anyopaque;
        extern "c" fn dlerror() ?[*:0]const u8;
        extern "c" fn getenv(name: [*:0]const u8) ?[*:0]const u8;
    };
};

pub const Options = struct {
    /// Optional loader for native libraries; must outlive the runtime.
    native_loader: ?*const NativeLoader = null,
    /// Route System.loadLibrary through bionic_translation's linker (implies
    /// loading libdl_bio.so from the runtime tree). Overrides `native_loader`.
    use_bionic_loader: bool = false,
    /// Root of the runtime tree (dir containing bin/, lib64/, apex/). If null,
    /// `locateTree` is used.
    root: ?[]const u8 = null,
    /// Optional libart.so overlay. Other runtime libraries and data continue
    /// to resolve from `root`.
    libart_path: ?[]const u8 = null,
    /// Directory containing libdl_bio.so and its ABI adapter libraries.
    bionic_lib_dir: ?[]const u8 = null,
    /// ANDROID_DATA: where dalvik-cache/ lives. Default: $XDG_CACHE_HOME/artzig.
    data_dir: ?[]const u8 = null,
    /// Host PEM CA bundle. If null, environment overrides and standard Linux
    /// locations are searched. The file is read directly and is never copied.
    ca_bundle: ?[]const u8 = null,
    /// -Djava.class.path
    classpath: []const []const u8 = &.{},
    /// Directories searched by System.loadLibrary (-Djava.library.path).
    library_path: []const []const u8 = &.{},
    /// Extra -X / -D options passed verbatim.
    extra_options: []const []const u8 = &.{},
    check_jni: bool = false,
    /// Use the precompiled boot image (fast startup). Disable to run imageless
    /// (e.g. after changing the boot jars without regenerating the image).
    use_boot_image: bool = true,
};

pub const Runtime = struct {
    vm: *JavaVM,
    env: *JNIEnv,
    lib: std.DynLib,
    root: []const u8,
    arena: std.heap.ArenaAllocator,

    const CreateFn = *const fn (**JavaVM, *?*anyopaque, *anyopaque) callconv(.c) jint;

    pub fn create(gpa: std.mem.Allocator, io: std.Io, environ: std.process.Environ, opts: Options) Error!Runtime {
        var arena = std.heap.ArenaAllocator.init(gpa);
        errdefer arena.deinit();
        const a = arena.allocator();

        const root = if (opts.root) |r| try a.dupe(u8, r) else try locateTree(a, io, environ);
        const data_dir = opts.data_dir orelse try defaultDataDir(a, environ);
        const ca_bundle = try locateCaBundle(a, io, environ, opts.ca_bundle);

        // Environment ART's path resolution relies on (see libartbase/base/file_utils.cc).
        try setenv(a, "ANDROID_ROOT", root);
        try setenv(a, "ANDROID_ART_ROOT", try std.fs.path.join(a, &.{ root, "apex/com.android.art" }));
        try setenv(a, "ANDROID_I18N_ROOT", try std.fs.path.join(a, &.{ root, "com.android.i18n" }));
        try setenv(a, "ANDROID_TZDATA_ROOT", try std.fs.path.join(a, &.{ root, "com.android.tzdata" }));
        try setenv(a, "ANDROID_DATA", data_dir);
        const cache = try std.fs.path.join(a, &.{ data_dir, "dalvik-cache", isa });
        std.Io.Dir.cwd().createDirPath(io, cache) catch {};

        var options: std.ArrayList([*:0]const u8) = .empty;
        var bcp: std.ArrayList(u8) = .empty;
        var bcpl: std.ArrayList(u8) = .empty;
        for (boot_jars, 0..) |j, i| {
            if (i != 0) {
                try bcp.append(a, ':');
                try bcpl.append(a, ':');
            }
            try bcp.print(a, "{s}/apex/{s}", .{ root, j });
            try bcpl.print(a, "/apex/{s}", .{j});
        }
        try options.append(a, try std.fmt.allocPrintSentinel(a, "-Xbootclasspath:{s}", .{bcp.items}, 0));
        try options.append(a, try std.fmt.allocPrintSentinel(a, "-Xbootclasspath-locations:{s}", .{bcpl.items}, 0));
        if (opts.use_boot_image) {
            try options.append(a, "-Xnorelocate");
            try options.append(a, try std.fmt.allocPrintSentinel(a, "-Ximage:{s}/apex/com.android.art/javalib/boot.art", .{root}, 0));
        } else {
            try options.append(a, "-Xnoimage-dex2oat");
        }
        if (opts.check_jni) try options.append(a, "-Xcheck:jni");
        try options.append(a, try std.fmt.allocPrintSentinel(a, "-Dartzig.ca.bundle={s}", .{ca_bundle}, 0));
        if (opts.classpath.len > 0) {
            const cp = try std.mem.join(a, ":", opts.classpath);
            try options.append(a, try std.fmt.allocPrintSentinel(a, "-Djava.class.path={s}", .{cp}, 0));
        }
        if (opts.library_path.len > 0) {
            const lp = try std.mem.join(a, ":", opts.library_path);
            try options.append(a, try std.fmt.allocPrintSentinel(a, "-Djava.library.path={s}", .{lp}, 0));
        }
        for (opts.extra_options) |o| try options.append(a, try a.dupeSentinel(u8, o, 0));

        const libart = if (opts.libart_path) |p|
            try a.dupeSentinel(u8, p, 0)
        else
            try std.fs.path.joinZ(a, &.{ root, "lib64", "libart.so" });
        var lib = std.DynLib.openZ(libart) catch return Error.LibartLoadFailed;
        errdefer lib.close();
        const create_vm = lib.lookup(CreateFn, "JNI_CreateJavaVM") orelse return Error.SymbolNotFound;
        var native_loader = opts.native_loader;
        if (opts.use_bionic_loader) {
            native_loader = try BionicLoader.init(gpa, root, opts.bionic_lib_dir);
        } else if (native_loader == null) {
            native_loader = try HostLoader.init(root);
        }
        if (native_loader) |nl| {
            const SetLoader = *const fn (?*const NativeLoader) callconv(.c) void;
            const set = lib.lookup(SetLoader, "artzig_set_native_loader") orelse return Error.SymbolNotFound;
            set(nl);
        }

        const vm_opts = try a.alloc(jni.JavaVMOption, options.items.len);
        for (vm_opts, options.items) |*o, s| o.* = .{ .optionString = @constCast(s), .extraInfo = null };
        var args: jni.JavaVMInitArgs = .{
            .version = jni.JNI_VERSION_1_6,
            .nOptions = @intCast(vm_opts.len),
            .options = vm_opts.ptr,
            .ignoreUnrecognized = jni.JNI_FALSE,
        };
        var vm: *JavaVM = undefined;
        var env: ?*anyopaque = null;
        if (create_vm(&vm, &env, &args) != jni.JNI_OK) return Error.VmCreationFailed;

        return .{
            .vm = vm,
            .env = @ptrCast(@alignCast(env.?)),
            .lib = lib,
            .root = root,
            .arena = arena,
        };
    }

    pub fn destroy(self: *Runtime) void {
        _ = self.vm.*.*.DetachCurrentThread.?(self.vm);
        _ = self.vm.*.*.DestroyJavaVM.?(self.vm);
        self.lib.close();
        self.arena.deinit();
    }

    /// Release allocations owned by the launcher when the whole process is
    /// exiting. Android applications commonly leave non-daemon executor
    /// threads alive, so DestroyJavaVM would wait forever; the OS tears down
    /// ART and its dlopen handle immediately after main returns.
    pub fn releaseProcessState(self: *Runtime) void {
        self.arena.deinit();
    }

    pub fn attachCurrentThread(self: *Runtime) Error!*JNIEnv {
        var env: ?*anyopaque = null;
        if (self.vm.*.*.AttachCurrentThread.?(self.vm, &env, null) != jni.JNI_OK) return Error.VmCreationFailed;
        return @ptrCast(@alignCast(env.?));
    }

    /// Run `public static void main(String[])` of `class_name` (dotted or slashed).
    pub fn runMain(self: *Runtime, class_name: []const u8, args: []const [:0]const u8) Error!void {
        const e = Env{ .env = self.env };
        const a = self.arena.allocator();
        const slashed = try a.dupeSentinel(u8, class_name, 0);
        std.mem.replaceScalar(u8, slashed, '.', '/');
        const cls = try e.findClass(slashed);
        const mid = try e.getStaticMethodID(cls, "main", "([Ljava/lang/String;)V");
        const str_cls = try e.findClass("java/lang/String");
        const arr = self.env.*.*.NewObjectArray.?(self.env, @intCast(args.len), str_cls, null) orelse return Error.OutOfMemory;
        for (args, 0..) |arg, i| {
            const s = try e.newString(arg.ptr);
            self.env.*.*.SetObjectArrayElement.?(self.env, arr, @intCast(i), s);
        }
        self.env.*.*.CallStaticVoidMethodA.?(self.env, cls, mid, &[_]jni.jvalue{.{ .l = arr }});
        try e.check();
    }
};

/// Thin helpers over the raw JNIEnv function table. Everything else is
/// reachable through `env.*.*.<Name>.?(env, ...)`.
pub const Env = struct {
    env: *JNIEnv,

    pub fn findClass(self: Env, name: [*:0]const u8) Error!jclass {
        return self.env.*.*.FindClass.?(self.env, name) orelse {
            self.clear();
            return Error.ClassNotFound;
        };
    }
    pub fn getStaticMethodID(self: Env, cls: jclass, name: [*:0]const u8, sig: [*:0]const u8) Error!jmethodID {
        return self.env.*.*.GetStaticMethodID.?(self.env, cls, name, sig) orelse {
            self.clear();
            return Error.MethodNotFound;
        };
    }
    pub fn getMethodID(self: Env, cls: jclass, name: [*:0]const u8, sig: [*:0]const u8) Error!jmethodID {
        return self.env.*.*.GetMethodID.?(self.env, cls, name, sig) orelse {
            self.clear();
            return Error.MethodNotFound;
        };
    }
    pub fn newString(self: Env, s: [*:0]const u8) Error!jstring {
        return self.env.*.*.NewStringUTF.?(self.env, s) orelse Error.OutOfMemory;
    }
    /// Describe and clear a pending exception; returns error.JavaException if there was one.
    pub fn check(self: Env) Error!void {
        if (self.env.*.*.ExceptionCheck.?(self.env) == jni.JNI_TRUE) {
            self.env.*.*.ExceptionDescribe.?(self.env);
            self.env.*.*.ExceptionClear.?(self.env);
            return Error.JavaException;
        }
    }
    pub fn clear(self: Env) void {
        if (self.env.*.*.ExceptionCheck.?(self.env) == jni.JNI_TRUE) {
            self.env.*.*.ExceptionDescribe.?(self.env);
            self.env.*.*.ExceptionClear.?(self.env);
        }
    }
};

pub const isa = "x86_64";

/// Find the runtime tree: $ARTZIG_ROOT, then <exe dir>/art, <exe dir>/../lib/art,
/// <exe dir>/../share/art.
pub fn locateTree(a: std.mem.Allocator, io: std.Io, environ: std.process.Environ) Error![]const u8 {
    if (environ.getPosix("ARTZIG_ROOT")) |r| return a.dupe(u8, r);
    const exe = std.process.executablePathAlloc(io, a) catch return Error.RuntimeTreeNotFound;
    const exe_dir = std.fs.path.dirname(exe) orelse return Error.RuntimeTreeNotFound;
    for ([_][]const u8{ "art", "../lib/art", "../share/art" }) |rel| {
        const cand = try std.fs.path.join(a, &.{ exe_dir, rel });
        const probe = try std.fs.path.join(a, &.{ cand, "lib64", "libart.so" });
        std.Io.Dir.accessAbsolute(io, probe, .{}) catch continue;
        return std.Io.Dir.realPathFileAbsoluteAlloc(io, cand, a) catch cand;
    }
    return Error.RuntimeTreeNotFound;
}

/// Resolve the host trust bundle without copying it into the ART runtime.
/// Explicit configuration is authoritative; an invalid override fails rather
/// than silently selecting a different trust policy.
pub fn locateCaBundle(
    a: std.mem.Allocator,
    io: std.Io,
    environ: std.process.Environ,
    configured: ?[]const u8,
) Error![]const u8 {
    if (configured) |path| return resolveHostFile(a, io, path) orelse Error.HostCaBundleNotFound;
    inline for (.{ "ARTZIG_CA_BUNDLE", "SSL_CERT_FILE", "NIX_SSL_CERT_FILE" }) |name| {
        if (environ.getPosix(name)) |path| {
            return resolveHostFile(a, io, path) orelse Error.HostCaBundleNotFound;
        }
    }
    inline for (.{
        "/etc/ssl/certs/ca-bundle.crt", // NixOS
        "/etc/ssl/certs/ca-certificates.crt", // Debian, Ubuntu
        "/etc/pki/tls/certs/ca-bundle.crt", // Fedora, RHEL
        "/etc/ssl/ca-bundle.pem", // openSUSE
        "/etc/ssl/cert.pem", // Alpine
    }) |path| {
        if (resolveHostFile(a, io, path)) |resolved| return resolved;
    }
    return Error.HostCaBundleNotFound;
}

fn resolveHostFile(a: std.mem.Allocator, io: std.Io, path: []const u8) ?[]const u8 {
    if (std.fs.path.isAbsolute(path)) {
        return std.Io.Dir.realPathFileAbsoluteAlloc(io, path, a) catch null;
    }
    return std.Io.Dir.cwd().realPathFileAlloc(io, path, a) catch null;
}

fn defaultDataDir(a: std.mem.Allocator, environ: std.process.Environ) Error![]const u8 {
    if (environ.getPosix("ANDROID_DATA")) |d| return a.dupe(u8, d);
    if (environ.getPosix("XDG_CACHE_HOME")) |c| return std.fs.path.join(a, &.{ c, "artzig" });
    const home = environ.getPosix("HOME") orelse "/tmp";
    return std.fs.path.join(a, &.{ home, ".cache", "artzig" });
}

const libc = struct {
    extern "c" fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;
};

fn setenv(a: std.mem.Allocator, name: [*:0]const u8, value: []const u8) Error!void {
    const v = try a.dupeSentinel(u8, value, 0);
    _ = libc.setenv(name, v.ptr, 1);
}
