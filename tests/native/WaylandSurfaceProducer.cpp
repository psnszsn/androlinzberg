#include <azk/binder.h>
#include <cassert>
#include <binder/IServiceManager.h>
#include <gui/Surface.h>
#include <ui/GraphicBuffer.h>
#include <system/window.h>
#include "GpuBufferRenderer.h"
#include <chrono>
#include <iostream>
#include <thread>
using namespace android;
int main(int argc, char **argv) {
  const bool pressure = argc == 2 && std::string(argv[1]) == "--pressure";
  bool observedPressure = false;
  auto binder = defaultServiceManager()->waitForService(
      String16("dev.artzig.surface.v1"));
  auto producer = interface_cast<IGraphicBufferProducer>(binder);
  assert(producer && binder->remoteBinder());
  auto surface = sp<Surface>::make(producer, true);
  ANativeWindow *window = surface.get();
  assert(native_window_api_connect(window, NATIVE_WINDOW_API_EGL) == OK);
  assert(native_window_set_usage(
             window, GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE |
                         GRALLOC_USAGE_SW_READ_OFTEN) == OK);
  assert(surface->setDequeueTimeout(1'000'000'000) == OK);
  GpuBufferRenderer renderer;
  for (int frame = 0; frame < 12; ++frame) {
    assert(native_window_set_buffers_dimensions(window, frame < 6 ? 320 : 480,
                                                frame < 6 ? 240 : 360) == OK);
    ANativeWindowBuffer *buffer = nullptr;
    int fd = -1;
    const auto start = std::chrono::steady_clock::now();
    auto status = window->dequeueBuffer(window, &buffer, &fd);
    if (pressure && !observedPressure && status == TIMED_OUT) {
      assert(std::chrono::steady_clock::now() - start >= std::chrono::milliseconds(900));
      observedPressure = true;
      std::cout << "BACKPRESSURE" << std::endl;
      std::string command;
      assert(std::getline(std::cin, command) && command == "resume");
      status = window->dequeueBuffer(window, &buffer, &fd);
    }
    assert(status == OK);
    assert(sp<Fence>::make(fd)->wait(3000) == OK);
    const uint32_t color =
        frame == 11 ? 0xff3478ab : 0xff000000u | uint32_t(frame * 0x010203);
    auto fence = renderer.render(GraphicBuffer::from(buffer), color);
    assert(window->queueBuffer(window, buffer, fence->dup()) == OK);
    std::cout << "queued " << frame + 1 << std::endl;
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  assert(!pressure || observedPressure);
  std::cout << "READY_SCREENSHOT" << std::endl;
  char ch;
  while (std::cin.get(ch)) {
  }
  if (argc == 2 && std::string(argv[1]) == "--expect-death") {
    int width;
    assert(producer->query(NATIVE_WINDOW_WIDTH, &width) == DEAD_OBJECT);
    std::cout << "PASS owner loss fails further producer calls" << std::endl;
    return 0;
  }
  assert(native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL) == OK);
}
