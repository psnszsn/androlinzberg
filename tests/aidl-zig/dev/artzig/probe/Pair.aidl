package dev.artzig.probe;

import dev.artzig.probe.Point;

/** A generic parcelable: Zig generates a function of its arguments' codecs.
 * AIDL cannot declare a field of a parameter's type, so they are phantom. */
parcelable Pair<A, B> {
    String first;
    Point second;
}
