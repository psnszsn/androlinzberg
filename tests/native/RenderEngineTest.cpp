// Exercise pinned RenderEngine's public drawLayers contract on actual GBM
// storage. Readback is confined to verification; rendering uses GPU imports.
#include <azk/graphics_buffer.h>
#include <azk/graphics_egl.h>
#include <renderengine/RenderEngine.h>
#include <renderengine/impl/ExternalTexture.h>
#include <ui/GraphicBuffer.h>
#include <ui/Fence.h>
#include "GpuBufferRenderer.h"
#include <cassert>
#include <iostream>
#include <array>
#include <chrono>

using namespace android;
namespace re = android::renderengine;
namespace {
void expectPixel(const GraphicBuffer &buffer, const void *pixels, unsigned x,
                 unsigned y, std::array<unsigned, 4> expected) {
  const auto *pixel =
      static_cast<const uint8_t *>(pixels) + (y * buffer.getStride() + x) * 4;
  for (unsigned c = 0; c < 4; ++c) {
    if (std::abs(int(pixel[c]) - int(expected[c])) > 2) {
      std::cerr << "pixel " << x << ',' << y << " channel " << c << " got "
                << unsigned(pixel[c]) << " expected " << expected[c]
                << std::endl;
      std::abort();
    }
  }
}
} // namespace
int main() {
  const uint64_t baseline = azk_graphics_live_buffers();
  {
    GpuBufferRenderer producer;
    auto engine = re::RenderEngine::create(
        re::RenderEngineCreationArgs::Builder()
            .setThreaded(re::RenderEngine::Threaded::YES)
            .build());
    assert(engine);
    assert(re::RenderEngine::canSupport(re::RenderEngine::GraphicsApi::GL));
    assert(!re::RenderEngine::canSupport(re::RenderEngine::GraphicsApi::VK));
    // Fail unavailable backends at creation, before starting a thread or
    // allocating a supposedly protected target.
    assert(!re::RenderEngine::create(re::RenderEngineCreationArgs::Builder()
                                         .setEnableProtectedContext(true)
                                         .build()));
    assert(!re::RenderEngine::create(
        re::RenderEngineCreationArgs::Builder()
            .setGraphicsApi(re::RenderEngine::GraphicsApi::VK)
            .build()));
    auto input = sp<GraphicBuffer>::make(32, 32, PIXEL_FORMAT_RGBA_8888, 1,
                                         GRALLOC_USAGE_HW_RENDER |
                                             GRALLOC_USAGE_HW_TEXTURE,
                                         "RenderEngineInput");
    assert(input->initCheck() == OK);
    auto texture = std::make_shared<re::impl::ExternalTexture>(
        input, *engine, re::impl::ExternalTexture::READABLE);
    // Keep both outputs alive. Updating either must leave the other target's
    // last completed frame intact, including when imported textures are cached.
    std::array<sp<GraphicBuffer>, 2> outputs;
    std::array<std::shared_ptr<re::impl::ExternalTexture>, 2> targets;
    std::array<std::array<unsigned, 4>, 2> expected;
    for (unsigned index = 0; index < outputs.size(); ++index) {
      auto &output = outputs[index];
      output = sp<GraphicBuffer>::make(
          index ? 96 : 64, index ? 72 : 48, PIXEL_FORMAT_RGBA_8888, 1,
          GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE |
              GRALLOC_USAGE_SW_READ_OFTEN,
          "RenderEngineOutput");
      assert(output->initCheck() == OK);
      targets[index] = std::make_shared<re::impl::ExternalTexture>(
          output, *engine, re::impl::ExternalTexture::WRITEABLE);
    }
    for (unsigned frame = 0; frame < 6; ++frame) {
      const unsigned index = frame % 2;
      const auto &output = outputs[index];
      const unsigned width = output->getWidth();
      const unsigned height = output->getHeight();
      const bool green = (frame & 1) != 0;
      const unsigned intensity = 255 - (frame / 2) * 64;
      auto ready = producer.render(
          input, 0xff000000 | (green ? intensity << 8 : intensity));
      expected[index] =
          green ? std::array<unsigned, 4>{0, (intensity + 1) / 2, 128, 255}
                : std::array<unsigned, 4>{(intensity + 1) / 2, 0, 128, 255};
      re::DisplaySettings display;
      display.physicalDisplay = Rect(width, height);
      display.clip = Rect(width, height);
      display.outputDataspace = ui::Dataspace::V0_SRGB;
      re::LayerSettings background;
      background.geometry.boundaries = FloatRect(0, 0, width, height);
      background.source.solidColor = half3(0.f, 0.f, 1.f);
      background.alpha = half(1.f);
      background.sourceDataspace = ui::Dataspace::V0_SRGB;
      re::LayerSettings foreground;
      foreground.geometry.boundaries = FloatRect(0, 0, 16, 16);
      foreground.geometry.positionTransform =
          mat4::translate(vec4(8.f, 8.f, 0.f, 1.f));
      foreground.source.buffer.buffer = texture;
      foreground.source.buffer.fence = ready;
      foreground.source.buffer.isOpaque = true;
      foreground.alpha = half(0.5f);
      foreground.sourceDataspace = ui::Dataspace::V0_SRGB;
      auto future = engine->drawLayers(display, {background, foreground},
                                       targets[index], {});
      assert(future.wait_for(std::chrono::seconds(5)) ==
             std::future_status::ready);
      auto result = future.get();
      assert(result.ok() && result.value() && result.value()->isValid());
      assert(result.value()->wait(3000) == OK);
      for (unsigned checked = 0; checked <= std::min(frame, 1u); ++checked) {
        const auto &completed = outputs[checked];
        void *pixels = nullptr;
        assert(completed->lock(GRALLOC_USAGE_SW_READ_OFTEN, &pixels) == OK);
        expectPixel(*completed, pixels, 2, 2, {0, 0, 255, 255});
        expectPixel(*completed, pixels, 12, 12, expected[checked]);
        expectPixel(*completed, pixels, 26, 12, {0, 0, 255, 255});
        assert(completed->unlock() == OK);
      }
      engine->cleanupPostRender();
    }
    for (auto &target : targets)
      target.reset();
    for (auto &output : outputs)
      output.clear();
    texture.reset();
    input.clear();
    engine->cleanupPostRender();
    engine.reset();
  }
  assert(azk_graphics_live_buffers() == baseline);
  std::cout
      << "PASS pinned threaded RenderEngine GPU buffers, alpha, translation, "
         "independent targets, completion fences and cleanup"
      << std::endl;
}
