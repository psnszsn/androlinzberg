// Stock SF publishes transformed WindowInfo over AZKB. Feed that publication to
// the stock dispatcher using the same callback as InputDispatcher's Android
// registration, which AOSP excludes from host builds. The fake policy stands
// in for WMS; neither hit testing nor layer geometry is synthesized here.
#include "FakeApplicationHandle.h"
#include "FakeInputDispatcherPolicy.h"
#include "FakeWindows.h"
#include "GpuBufferRenderer.h"
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <binder/IPCThreadState.h>
#include <android/os/BnInputFlinger.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <input/InputEventBuilders.h>
#include <gtest/gtest.h>
#include <condition_variable>
#include <iostream>
#include <mutex>

using namespace android;
using namespace android::inputdispatcher;
using android::os::InputEventInjectionResult;
using android::os::InputEventInjectionSync;
using namespace std::chrono_literals;

class PublishedWindows final : public gui::WindowInfosListener {
  std::mutex mutex;
  std::condition_variable changed;
  InputDispatcher *dispatcher;
  gui::WindowInfosUpdate latest;

public:
  explicit PublishedWindows(InputDispatcher &value) : dispatcher(&value) {}
  void onWindowInfosChanged(const gui::WindowInfosUpdate &update) override {
    std::lock_guard lock(mutex);
    if (!dispatcher)
      return;
    dispatcher->onWindowInfosChanged(update);
    latest = update;
    changed.notify_all();
  }
  void detach() {
    std::lock_guard lock(mutex);
    dispatcher = nullptr;
  }
  bool awaitVisible(const sp<IBinder> &token, bool visible) {
    std::unique_lock lock(mutex);
    return changed.wait_for(lock, 5s, [&] {
      for (const auto &window : latest.windowInfos)
        if (window.token == token)
          return !window.inputConfig.test(
                     gui::WindowInfo::InputConfig::NOT_VISIBLE) == visible;
      return false;
    });
  }
  bool awaitFrame(const sp<IBinder> &token, std::optional<Rect> frame) {
    std::unique_lock lock(mutex);
    const bool found = changed.wait_for(lock, 5s, [&] {
      for (const auto &window : latest.windowInfos) {
        if (window.token == token) {
          return frame && window.frame == *frame &&
                 window.displayId == ui::LogicalDisplayId::DEFAULT;
        }
      }
      return !frame;
    });
    if (!found) {
      std::cerr << "SF input publication mismatch; windows="
                << latest.windowInfos.size() << std::endl;
      for (const auto &window : latest.windowInfos)
        std::cerr << window.name << " display=" << window.displayId.val()
                  << " frame=" << window.frame.left << ',' << window.frame.top
                  << ',' << window.frame.right << ',' << window.frame.bottom
                  << " same-token=" << (window.token == token) << std::endl;
    }
    return found;
  }
};

// This native fixture implements exactly the SF focus callback contract.
// Channel provisioning belongs to InputManagerService and is not advertised
// by this fixture. The "window" endpoint below only supplies SF's death link;
// this test is not evidence of a booted WMS/ATMS service graph.
class FocusService final : public os::BnInputFlinger {
  std::mutex mutex;
  InputDispatcher *dispatcher;

public:
  explicit FocusService(InputDispatcher &value) : dispatcher(&value) {}
  void detach() {
    std::lock_guard lock(mutex);
    dispatcher = nullptr;
  }
  binder::Status createInputChannel(const std::string &,
                                    os::InputChannelCore *) override {
    return binder::Status::fromStatusT(UNKNOWN_TRANSACTION);
  }
  binder::Status removeInputChannel(const sp<IBinder> &) override {
    return binder::Status::fromStatusT(UNKNOWN_TRANSACTION);
  }
  binder::Status setFocusedWindow(const gui::FocusRequest &request) override {
    std::lock_guard lock(mutex);
    if (!dispatcher)
      return binder::Status::fromStatusT(DEAD_OBJECT);
    dispatcher->setFocusedWindow(request);
    return binder::Status::ok();
  }
};

