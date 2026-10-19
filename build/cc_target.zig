const std = @import("std");

/// A target triple does not encode the CPU. Without -mcpu, manual Zig C/C++
/// commands can use the build host CPU even when the product selects a baseline.
/// Call before per-source flags so runtime-dispatched ISA variants retain their
/// explicit -march overrides.
pub fn add(b: *std.Build, run: *std.Build.Step.Run, target: std.Build.ResolvedTarget) void {
    run.addArgs(&.{
        "-target",
        target.query.zigTriple(b.allocator) catch @panic("oom"),
        b.fmt("-mcpu={s}", .{target.query.serializeCpuAlloc(b.allocator) catch @panic("oom")}),
    });
}

/// Check the compiler's effective ISA, including deliberate per-source variants.
pub fn addTests(b: *std.Build, step: *std.Build.Step) void {
    const source = b.addWriteFiles().add("cpu.c",
        \\#if defined(__AVX512F__) != EXPECT_AVX512
        \\#error unexpected AVX-512 target
        \\#endif
        \\#if defined(__AVX2__) != EXPECT_AVX2
        \\#error unexpected AVX2 target
        \\#endif
        \\int cpu_target_probe(void) { return 0; }
    );
    inline for (.{
        .{ "baseline", false, false, false },
        .{ "x86_64_v3", true, false, false },
        .{ "x86_64_v3", true, true, true },
    }) |case| {
        const run = b.addSystemCommand(&.{ b.graph.zig_exe, "cc", "-c" });
        add(b, run, b.resolveTargetQuery(std.Target.Query.parse(.{
            .arch_os_abi = "x86_64-linux-gnu",
            .cpu_features = case[0],
        }) catch @panic("invalid test target")));
        if (case[3]) run.addArg("-march=skylake_avx512");
        run.addArgs(&.{
            b.fmt("-DEXPECT_AVX2={d}", .{@intFromBool(case[1])}),
            b.fmt("-DEXPECT_AVX512={d}", .{@intFromBool(case[2])}),
        });
        run.addFileArg(source);
        run.addArg("-o");
        _ = run.addOutputFileArg("cpu.o");
        step.dependOn(&run.step);
    }
}
