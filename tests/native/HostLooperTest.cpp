// The host borrows poll state; stock Looper owns dispatch, wake and fd
// lifetime.
#include <gtest/gtest.h>
#include <utils/Looper.h>
#include <android-base/unique_fd.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <thread>

using namespace android;
namespace {
class CountMessages : public MessageHandler {
public:
  int count = 0;
  void handleMessage(const Message &) override { ++count; }
};

TEST(HostLooper, NativeDeadlinesAndCrossThreadWakeRemainVisibleToOuterLoop) {
  auto looper = sp<Looper>::make(false);
  auto handler = sp<CountMessages>::make();
  int fd, timeout;
  looper->pollOnce(0);
  looper->getHostPollState(&fd, &timeout);
  ASSERT_GE(fd, 0);
  EXPECT_EQ(timeout, -1);
  // Posting earlier work after the snapshot must wake the outer poll.
  std::thread post([&] {
    looper->sendMessageDelayed(milliseconds_to_nanoseconds(80), handler,
                               Message(1));
  });
  post.join();
  pollfd pending{fd, POLLIN, 0};
  ASSERT_EQ(poll(&pending, 1, 1000), 1);
  looper->getHostPollState(&fd, &timeout);
  EXPECT_GE(timeout, 0);
  EXPECT_LE(timeout, 80);
  looper->pollOnce(0);
  const auto deadline =
      systemTime(SYSTEM_TIME_MONOTONIC) + seconds_to_nanoseconds(2);
  while (handler->count == 0) {
    ASSERT_LT(systemTime(SYSTEM_TIME_MONOTONIC), deadline);
    looper->getHostPollState(&fd, &timeout);
    ASSERT_GE(timeout, 0);
    pending = {fd, POLLIN, 0};
    ASSERT_GE(poll(&pending, 1, timeout), 0);
    looper->pollOnce(0);
  }
  EXPECT_EQ(handler->count, 1);
  looper->getHostPollState(&fd, &timeout);
  EXPECT_EQ(timeout, -1);
  looper->sendMessageDelayed(seconds_to_nanoseconds(10), handler, Message(2));
  looper->removeMessages(handler);
  looper->pollOnce(0);
  looper->getHostPollState(&fd, &timeout);
  EXPECT_EQ(timeout, -1);
}

TEST(HostLooper, BorrowedEpollReportsNativeCallbacksAndUnregistration) {
  auto looper = sp<Looper>::make(false);
  android::base::unique_fd event(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
  ASSERT_GE(event.get(), 0);
  int calls = 0;
  ASSERT_EQ(looper->addFd(
                event.get(), Looper::POLL_CALLBACK, Looper::EVENT_INPUT,
                [](int fd, int events, void *data) {
                  EXPECT_TRUE(events & Looper::EVENT_INPUT);
                  uint64_t value;
                  EXPECT_EQ(read(fd, &value, sizeof(value)), sizeof(value));
                  ++*static_cast<int *>(data);
                  return 0; // Stock callback contract: unregister.
                },
                &calls),
            1);
  looper->pollOnce(0);
  int fd, timeout;
  looper->getHostPollState(&fd, &timeout);
  uint64_t value = 1;
  ASSERT_EQ(write(event.get(), &value, sizeof(value)), sizeof(value));
  pollfd pending{fd, POLLIN, 0};
  ASSERT_EQ(poll(&pending, 1, 1000), 1);
  EXPECT_EQ(calls, 0); // The outer poll must not run callbacks.
  looper->pollOnce(0);
  EXPECT_EQ(calls, 1);
  ASSERT_EQ(write(event.get(), &value, sizeof(value)), sizeof(value));
  pending.revents = 0;
  EXPECT_EQ(poll(&pending, 1, 0), 0);
  looper->pollOnce(0);
  EXPECT_EQ(calls, 1);
}
} // namespace
