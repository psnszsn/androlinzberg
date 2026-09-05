#pragma once
// Platform (non-VINTF) interfaces share the hosted process domain. VINTF
// service negotiation is unavailable and must not be advertised by this facade.
namespace android::internal {
class Stability {
public:
  template <class T> static void markCompilationUnit(T *) {}
};
} // namespace android::internal
