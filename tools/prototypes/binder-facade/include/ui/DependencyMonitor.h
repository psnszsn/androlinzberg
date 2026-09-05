#pragma once
#include <string>
// Standalone probe excludes diagnostic fence-history monitoring.
namespace android {
class DependencyMonitor {
  std::string token;

 public:
  void setToken(std::string s) { token = std::move(s); }
};
}  // namespace android
