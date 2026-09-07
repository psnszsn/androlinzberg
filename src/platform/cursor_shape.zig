//! Android built-in pointer styles represented by cursor-shape-v1.
/// android.view.PointerIcon.TYPE_* -> wp_cursor_shape_device_v1.shape
pub fn fromAndroid(icon: i32) ?u32 {
    return switch (icon) {
        1001 => 2, // CONTEXT_MENU
        1002 => 4, // HAND -> pointer
        1003 => 3, // HELP
        1004 => 6, // WAIT
        1006 => 7, // CELL
        1007 => 8, // CROSSHAIR
        1008 => 9, // TEXT
        1009 => 10, // VERTICAL_TEXT
        1010 => 11, // ALIAS
        1011 => 12, // COPY
        1012 => 14, // NO_DROP
        1013 => 32, // ALL_SCROLL
        1014 => 26, // HORIZONTAL_DOUBLE_ARROW -> ew_resize
        1015 => 27, // VERTICAL_DOUBLE_ARROW -> ns_resize
        1016 => 28, // TOP_RIGHT_DIAGONAL_DOUBLE_ARROW -> nesw_resize
        1017 => 29, // TOP_LEFT_DIAGONAL_DOUBLE_ARROW -> nwse_resize
        1018 => 33, // ZOOM_IN
        1019 => 34, // ZOOM_OUT
        1020 => 16, // GRAB
        1021 => 17, // GRABBING
        1, 1000 => 1, // NOT_SPECIFIED / ARROW
        1022 => 9, // HANDWRITING -> compositor text cursor
        else => null, // Hidden/custom cursors require a separate path.
    };
}
