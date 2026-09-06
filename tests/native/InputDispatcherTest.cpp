// Exercise pinned InputDispatcher through Android window metadata and stock
// InputConsumer channels. AOSP's test policy substitutes for the WMS policy;
// dispatch, coordinate transforms, focus, cancellation and peer death stay
// stock.
#include "FakeApplicationHandle.h"
#include "FakeInputDispatcherPolicy.h"
#include "FakeWindows.h"
#include <input/InputEventBuilders.h>
#include <gtest/gtest.h>

using namespace android;
using namespace android::inputdispatcher;
using android::os::InputEventInjectionResult;
using android::os::InputEventInjectionSync;
using namespace std::chrono_literals;

class HostedInputDispatcher : public testing::Test {
protected:
  FakeInputDispatcherPolicy policy;
  std::unique_ptr<InputDispatcher> dispatcher;
  std::shared_ptr<FakeApplicationHandle> application;
  sp<FakeWindowHandle> left, right;
  nsecs_t gestureStart = 0;

  void SetUp() override {
    dispatcher = std::make_unique<InputDispatcher>(policy);
    application = std::make_shared<FakeApplicationHandle>();
    left = sp<FakeWindowHandle>::make(application, dispatcher, "task-left",
                                      ui::LogicalDisplayId::DEFAULT);
    right = sp<FakeWindowHandle>::make(application, dispatcher, "task-right",
                                       ui::LogicalDisplayId::DEFAULT);
    left->setFrame(Rect(300, 100, 700, 500));
    right->setFrame(Rect(600, 100, 1000, 500));
    // First window in the update is topmost, including the real overlap.
    dispatcher->onWindowInfosChanged(
        {{*right->getInfo(), *left->getInfo()}, {}, 0, 0});
    dispatcher->setInputDispatchMode(true, false);
    ASSERT_EQ(OK, dispatcher->start());
  }
  void TearDown() override {
    ASSERT_EQ(OK, dispatcher->stop());
    dispatcher.reset();
  }
  void focus(const sp<FakeWindowHandle> &window) {
    gui::FocusRequest request;
    request.token = window->getToken();
    request.windowName = window->getName();
    request.timestamp = systemTime(SYSTEM_TIME_MONOTONIC);
    request.displayId = window->getInfo()->displayId.val();
    dispatcher->setFocusedWindow(request);
  }
  InputEventInjectionResult motion(int32_t action, float x, float y,
                                   std::optional<gui::Uid> target = {}) {
    const auto now = systemTime(SYSTEM_TIME_MONOTONIC);
    if (action == AMOTION_EVENT_ACTION_DOWN)
      gestureStart = now;
    auto event = MotionEventBuilder(action, AINPUT_SOURCE_TOUCHSCREEN)
                     .displayId(ui::LogicalDisplayId::DEFAULT)
                     .eventTime(now)
                     .downTime(gestureStart)
                     .pointer(PointerBuilder(0, ToolType::FINGER).x(x).y(y))
                     .build();
    return dispatcher->injectInputEvent(
        &event, target, InputEventInjectionSync::WAIT_FOR_RESULT, 1s,
        POLICY_FLAG_PASS_TO_USER);
  }
  InputEventInjectionResult key(int32_t action) {
    KeyEvent event;
    const auto now = systemTime(SYSTEM_TIME_MONOTONIC);
    event.initialize(InputEvent::nextId(), 7, AINPUT_SOURCE_KEYBOARD,
                     ui::LogicalDisplayId::DEFAULT, INVALID_HMAC, action, 0,
                     AKEYCODE_ENTER, KEY_ENTER, AMETA_NONE, 0, now, now);
    return dispatcher->injectInputEvent(
        &event, {}, InputEventInjectionSync::WAIT_FOR_RESULT, 1s,
        POLICY_FLAG_PASS_TO_USER | POLICY_FLAG_DISABLE_KEY_REPEAT);
  }
  void expectMotion(const sp<FakeWindowHandle> &window, int action, float x,
                    float y) {
    auto event = window->consumeMotionEvent(
        testing::AllOf(WithMotionAction(action),
                       WithDisplayId(ui::LogicalDisplayId::DEFAULT)));
    ASSERT_NE(nullptr, event);
    EXPECT_FLOAT_EQ(x, event->getX(0));
    EXPECT_FLOAT_EQ(y, event->getY(0));
  }
};

