// Exercise pinned InputDispatcher through Android window metadata and stock
// InputConsumer channels. AOSP's test policy substitutes for the WMS policy;
// dispatch, coordinate transforms, focus, cancellation and peer death stay
// stock.
#include "FakeApplicationHandle.h"
#include "FakeInputDispatcherPolicy.h"
#include "FakeWindows.h"
#include <input/InputEventBuilders.h>
#include <binder/Binder.h>
#include <gtest/gtest.h>
#include <atomic>

using namespace android;
using namespace android::inputdispatcher;
using android::os::InputEventInjectionResult;
using android::os::InputEventInjectionSync;
using namespace std::chrono_literals;

class HostedDragPolicy : public FakeInputDispatcherPolicy {
public:
  std::atomic<unsigned> dropCalls{0};
  std::atomic<bool> nonNullDrop{false};
  void notifyDropWindow(const sp<IBinder>& token, float, float) override {
    if (token) nonNullDrop.store(true);
    dropCalls.fetch_add(1);
  }
};

class HostedInputDispatcher : public testing::Test {
protected:
  HostedDragPolicy policy;
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

// WMS task Binder identities are independent of window token, UID and geometry.
class HostedTaskKeyboard : public HostedInputDispatcher {
protected:
  sp<IBinder> leftTask = sp<BBinder>::make();
  sp<IBinder> rightTask = sp<BBinder>::make();
  nsecs_t keyStart = 0;

  void publishTasks(bool includeRight = true, bool sameTask = false) {
    InputDispatcher::HostedWindowTasks tasks{{left->getToken(), leftTask}};
    if (includeRight)
      tasks.emplace(right->getToken(), sameTask ? leftTask : rightTask);
    dispatcher->onHostedWindowInfosChanged(
        {{*right->getInfo(), *left->getInfo()}, {}, 0, 0}, std::move(tasks));
  }
  void SetUp() override {
    HostedInputDispatcher::SetUp();
    dispatcher->setFocusedApplication(ui::LogicalDisplayId::DEFAULT, application);
    publishTasks();
    dispatcher->setHostedKeyboardFocus(leftTask);
    focus(left);
    left->consumeFocusEvent(true);
    ASSERT_EQ(left->getInfo()->ownerUid, right->getInfo()->ownerUid);
  }
  void scopedKey(const sp<IBinder>& task, int32_t action,
                 int32_t code = AKEYCODE_A, bool repeatable = false) {
    const auto time = systemTime(SYSTEM_TIME_MONOTONIC);
    if (action == AKEY_EVENT_ACTION_DOWN) keyStart = time;
    dispatcher->notifyHostedKey(
        NotifyKeyArgs{InputEvent::nextId(), time, time, 7, AINPUT_SOURCE_KEYBOARD,
                      ui::LogicalDisplayId::DEFAULT,
                      POLICY_FLAG_TRUSTED | POLICY_FLAG_PASS_TO_USER |
                          (repeatable ? 0u : POLICY_FLAG_DISABLE_KEY_REPEAT),
                      action, 0, code, KEY_A, AMETA_NONE, keyStart}, task);
  }
};

TEST_F(HostedTaskKeyboard, WaitsForMatchingTaskMetadataAndFocus) {
  publishTasks(false);
  dispatcher->setHostedKeyboardFocus(rightTask);
  left->consumeFocusEvent(false);
  scopedKey(rightTask, AKEY_EVENT_ACTION_DOWN);
  focus(right); // The requested window exists, but task membership is not published.
  left->assertNoEvents(50ms);
  right->assertNoEvents(50ms);
  publishTasks();
  right->consumeFocusEvent(true);
  right->consumeKeyDown(ui::LogicalDisplayId::DEFAULT);
  scopedKey(rightTask, AKEY_EVENT_ACTION_UP);
  right->consumeKeyUp(ui::LogicalDisplayId::DEFAULT);
  left->assertNoEvents();
}

TEST_F(HostedTaskKeyboard, QueuedKeyCannotFollowFocusAcrossTasksOrBackToOldTask) {
  // Keep the delayed event fresh so generic age rejection cannot mask a lost scope.
  policy.setStaleEventTimeout(10s);
  policy.setInterceptKeyBeforeDispatchingResult(static_cast<nsecs_t>(1'000'000'000));
  scopedKey(leftTask, AKEY_EVENT_ACTION_DOWN);
  ASSERT_FALSE(dispatcher->waitForIdle()); // Key is queued behind policy, not delivered.
  dispatcher->setHostedKeyboardFocus(rightTask);
  focus(right);
  left->consumeFocusEvent(false);
  right->consumeFocusEvent(true);
  dispatcher->setHostedKeyboardFocus(leftTask);
  focus(left);
  right->consumeFocusEvent(false);
  left->consumeFocusEvent(true);
  policy.setInterceptKeyBeforeDispatchingResult(static_cast<nsecs_t>(0));
  scopedKey(leftTask, AKEY_EVENT_ACTION_DOWN, AKEYCODE_B);
  scopedKey(leftTask, AKEY_EVENT_ACTION_UP, AKEYCODE_B);
  auto down = left->consumeKey();
  ASSERT_NE(nullptr, down);
  EXPECT_EQ(AKEY_EVENT_ACTION_DOWN, down->getAction());
  EXPECT_EQ(AKEYCODE_B, down->getKeyCode());
  auto up = left->consumeKey();
  ASSERT_NE(nullptr, up);
  EXPECT_EQ(AKEY_EVENT_ACTION_UP, up->getAction());
  EXPECT_EQ(AKEYCODE_B, up->getKeyCode());
  right->assertNoEvents();
  left->assertNoEvents();
}

TEST_F(HostedTaskKeyboard, SameTaskPopupRetainsStockWindowRetargeting) {
  publishTasks(true, true);
  focus(right);
  left->consumeFocusEvent(false);
  right->consumeFocusEvent(true);
  scopedKey(leftTask, AKEY_EVENT_ACTION_DOWN);
  right->consumeKeyDown(ui::LogicalDisplayId::DEFAULT);
  scopedKey(leftTask, AKEY_EVENT_ACTION_UP);
  right->consumeKeyUp(ui::LogicalDisplayId::DEFAULT);
  left->assertNoEvents();
}

TEST_F(HostedTaskKeyboard, KeyboardWithdrawalDoesNotCancelIndependentTouch) {
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 650, 150));
  expectMotion(right, AMOTION_EVENT_ACTION_DOWN, 50, 50);
  dispatcher->setHostedKeyboardFocus(nullptr);
  left->consumeFocusEvent(false);
  // Re-publication must not restore the preferred SF window while the seat is absent.
  publishTasks();
  focus(left);
  scopedKey(leftTask, AKEY_EVENT_ACTION_DOWN);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_MOVE, 350, 150));
  expectMotion(right, AMOTION_EVENT_ACTION_MOVE, -250, 50);
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_UP, 350, 150));
  expectMotion(right, AMOTION_EVENT_ACTION_UP, -250, 50);
  left->assertNoEvents();
  right->assertNoEvents();
}

