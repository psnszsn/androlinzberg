//! Bounded observations of actual compositor presentation. Neither timestamps
//! nor discarded feedback release a buffer or stand in for a frame callback.
const std = @import("std");
const client = @import("wayland").client;
const wl = client.wl;
const wp = client.wp;
const Self = @This();

pub const max_pending = 4;
pub const Sample = struct {
    submission: u64,
    configuration: i64,
    frame: u64,
    clock_id: u32,
    seconds: u64,
    nanoseconds: u32,
    refresh_ns: u32,
    sequence: u64,
    flags: u32,
    output_global: ?u32,
};
pub const Context = struct {
    clock_id: u32,
    configuration: i64,
    frame: u64,
    output_context: *anyopaque,
    output_global: *const fn (*anyopaque, *wl.Output) ?u32,
    failure: *?anyerror,
};
const Pending = struct {
    owner: *Self = undefined,
    proxy: ?*wp.PresentationFeedback = null,
    submission: u64 = 0,
    context: Context = undefined,
    output_global: ?u32 = null,
};
pending: [max_pending]Pending = @splat(.{}),
submitted: u64 = 0,
observed: u64 = 0,
discarded: u64 = 0,
unobserved: u64 = 0,
last: ?Sample = null,

pub fn request(self: *Self, manager: *wp.Presentation, surface: *wl.Surface, context: Context) !void {
    if (self.submitted == std.math.maxInt(u64)) return error.PresentationSequenceExhausted;
    const slot = &self.pending[self.submitted % max_pending];
    if (slot.proxy) |proxy| {
        // A covered surface can retain feedback indefinitely. Stop observing
        // the oldest record without claiming its content was discarded.
        proxy.destroy();
        slot.proxy = null;
        self.unobserved += 1;
    }
    const proxy = try manager.feedback(surface);
    self.submitted += 1;
    slot.* = .{ .owner = self, .proxy = proxy, .submission = self.submitted, .context = context };
    proxy.setListener(*Pending, listener, slot);
}

pub fn deinit(self: *Self) void {
    for (&self.pending) |*slot| if (slot.proxy) |proxy| {
        proxy.destroy();
        slot.proxy = null;
        self.unobserved += 1;
    };
}

fn listener(proxy: *wp.PresentationFeedback, event: wp.PresentationFeedback.Event, pending: *Pending) void {
    if (pending.proxy != proxy) return;
    const self = pending.owner;
    switch (event) {
        .sync_output => |sync| pending.output_global = if (sync.output) |output|
            pending.context.output_global(pending.context.output_context, output)
        else
            null,
        .discarded => {
            self.discarded += 1;
            proxy.destroy();
            pending.proxy = null;
        },
        .presented => |value| {
            proxy.destroy();
            pending.proxy = null;
            if (value.tv_nsec >= std.time.ns_per_s) {
                pending.context.failure.* = error.InvalidPresentationTimestamp;
                self.unobserved += 1;
                return;
            }
            const sample: Sample = .{
                .submission = pending.submission,
                .configuration = pending.context.configuration,
                .frame = pending.context.frame,
                .clock_id = pending.context.clock_id,
                .seconds = (@as(u64, value.tv_sec_hi) << 32) | value.tv_sec_lo,
                .nanoseconds = value.tv_nsec,
                .refresh_ns = value.refresh,
                .sequence = (@as(u64, value.seq_hi) << 32) | value.seq_lo,
                .flags = @bitCast(value.flags),
                .output_global = pending.output_global,
            };
            self.observed += 1;
            if (self.last == null or sample.submission > self.last.?.submission) self.last = sample;
            if (traceEnabled()) std.log.info("surface-presentation submission={d} configuration={d} frame={d} clock={d} seconds={d} nanoseconds={d} refresh_ns={d} sequence={d} flags={d} output={any}", .{ sample.submission, sample.configuration, sample.frame, sample.clock_id, sample.seconds, sample.nanoseconds, sample.refresh_ns, sample.sequence, sample.flags, sample.output_global });
        },
    }
}

// Per-frame trace for the native surface tests (AZK_SURFACE_TRACE=1); off by
// default because it prints for every submitted frame.
extern "c" fn getenv([*:0]const u8) ?[*:0]const u8;
var trace_state: enum { unknown, off, on } = .unknown;
fn traceEnabled() bool {
    if (trace_state == .unknown) trace_state = if (getenv("AZK_SURFACE_TRACE") != null) .on else .off;
    return trace_state == .on;
}
