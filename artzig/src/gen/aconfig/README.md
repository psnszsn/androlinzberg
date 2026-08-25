Output of `aconfig create-cpp-lib --mode production` for `art/build/flags/art-flags.aconfig`
with the `trunk_staging` release values (build/release/aconfig/trunk_staging/com.android.art.flags/*).
Vendored because `aconfig` is a Rust tool that needs the whole Soong prebuilt toolchain; regenerate
from a Soong build (`out/soong/.intermediates/art/build/flags/art-aconfig-flags-lib/*/gen`) when
art-flags.aconfig or the release values change.
