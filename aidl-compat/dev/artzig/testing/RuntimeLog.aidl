package dev.artzig.testing;

/** One Android log record of a session process. */
parcelable RuntimeLog {
    long sequence;
    long monotonicMs;
    int priority;
    String tag;
    String message;
    String thread;
    int pid;
    String process;
}
