package dev.artzig.probe;

/** ROUND repeats CIRCLE: Zig enum tags are unique, so it becomes a constant. */
@Backing(type="int")
enum Kind {
    NONE = 0,
    CIRCLE = 1,
    SQUARE = 2,
    ROUND = 1,
}