TEST_F(HostedTaskKeyboard, FocusChangeCancelsHeldKeyAndStopsOldRepeat) {
  dispatcher->setKeyRepeatConfiguration(20ms, 40ms, true);
  scopedKey(leftTask, AKEY_EVENT_ACTION_DOWN, AKEYCODE_A, true);
  left->consumeKeyDown(ui::LogicalDisplayId::DEFAULT);
  auto repeated = left->consumeKey();
  ASSERT_NE(nullptr, repeated);
  EXPECT_GT(repeated->getRepeatCount(), 0);
  dispatcher->setHostedKeyboardFocus(rightTask);
  focus(right);
  bool cancelled = false;
  for (int count = 0; count < 4 && !cancelled; ++count) {
    auto key = left->consumeKey();
    ASSERT_NE(nullptr, key);
    if (key->getAction() == AKEY_EVENT_ACTION_UP) {
      EXPECT_NE(0, key->getFlags() & AKEY_EVENT_FLAG_CANCELED);
      cancelled = true;
    } else EXPECT_GT(key->getRepeatCount(), 0);
  }
  ASSERT_TRUE(cancelled);
  left->consumeFocusEvent(false);
  right->consumeFocusEvent(true);
  right->assertNoEvents(150ms);
  left->assertNoEvents(150ms);
}

