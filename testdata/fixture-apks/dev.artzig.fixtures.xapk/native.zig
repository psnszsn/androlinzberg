//! Dependency-free JNI library carried only by the XAPK's feature split.
export fn Java_dev_artzig_fixtures_windows_SplitFixtureActivity_nativeValue(_: ?*anyopaque, _: ?*anyopaque) callconv(.c) i32 {
    return 73;
}
