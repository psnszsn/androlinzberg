// Entry point of the repository's AIDL compiler build (build/aidl.zig).
//
// Every stock backend runs the pinned AOSP `aidl_entry` unchanged. `--lang=zig`
// is the one repo-owned addition: the pinned Options/Language enumeration is
// closed, so the Zig backend is selected here rather than by patching AOSP. It
// loads and validates its inputs through the pinned front end under the Java
// language rules, the only ones that accept an unstructured parcelable without
// a C++ or Rust type; generate_zig.cpp rejects the Java-only types itself.

#include "aidl.h"
#include "generate_zig.h"
#include "io_delegate.h"
#include "logging.h"
#include "options.h"

#include <string>
#include <vector>

using android::aidl::AidlError;
using android::aidl::AidlTypenames;
using android::aidl::IoDelegate;
using android::aidl::Options;

namespace {

constexpr char kZigLanguage[] = "--lang=zig";
constexpr char kFrontEndLanguage[] = "--lang=java";

int CompileZig(const Options& options, const IoDelegate& io_delegate) {
  if (options.OutputDir().empty()) {
    AIDL_ERROR("--lang=zig") << "An output directory (-o) is required.";
    return 1;
  }
  for (const std::string& input_file : options.InputFiles()) {
    AidlTypenames typenames;
    std::vector<std::string> imported_files;
    if (android::aidl::internals::load_and_validate_aidl(input_file, options, io_delegate,
                                                          &typenames, &imported_files) !=
        AidlError::OK) {
      return 1;
    }
    for (const auto& defined_type : typenames.MainDocument().DefinedTypes()) {
      AIDL_FATAL_IF(defined_type == nullptr, input_file);
      if (!android::aidl::zig::GenerateZig(options, typenames, *defined_type, io_delegate)) {
        return 1;
      }
    }
  }
  return 0;
}

}  // namespace

int main(int argc, char* argv[]) {
  bool zig = false;
  std::vector<char*> arguments(argv, argv + argc);
  std::string front_end_language = kFrontEndLanguage;
  for (char*& argument : arguments) {
    if (std::string(argument) == kZigLanguage) {
      zig = true;
      argument = front_end_language.data();
    }
  }

  Options options(static_cast<int>(arguments.size()), arguments.data(), Options::Language::JAVA);
  IoDelegate io_delegate;
  if (zig) {
    if (options.GetTask() != Options::Task::COMPILE) {
      AIDL_ERROR("--lang=zig") << "Only compilation is supported.";
      return 1;
    }
    return CompileZig(options, io_delegate);
  }
  return aidl_entry(options, io_delegate);
}
