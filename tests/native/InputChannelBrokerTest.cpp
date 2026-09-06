// Stock InputChannelCore carries a socket and Binder token over AZKB. Input
// events subsequently use the stock channel protocol, independently of Binder.
#include <binder/Binder.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <binder/ProcessState.h>
#include <input/InputTransport.h>
#include <input/InputConsumer.h>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <thread>

using namespace android;
using namespace std::chrono_literals;

struct Service final : BBinder {
  std::shared_ptr<InputChannel> server;
  std::unique_ptr<InputChannel> client;
  std::unique_ptr<InputConsumer> consumer;
  PreallocatedInputEventFactory factory;
  Service() {
    std::unique_ptr<InputChannel> local;
    assert(InputChannel::openInputChannelPair("HostInputContract", local,
                                              client) == OK);
    server = std::move(local);
    consumer = std::make_unique<InputConsumer>(server);
  }
  status_t onTransact(uint32_t code, const Parcel &request, Parcel *reply,
                      uint32_t flags) override {
    if (code == 1) {
      assert(client);
      os::InputChannelCore core;
      InputChannel::moveChannel(std::move(client), core);
      // Both the source InputChannel and this parcelable are destroyed before
      // the receiving client uses its channel.
      return core.writeToParcel(reply);
    }
    if (code == 2) {
      uint32_t seq = 0;
      InputEvent *event = nullptr;
      assert(consumer->consume(&factory, false, -1, &seq, &event) == OK);
      assert(seq == 42 && event && event->getType() == InputEventType::KEY);
      const auto &key = static_cast<const KeyEvent &>(*event);
      assert(key.getAction() == AKEY_EVENT_ACTION_DOWN);
      assert(key.getKeyCode() == AKEYCODE_ENTER && key.getDeviceId() == 7);
      assert(key.getDisplayId() == ui::LogicalDisplayId::DEFAULT);
      assert(key.getEventTime() == 200 && key.getDownTime() == 100);
      return consumer->sendFinishedSignal(seq, true);
    }
    return BBinder::onTransact(code, request, reply, flags);
  }
};

struct Death final : IBinder::DeathRecipient {
  std::mutex mutex;
  std::condition_variable changed;
  bool died = false;
  void binderDied(const wp<IBinder> &) override {
    std::lock_guard lock(mutex);
    died = true;
    changed.notify_all();
  }
};

int main(int argc, char **argv) {
  ProcessState::self()->startThreadPool();
  if (argc > 1 && std::string(argv[1]) == "server") {
    auto service = sp<Service>::make();
    assert(defaultServiceManager()->addService(
               String16("dev.artzig.input-channel"), service) == OK);
    std::cout << "ready" << std::endl;
    char ch;
    while (std::cin.get(ch)) {
    }
    return 0;
  }
  auto service = defaultServiceManager()->checkService(
      String16("dev.artzig.input-channel"));
  assert(service && service->remoteBinder());
  auto death = sp<Death>::make();
  assert(service->linkToDeath(death) == OK);
  Parcel request, response;
  assert(service->transact(1, request, &response) == OK);
  os::InputChannelCore core;
  assert(core.readFromParcel(&response) == OK);
  response.freeData();
  auto channel = InputChannel::create(std::move(core));
  assert(channel && channel->getName() == "HostInputContract");
  assert(channel->getConnectionToken() &&
         channel->getConnectionToken()->remoteBinder());
  std::shared_ptr<InputChannel> duplicate = channel->dup();
  assert(duplicate &&
         duplicate->getConnectionToken() == channel->getConnectionToken());
  channel.reset();
  InputPublisher publisher(duplicate);
  const auto publish = [&] {
    return publisher.publishKeyEvent(
        42, 1, 7, AINPUT_SOURCE_KEYBOARD, ui::LogicalDisplayId::DEFAULT, {},
        AKEY_EVENT_ACTION_DOWN, 0, AKEYCODE_ENTER, 28, 0, 0, 100, 200);
  };
  assert(publish() == OK);
  assert(service->transact(2, request, &response) == OK);
  auto finished = publisher.receiveConsumerResponse();
  assert(finished.ok() &&
         std::holds_alternative<InputPublisher::Finished>(*finished));
  const auto &ack = std::get<InputPublisher::Finished>(*finished);
  assert(ack.seq == 42 && ack.handled && ack.consumeTime > 0);
  std::cout << "PASS stock InputChannelCore AZKB transfer, token identity and "
               "input acknowledgements"
            << std::endl;
  std::cout << "READY_DEATH" << std::endl;
  {
    std::unique_lock lock(death->mutex);
    assert(death->changed.wait_for(lock, 5s, [&] { return death->died; }));
  }
  auto closed = publisher.receiveConsumerResponse();
  assert(!closed.ok() && closed.error().code() == DEAD_OBJECT);
  assert(publish() == DEAD_OBJECT);
  std::cout << "PASS stock input channel observes service peer close"
            << std::endl;
}