TEST_F(HostedInputDispatcher,
       RoutesOverlappingWindowsAndTransformsCoordinates) {
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 625, 125));
  expectMotion(right, AMOTION_EVENT_ACTION_DOWN, 25, 25);
  left->assertNoEvents();
  // The gesture remains with its original target after leaving that window.
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_MOVE, 350, 150));
  expectMotion(right, AMOTION_EVENT_ACTION_MOVE, -250, 50);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_UP, 350, 150));
  expectMotion(right, AMOTION_EVENT_ACTION_UP, -250, 50);
  left->assertNoEvents();
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_DOWN, 50, 50);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_UP, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_UP, 50, 50);
  right->assertNoEvents();
}

TEST_F(HostedInputDispatcher, FocusOrdersCallbacksAndRoutesKeys) {
  focus(left);
  left->consumeFocusEvent(true);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED, key(AKEY_EVENT_ACTION_DOWN));
  auto event = left->consumeKey();
  ASSERT_NE(nullptr, event);
  EXPECT_EQ(AKEYCODE_ENTER, event->getKeyCode());
  EXPECT_EQ(AKEY_EVENT_ACTION_DOWN, event->getAction());
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED, key(AKEY_EVENT_ACTION_UP));
  left->consumeKeyUp(ui::LogicalDisplayId::DEFAULT);
  focus(right);
  left->consumeFocusEvent(false);
  right->consumeFocusEvent(true);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED, key(AKEY_EVENT_ACTION_DOWN));
  right->consumeKeyDown(ui::LogicalDisplayId::DEFAULT);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED, key(AKEY_EVENT_ACTION_UP));
  right->consumeKeyUp(ui::LogicalDisplayId::DEFAULT);
  left->assertNoEvents();
}

TEST_F(HostedInputDispatcher,
       RemovingWindowCancelsGestureAndKeepsOtherTaskAlive) {
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 650, 150));
  expectMotion(right, AMOTION_EVENT_ACTION_DOWN, 50, 50);
  dispatcher->onWindowInfosChanged({{*left->getInfo()}, {}, 0, 0});
  right->consumeMotionCancel();
  // Window cancellation does not lift the physical pointer. End that input
  // stream before beginning another; its old target is already gone.
  EXPECT_EQ(InputEventInjectionResult::FAILED,
            motion(AMOTION_EVENT_ACTION_UP, 650, 150));
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_DOWN, 50, 50);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_UP, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_UP, 50, 50);
}

TEST_F(HostedInputDispatcher, RejectsWrongTargetUid) {
  EXPECT_EQ(InputEventInjectionResult::TARGET_MISMATCH,
            motion(AMOTION_EVENT_ACTION_DOWN, 650, 150, gui::Uid{2002}));
  right->assertNoEvents();
  left->assertNoEvents();
}

TEST_F(HostedInputDispatcher, ReportsBrokenChannelAndKeepsOtherTaskAlive) {
  const auto token = right->getToken();
  right->destroyReceiver();
  policy.assertNotifyInputChannelBrokenWasCalled(token);
  dispatcher->onWindowInfosChanged({{*left->getInfo()}, {}, 0, 0});
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_DOWN, 50, 50);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_UP, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_UP, 50, 50);
}

TEST_F(HostedInputDispatcher, VerifiesSignedEventsAndRejectsTampering) {
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 350, 150));
  auto event =
      left->consumeMotionEvent(WithMotionAction(AMOTION_EVENT_ACTION_DOWN));
  ASSERT_NE(nullptr, event);
  ASSERT_NE(nullptr, dispatcher->verifyInputEvent(*event));
  event->setAction(AMOTION_EVENT_ACTION_UP);
  EXPECT_EQ(nullptr, dispatcher->verifyInputEvent(*event));
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_UP, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_UP, 50, 50);
}
