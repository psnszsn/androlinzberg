package dev.artzig.probe;

import dev.artzig.probe.Kind;
import dev.artzig.probe.Point;

/** One field of every marshalling category the Zig backend supports. */
parcelable Shape {
    const int VERSION = 3;
    const String UNIT = "px";

    Kind kind = Kind.SQUARE;
    String name = "shape";
    @nullable String label;
    Point origin;
    Point[] vertices;
    long[] weights;
    byte[] blob;
    int[3] rgb = {1, 2, 3};
    byte[4] tag;
    double scale = 1.5;
    boolean visible = true;
    char glyph = 'z';
    @nullable Point anchor;
    @nullable IBinder token;
    List<String> tags;
    @nullable(heap=true) Shape child;
}
