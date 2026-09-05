#pragma once
namespace android::internal {
// This prototype has one private hosted domain and no VINTF/stability
// enforcement.
class Stability {
 public:
  template <class T>
  static void markCompilationUnit(T*) {}
};
}  // namespace android::internal
