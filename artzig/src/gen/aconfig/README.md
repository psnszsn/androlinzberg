# Generated ART flags

Checked-in output of `aconfig create-cpp-lib --mode production` for the pinned
`art/build/flags/art-flags.aconfig`, using the `trunk_staging` release values
from `build/release/aconfig/trunk_staging/com.android.art.flags/`.

`artzig/build/genrules.zig` supplies these files directly to the ART build.
Regenerate them when the pinned declarations or release values change; the
corresponding Soong output is
`out/soong/.intermediates/art/build/flags/art-aconfig-flags-lib/*/gen`.
