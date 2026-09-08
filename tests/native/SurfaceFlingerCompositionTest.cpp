// Exercise the pinned SF hierarchy -> snapshot -> LayerFE -> RenderEngine path.
// This is a native composition gate; service scheduling and output queues are
// tested separately when the hosted SurfaceFlinger service is available.
#include <azk/graphics_buffer.h>
#include <LayerLifecycleManagerHelper.h>
#include <LayerFE.h>
#include <SurfaceFlinger.h>
#include <renderengine/RenderEngine.h>
#include <renderengine/impl/ExternalTexture.h>
#include "GpuBufferRenderer.h"
#include "DelayedGpuFence.h"
#include <array>
#include <cassert>
#include <chrono>
#include <iostream>
#include <set>

using namespace android;
namespace fe = android::surfaceflinger::frontend;
namespace re = android::renderengine;
using namespace ftl::flag_operators;

// LayerFE still reads this legacy service global for display-inverse buffers.
// This standalone fixture has an unrotated display and does not start SF.
ui::Transform::RotationFlags SurfaceFlinger::sActiveDisplayRotationFlags =
    ui::Transform::ROT_0;

namespace {
void pixel(const GraphicBuffer &buffer, const void *pixels, unsigned x,
           unsigned y, std::array<unsigned, 4> expected) {
  const auto *p =
      static_cast<const uint8_t *>(pixels) + (y * buffer.getStride() + x) * 4;
  for (unsigned c = 0; c < 4; ++c) {
    if (std::abs(int(p[c]) - int(expected[c])) > 2) {
      std::cerr << "pixel " << x << ',' << y << " channel " << c << " got "
                << unsigned(p[c]) << " expected " << expected[c] << std::endl;
      std::abort();
    }
  }
}

void setBuffer(fe::LayerLifecycleManager &manager, uint32_t id,
               const std::shared_ptr<re::impl::ExternalTexture> &texture,
               const sp<Fence> &fence, uint64_t frame) {
  QueuedTransactionState transaction;
  transaction.states.emplace_back();
  auto &resolved = transaction.states.back();
  resolved.layerId = id;
  resolved.externalTexture = texture;
  resolved.state.what =
      layer_state_t::eBufferChanged | layer_state_t::eDataspaceChanged;
  resolved.state.dataspace = ui::Dataspace::V0_SRGB;
  auto &data = resolved.state.bufferData;
  data = std::make_shared<BufferData>();
  data->buffer = texture->getBuffer();
  data->acquireFence = fence;
  data->frameNumber = frame;
  data->flags = BufferData::BufferDataChange::fenceChanged |
                BufferData::BufferDataChange::frameNumberChanged;
  manager.applyTransactions({transaction});
}

void createOutputLayer(fe::LayerLifecycleManager &manager, uint32_t id,
                       uint32_t parent, uint32_t mirrored) {
  // SurfaceFlinger::mirrorLayer/createLayer mark mirrors and containers with
  // eNoColorFill before handing them to the frontend. The generic hierarchy
  // test helper intentionally omits that service-side normalization.
  auto args = fe::LayerLifecycleManagerHelper::createArgs(
      id, parent == UNASSIGNED_LAYER_ID, parent, mirrored);
  args.flags = gui::ISurfaceComposerClient::eNoColorFill;
  std::vector<std::unique_ptr<fe::RequestedLayerState>> layers;
  layers.push_back(std::make_unique<fe::RequestedLayerState>(args));
  manager.addLayers(std::move(layers));
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
    {
      fe::LayerLifecycleManager manager;
      fe::LayerLifecycleManagerHelper scene(manager);
      fe::LayerHierarchyBuilder hierarchy;
      fe::LayerSnapshotBuilder snapshots;
      fe::DisplayInfos displays;
      ShadowSettings shadows;
      std::array<sp<GraphicBuffer>, 2> inputs, outputs;
      std::array<std::shared_ptr<re::impl::ExternalTexture>, 2> textures,
          targets;
      std::array<std::array<unsigned, 4>, 2> expected;
      for (unsigned index = 0; index < 2; ++index) {
        const uint32_t root = 1 + index;
        const uint32_t child = 11 + index;
        const uint32_t outputRoot = 101 + index;
        const uint32_t mirror = 201 + index;
        // Both original subtrees share Android's layer stack. Separate mirror
        // roots are filtered for private composition outputs.
        scene.createRootLayer(root);
        scene.setCrop(root, Rect(64, 64));
        scene.setColor(root, half3(0.f, 0.f, 1.f));
        scene.setPosition(root, 300.f * (index + 1), 0.f);
        scene.createLayer(child, root);
        scene.setZ(child, 1);
        scene.setPosition(child, 8.f, 8.f);
        scene.setMatrix(child, 2.f, 0.f, 0.f, 2.f);
        scene.setCrop(child, Rect(8, 8));
        scene.setAlpha(child, 0.5f);
        createOutputLayer(manager, outputRoot, UNASSIGNED_LAYER_ID,
                          UNASSIGNED_LAYER_ID);
        scene.setLayerStack(outputRoot, 101 + index);
        scene.setCrop(outputRoot, Rect(64, 64));
        createOutputLayer(manager, mirror, outputRoot, root);
        scene.setPosition(mirror, -300.f * (index + 1), 0.f);

        inputs[index] = sp<GraphicBuffer>::make(
            32, 32, PIXEL_FORMAT_RGBA_8888, 1,
            GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE,
            "SFSubtreeInput");
        outputs[index] = sp<GraphicBuffer>::make(
            64, 64, PIXEL_FORMAT_RGBA_8888, 1,
            GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE |
                GRALLOC_USAGE_SW_READ_OFTEN,
            "SFSubtreeOutput");
        assert(inputs[index]->initCheck() == OK &&
               outputs[index]->initCheck() == OK);
        textures[index] = std::make_shared<re::impl::ExternalTexture>(
            inputs[index], *engine, re::impl::ExternalTexture::READABLE);
        targets[index] = std::make_shared<re::impl::ExternalTexture>(
            outputs[index], *engine, re::impl::ExternalTexture::WRITEABLE);
      }

      for (unsigned frame = 0; frame < 6; ++frame) {
        const unsigned index = frame % 2;
        const unsigned intensity = 255 - (frame / 2) * 64;
        const bool secure = frame >= 4;
        // A real unsignalled sync_file delays the producer's write. Composition
        // must not publish completion while it can still read old pixels.
        std::unique_ptr<DelayedGpuFence> delayed;
        if (frame == 0 || frame == 2) delayed = std::make_unique<DelayedGpuFence>();
        auto ready = producer.render(
            inputs[index], 0xff000000 | (index ? intensity << 8 : intensity),
            delayed ? delayed->fence() : Fence::NO_FENCE);
        if (delayed) assert(ready->wait(0) == -ETIME);
        setBuffer(manager, 11 + index, textures[index], ready, frame + 1);
        scene.setFlags(11 + index, layer_state_t::eLayerSecure,
                       secure ? layer_state_t::eLayerSecure : 0);
        hierarchy.update(manager);
        snapshots.update({.root = hierarchy.getHierarchy(),
                          .layerLifecycleManager = manager,
                          .displays = displays,
                          .globalShadowSettings = shadows,
                          .supportedLayerGenericMetadata = {},
                          .genericLayerMetadataKeyMap = {}});
        manager.commitChanges();
        const Rect viewport(64, 64);
        const Region clip(viewport);
        compositionengine::LayerFE::ClientCompositionTargetSettings settings{
            .clip = clip,
            .needsFiltering = false,
            .isSecure = false,
            .isProtected = false,
            .viewport = viewport,
            .dataspace = ui::Dataspace::V0_SRGB,
            .realContentIsVisible = true,
            .clearContent = false,
            .blurSetting = compositionengine::LayerFE::
                ClientCompositionTargetSettings::BlurSetting::Disabled,
            .whitePointNits = 200.f,
            .treat170mAsSrgb = false,
            .luts = nullptr};
        std::vector<re::LayerSettings> layers;
        std::set<int32_t> included;
        snapshots.forEachVisibleSnapshot(
            [&](const fe::LayerSnapshot &snapshot) {
              if (snapshot.outputFilter.layerStack !=
                  ui::LayerStack::fromValue(101 + index))
                return;
              auto layer = sp<android::LayerFE>::make(snapshot.name);
              layer->mSnapshot = std::make_unique<fe::LayerSnapshot>(snapshot);
              if (auto composed = layer->prepareClientComposition(settings)) {
                included.insert(snapshot.sequence);
                layers.push_back(*composed);
              }
            });
        // These assertions prove the mirror follows its chosen subtree and
        // cannot accidentally include the other task or its own output root.
        const std::set<int32_t> wanted{int32_t(1 + index), int32_t(11 + index)};
        if (included != wanted) {
          std::cerr << "output " << index << " included:";
          for (auto id : included)
            std::cerr << ' ' << id;
          std::cerr << std::endl;
          snapshots.forEachSnapshot([](const fe::LayerSnapshot &snapshot) {
            std::cerr << snapshot << std::endl;
          });
          std::abort();
        }
        re::DisplaySettings display;
        display.physicalDisplay = viewport;
        display.clip = viewport;
        display.outputDataspace = ui::Dataspace::V0_SRGB;
        auto future = engine->drawLayers(display, layers, targets[index], {});
        assert(future.wait_for(std::chrono::seconds(5)) ==
               std::future_status::ready);
        auto result = future.get();
        assert(result.ok() && result.value()->isValid());
        if (delayed) {
          assert(result.value()->wait(0) == -ETIME);
          delayed->release();
          assert(!delayed->timedOut());
        }
        assert(result.value()->wait(3000) == OK);
        expected[index] =
            secure  ? std::array<unsigned, 4>{0, 0, 0, 255}
            : index ? std::array<unsigned, 4>{0, (intensity + 1) / 2, 128, 255}
                    : std::array<unsigned, 4>{(intensity + 1) / 2, 0, 128, 255};
        for (unsigned checked = 0; checked <= std::min(frame, 1u); ++checked) {
          void *pixels = nullptr;
          assert(outputs[checked]->lock(GRALLOC_USAGE_SW_READ_OFTEN, &pixels) ==
                 OK);
          pixel(*outputs[checked], pixels, 2, 2, {0, 0, 255, 255});
          pixel(*outputs[checked], pixels, 12, 12, expected[checked]);
          pixel(*outputs[checked], pixels, 26, 12, {0, 0, 255, 255});
          assert(outputs[checked]->unlock() == OK);
        }
      }
    }
    engine->cleanupPostRender();
    engine.reset();
  }
  assert(azk_graphics_live_buffers() == baseline);
  std::cout
      << "PASS pinned SF mirrored subtrees, GPU buffers, crop, scale, alpha, "
         "secure blackout, independent outputs and cleanup"
      << std::endl;
}
