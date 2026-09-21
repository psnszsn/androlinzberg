package dev.artzig.testing;

/** One entry of the missing-functionality journal. `kind` is "unimplemented"
 * for an explicit hosted-framework stub (owner/member name the seam) or
 * "runtime_exception" for a linkage, class-loading or `Stub!` failure. */
parcelable RuntimeIssue {
    long sequence;
    long monotonicMs;
    String kind;
    String owner;
    String member;
    String detail;
    String thread;
    int pid;
    String process;
}
