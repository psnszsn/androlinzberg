// Zig backend of the repository's AIDL compiler build. See generate_zig.cpp.
#pragma once

#include "aidl_language.h"
#include "aidl_typenames.h"
#include "io_delegate.h"
#include "options.h"

namespace android {
namespace aidl {
namespace zig {

// Writes `<output dir>/<package path>/<Name>.zig` for one top-level type.
// Returns false after reporting an error for AIDL that Zig cannot express.
bool GenerateZig(const Options& options, const AidlTypenames& typenames,
                 const AidlDefinedType& defined_type, const IoDelegate& io_delegate);

}  // namespace zig
}  // namespace aidl
}  // namespace android
