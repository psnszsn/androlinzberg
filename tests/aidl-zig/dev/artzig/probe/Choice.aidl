package dev.artzig.probe;

import dev.artzig.probe.Kind;
import dev.artzig.probe.Point;

union Choice {
    int number = 5;
    String text;
    Point point;
    Kind[] kinds;
}