TEST(SurfaceFlingerInput, PublishedGeometryRoutesAndCancelsStockInput) {
  FakeInputDispatcherPolicy policy;
  auto dispatcher = std::make_unique<InputDispatcher>(policy);
  auto application = std::make_shared<FakeApplicationHandle>();
  auto left = sp<FakeWindowHandle>::make(
      application, dispatcher, "PublishedLeft", ui::LogicalDisplayId::DEFAULT);
  auto right = sp<FakeWindowHandle>::make(
      application, dispatcher, "PublishedRight", ui::LogicalDisplayId::DEFAULT);
  // These buffers are created by this authenticated system client. Match
  // the window owner to that identity, as WMS does for an application's
  // buffers, so real untrusted-overlay checks do not reject the fixture.
  const gui::Uid ownerUid{
      static_cast<uint32_t>(IPCThreadState::self()->getCallingUid())};
  const gui::Pid ownerPid{getpid()};
  left->setOwnerInfo(ownerPid, ownerUid);
  right->setOwnerInfo(ownerPid, ownerUid);
  left->setFrame(Rect(64, 64));
  right->setFrame(Rect(64, 64));
  auto client = sp<SurfaceComposerClient>::make();
  ASSERT_EQ(OK, client->initCheck());
  auto listener = sp<PublishedWindows>::make(*dispatcher);
  ASSERT_EQ(OK, client->addWindowInfosListener(listener));
  dispatcher->setInputDispatchMode(true, false);
  ASSERT_EQ(OK, dispatcher->start());
  // Keep teardown ordered even when an assertion returns from the test.
  struct Cleanup {
    sp<SurfaceComposerClient> client;
    sp<PublishedWindows> listener;
    InputDispatcher &dispatcher;
    sp<FocusService> focus;
    ~Cleanup() {
      if (focus)
        focus->detach();
      listener->detach();
      EXPECT_EQ(OK, client->removeWindowInfosListener(listener));
      EXPECT_EQ(OK, dispatcher.stop());
    }
  } cleanup{client, listener, *dispatcher};

  // SF enables window publication only after resolving inputflinger during
  // bootFinished. Supply its bounded test collaborators before completing that
  // real control flow; production must provide actual WMS and input services.
  auto focusService = sp<FocusService>::make(*dispatcher);
  cleanup.focus = focusService;
  auto windowLifetime = sp<BBinder>::make();
  ASSERT_EQ(OK, defaultServiceManager()->addService(String16("window"),
                                                    windowLifetime));
  ASSERT_EQ(OK, defaultServiceManager()->addService(String16("inputflinger"),
                                                    focusService));
  ASSERT_EQ(OK, SurfaceComposerClient::bootFinished());

  GpuBufferRenderer renderer;
  auto buffer = sp<GraphicBuffer>::make(
      64, 64, PIXEL_FORMAT_RGBA_8888, 1,
      GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE, "InputPublication");
  ASSERT_EQ(OK, buffer->initCheck());
  auto fence = renderer.render(buffer, 0xff00ff00u);
  // Follow WMS's surface hierarchy: window geometry and input metadata live
  // on a container, with application buffers below it. SF intentionally uses
  // a buffer layer's parent transform for input, not its buffer placement.
  auto first = client->createSurface(
      String8("PublishedLeft"), 0, 0, PIXEL_FORMAT_RGBA_8888,
      gui::ISurfaceComposerClient::eFXSurfaceContainer);
  auto second = client->createSurface(
      String8("PublishedRight"), 0, 0, PIXEL_FORMAT_RGBA_8888,
      gui::ISurfaceComposerClient::eFXSurfaceContainer);
  ASSERT_TRUE(first && second);
  auto firstBuffer =
      client->createSurface(String8("LeftBuffer"), 64, 64,
                            PIXEL_FORMAT_RGBA_8888, 0, first->getHandle());
  auto secondBuffer =
      client->createSurface(String8("RightBuffer"), 64, 64,
                            PIXEL_FORMAT_RGBA_8888, 0, second->getHandle());
  ASSERT_TRUE(firstBuffer && secondBuffer);
  const auto displays = SurfaceComposerClient::getPhysicalDisplayIds();
  ASSERT_EQ(1u, displays.size());
  const auto display =
      SurfaceComposerClient::getPhysicalDisplayToken(displays[0]);
  ASSERT_NE(nullptr, display);
  SurfaceComposerClient::Transaction setup;
  // DMS normally establishes this projection. The native service fixture has
  // the host composer's fixed 1280x720 mode and one logical layer stack.
  setup.setDisplayLayerStack(display, ui::LayerStack{0});
  setup.setDisplayProjection(display, ui::Rotation::Rotation0, Rect(1280, 720),
                             Rect(1280, 720));
  setup.setBuffer(firstBuffer, buffer, fence)
      .setBuffer(secondBuffer, buffer, fence)
      .show(firstBuffer)
      .show(secondBuffer)
      .setPosition(first, 300, 100)
      .setPosition(second, 500, 100)
      .setMatrix(first, 4, 0, 0, 4)
      .setMatrix(second, 4, 0, 0, 4)
      .setCrop(first, Rect(64, 64))
      .setCrop(second, Rect(64, 64))
      .setLayer(first, 1)
      .setLayer(second, 2)
      .setInputWindowInfo(first, left)
      .setInputWindowInfo(second, right)
      .show(first)
      .show(second);
  ASSERT_EQ(OK, setup.apply());
  ASSERT_TRUE(listener->awaitFrame(left->getToken(), Rect(300, 100, 556, 356)));
  ASSERT_TRUE(
      listener->awaitFrame(right->getToken(), Rect(500, 100, 756, 356)));

  auto focus = [&](const sp<FakeWindowHandle> &window) {
    gui::FocusRequest request;
    request.token = window->getToken();
    request.windowName = window->getName();
    request.timestamp = systemTime(SYSTEM_TIME_MONOTONIC);
    request.displayId = ui::LogicalDisplayId::DEFAULT.val();
    ASSERT_EQ(
        OK,
        SurfaceComposerClient::Transaction().setFocusedWindow(request).apply());
  };
  focus(left);
  left->consumeFocusEvent(true);
  focus(right);
  left->consumeFocusEvent(false);
  right->consumeFocusEvent(true);
  const auto keyTime = systemTime(SYSTEM_TIME_MONOTONIC);
  for (int action : {AKEY_EVENT_ACTION_DOWN, AKEY_EVENT_ACTION_UP}) {
    KeyEvent key;
    key.initialize(InputEvent::nextId(), 7, AINPUT_SOURCE_KEYBOARD,
                   ui::LogicalDisplayId::DEFAULT, INVALID_HMAC, action, 0,
                   AKEYCODE_ENTER, KEY_ENTER, AMETA_NONE, 0, keyTime, keyTime);
    ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
              dispatcher->injectInputEvent(
                  &key, {}, InputEventInjectionSync::WAIT_FOR_RESULT, 1s,
                  POLICY_FLAG_PASS_TO_USER | POLICY_FLAG_DISABLE_KEY_REPEAT));
    auto received = right->consumeKey();
    ASSERT_NE(nullptr, received);
    EXPECT_EQ(action, received->getAction());
    EXPECT_EQ(AKEYCODE_ENTER, received->getKeyCode());
  }
  left->assertNoEvents();

  nsecs_t gestureStart = 0;
  auto inject = [&](int32_t action, float x, float y) {
    const auto now = systemTime(SYSTEM_TIME_MONOTONIC);
    if (action == AMOTION_EVENT_ACTION_DOWN)
      gestureStart = now;
    auto event = MotionEventBuilder(action, AINPUT_SOURCE_TOUCHSCREEN)
                     .displayId(ui::LogicalDisplayId::DEFAULT)
                     .downTime(gestureStart)
                     .eventTime(now)
                     .pointer(PointerBuilder(0, ToolType::FINGER).x(x).y(y))
                     .build();
    return dispatcher->injectInputEvent(
        &event, {}, InputEventInjectionSync::WAIT_FOR_RESULT, 1s,
        POLICY_FLAG_PASS_TO_USER);
  };
  auto expect = [&](const sp<FakeWindowHandle> &window, int32_t action, float x,
                    float y) {
    auto event = window->consumeMotionEvent(WithMotionAction(action));
    ASSERT_NE(nullptr, event);
    EXPECT_FLOAT_EQ(x, event->getX(0));
    EXPECT_FLOAT_EQ(y, event->getY(0));
  };
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            inject(AMOTION_EVENT_ACTION_DOWN, 520, 120));
  expect(right, AMOTION_EVENT_ACTION_DOWN, 5, 5);
  left->assertNoEvents();
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            inject(AMOTION_EVENT_ACTION_UP, 520, 120));
  expect(right, AMOTION_EVENT_ACTION_UP, 5, 5);

  // SF movement changes both the published frame and the input transform.
  ASSERT_EQ(OK, SurfaceComposerClient::Transaction()
                    .setPosition(second, 700, 100)
                    .apply());
  ASSERT_TRUE(
      listener->awaitFrame(right->getToken(), Rect(700, 100, 956, 356)));
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            inject(AMOTION_EVENT_ACTION_DOWN, 520, 120));
  expect(left, AMOTION_EVENT_ACTION_DOWN, 55, 5);
  right->assertNoEvents();
  ASSERT_EQ(OK, SurfaceComposerClient::Transaction().hide(first).apply());
  ASSERT_TRUE(listener->awaitVisible(left->getToken(), false));
  // Pinned DispatcherTouchState::eraseRemovedWindowsFromWindowInfo cancels
  // touch streams on removal, not visibility changes. A hidden window still
  // receives the UP belonging to its existing gesture.
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            inject(AMOTION_EVENT_ACTION_UP, 520, 120));
  expect(left, AMOTION_EVENT_ACTION_UP, 55, 5);
  EXPECT_EQ(InputEventInjectionResult::FAILED,
            inject(AMOTION_EVENT_ACTION_DOWN, 520, 120));
  EXPECT_EQ(InputEventInjectionResult::FAILED,
            inject(AMOTION_EVENT_ACTION_UP, 520, 120));
  left->assertNoEvents();
  ASSERT_EQ(OK, SurfaceComposerClient::Transaction().show(first).apply());
  ASSERT_TRUE(listener->awaitVisible(left->getToken(), true));
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            inject(AMOTION_EVENT_ACTION_DOWN, 520, 120));
  expect(left, AMOTION_EVENT_ACTION_DOWN, 55, 5);
  ASSERT_EQ(
      OK,
      SurfaceComposerClient::Transaction().reparent(first, nullptr).apply());
  ASSERT_TRUE(listener->awaitFrame(left->getToken(), std::nullopt));
  left->consumeMotionCancel();
  EXPECT_EQ(InputEventInjectionResult::FAILED,
            inject(AMOTION_EVENT_ACTION_UP, 520, 120));
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            inject(AMOTION_EVENT_ACTION_DOWN, 720, 120));
  expect(right, AMOTION_EVENT_ACTION_DOWN, 5, 5);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            inject(AMOTION_EVENT_ACTION_UP, 720, 120));
  expect(right, AMOTION_EVENT_ACTION_UP, 5, 5);
  ASSERT_EQ(OK, SurfaceComposerClient::Transaction()
                    .reparent(first, nullptr)
                    .reparent(second, nullptr)
                    .apply());
}

struct ServiceDeath final : IBinder::DeathRecipient {
  std::mutex mutex;
  std::condition_variable changed;
  bool dead = false;
  void binderDied(const wp<IBinder> &) override {
    std::lock_guard lock(mutex);
    dead = true;
    changed.notify_all();
  }
};

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  ProcessState::self()->startThreadPool();
  auto service =
      defaultServiceManager()->checkService(String16("SurfaceFlingerAIDL"));
  assert(service && service->remoteBinder());
  auto death = sp<ServiceDeath>::make();
  assert(service->linkToDeath(death) == OK);
  if (const int result = RUN_ALL_TESTS(); result != 0)
    return result;
  std::cout << "READY_DEATH" << std::endl;
  std::unique_lock lock(death->mutex);
  assert(death->changed.wait_for(lock, 5s, [&] { return death->dead; }));
  std::cout << "PASS published SF input metadata over AZKB and service death"
            << std::endl;
}