// Follows pinned InputDispatcherDragTests::startDrag: transfer a real DOWN to
// an empty-region drag input window, then retire its channel like WMS does.
TEST_F(HostedInputDispatcher, NativeDragHandoffOwnsOneInputTokenWithoutFalseDrop) {
  for (const bool cancelBeforeRemoval : {true, false}) {
    ASSERT_FALSE(dispatcher->handoffHostedDrag(left->getToken()));
    ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
              motion(AMOTION_EVENT_ACTION_DOWN, 350, 150));
    expectMotion(left, AMOTION_EVENT_ACTION_DOWN, 50, 50);
    auto drag = sp<FakeWindowHandle>::make(application, dispatcher, "drag",
                                           ui::LogicalDisplayId::DEFAULT);
    drag->setFrame(Rect(0, 0, 1280, 720));
    drag->setTouchableRegion(Region{{0, 0, 0, 0}});
    dispatcher->onWindowInfosChanged({{*drag->getInfo(), *right->getInfo(), *left->getInfo()}, {}, 0, 0});
    ASSERT_TRUE(dispatcher->transferTouchGesture(left->getToken(), drag->getToken(), true, false));
    left->consumeMotionCancel();
    drag->consumeMotionDown(ui::LogicalDisplayId::DEFAULT, AMOTION_EVENT_FLAG_NO_FOCUS_CHANGE);
    ASSERT_FALSE(dispatcher->handoffHostedDrag(right->getToken()));
    ASSERT_TRUE(dispatcher->handoffHostedDrag(drag->getToken()));
    ASSERT_FALSE(dispatcher->handoffHostedDrag(drag->getToken()));
    // Taking the native grab causes pointer leave/touch cancel. Its Android
    // cancellation retires the old physical stream after the WM handoff.
    if (cancelBeforeRemoval) {
      ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
                motion(AMOTION_EVENT_ACTION_CANCEL, 350, 150));
      expectMotion(drag, AMOTION_EVENT_ACTION_CANCEL, 350, 150);
    }
    ASSERT_EQ(OK, dispatcher->removeInputChannel(drag->getToken()));
    dispatcher->onWindowInfosChanged({{*right->getInfo(), *left->getInfo()}, {}, 0, 0});
    if (!cancelBeforeRemoval) {
      // The physical cancellation still retires the input verifier stream,
      // even when WM already removed its former recipient channel.
      EXPECT_EQ(InputEventInjectionResult::FAILED,
                motion(AMOTION_EVENT_ACTION_CANCEL, 350, 150));
    }
    dispatcher->waitForIdle();
    EXPECT_EQ(0u, policy.dropCalls.load());
    left->assertNoEvents();
    right->assertNoEvents();
    ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
              motion(AMOTION_EVENT_ACTION_DOWN, 350, 150));
    expectMotion(left, AMOTION_EVENT_ACTION_DOWN, 50, 50);
    ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
              motion(AMOTION_EVENT_ACTION_UP, 350, 150));
    expectMotion(left, AMOTION_EVENT_ACTION_UP, 50, 50);
    EXPECT_EQ(0u, policy.dropCalls.load());
  }
}

TEST_F(HostedInputDispatcher, DragChannelRemovalStillReportsDropWithoutNativeHandoff) {
  ASSERT_EQ(InputEventInjectionResult::SUCCEEDED,
            motion(AMOTION_EVENT_ACTION_DOWN, 350, 150));
  expectMotion(left, AMOTION_EVENT_ACTION_DOWN, 50, 50);
  auto drag = sp<FakeWindowHandle>::make(application, dispatcher, "drag",
                                         ui::LogicalDisplayId::DEFAULT);
  drag->setTouchableRegion(Region{{0, 0, 0, 0}});
  dispatcher->onWindowInfosChanged({{*drag->getInfo(), *right->getInfo(), *left->getInfo()}, {}, 0, 0});
  ASSERT_TRUE(dispatcher->transferTouchGesture(left->getToken(), drag->getToken(), true, false));
  left->consumeMotionCancel();
  drag->consumeMotionDown(ui::LogicalDisplayId::DEFAULT, AMOTION_EVENT_FLAG_NO_FOCUS_CHANGE);
  dispatcher->onWindowInfosChanged({{*right->getInfo(), *left->getInfo()}, {}, 0, 0});
  dispatcher->waitForIdle();
  EXPECT_EQ(1u, policy.dropCalls.load());
  EXPECT_FALSE(policy.nonNullDrop.load());
  ASSERT_FALSE(dispatcher->handoffHostedDrag(drag->getToken()));
}
