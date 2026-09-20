// Wire oracle of the Zig AIDL backend self-test (src/binder/aidl_test.zig).
//
// tests/aidl-zig is compiled here by the pinned NDK backend. `Probe` implements
// the contract src/binder/aidl_test.zig implements in Zig, and drive() repeats
// its expectations from a stock generated proxy, so both directions of the
// Zig backend meet unmodified AOSP-generated marshalling code.

#include <aidl/dev/artzig/probe/BnProbe.h>
#include <aidl/dev/artzig/probe/BnProbeCallback.h>
#include <android/binder_ibinder.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <memory>
#include <optional>
#include <string>
#include <vector>

using namespace aidl::dev::artzig::probe;
using ndk::ScopedAStatus;
using ndk::ScopedFileDescriptor;
using ndk::SpAIBinder;

namespace {

class Probe : public BnProbe {
  ScopedAStatus add(int32_t a, int32_t b, int32_t* result) override {
    *result = a + b;
    return ScopedAStatus::ok();
  }
  ScopedAStatus echo(const std::string& text, std::string* result) override {
    *result = text;
    return ScopedAStatus::ok();
  }
  ScopedAStatus maybe(const std::optional<std::string>& text,
                      std::optional<std::string>* result) override {
    *result = text;
    return ScopedAStatus::ok();
  }
  static Shape Copy(const Shape& shape) {
    Shape copy;
    copy.kind = shape.kind;
    copy.name = shape.name;
    copy.label = shape.label;
    copy.origin = shape.origin;
    copy.vertices = shape.vertices;
    copy.weights = shape.weights;
    copy.blob = shape.blob;
    copy.rgb = shape.rgb;
    copy.tag = shape.tag;
    copy.scale = shape.scale;
    copy.visible = shape.visible;
    copy.glyph = shape.glyph;
    copy.anchor = shape.anchor;
    copy.token = shape.token;
    copy.tags = shape.tags;
    if (shape.child) copy.child = std::make_unique<Shape>(Copy(*shape.child));
    return copy;
  }
  ScopedAStatus transform(const Shape& shape, Shape* mirrored, Point* cursor,
                          Shape* result) override {
    *result = Copy(shape);
    *mirrored = Copy(shape);
    mirrored->name = "mirror:" + shape.name;
    cursor->x += 1;
    cursor->y += shape.rgb[0];
    return ScopedAStatus::ok();
  }
  ScopedAStatus choose(const Choice& choice, Choice* result) override {
    switch (choice.getTag()) {
      case Choice::number:
        result->set<Choice::number>(choice.get<Choice::number>() + 1);
        break;
      case Choice::text: {
        Point point;
        point.x = static_cast<int32_t>(choice.get<Choice::text>().size());
        point.y = 7;
        result->set<Choice::point>(point);
        break;
      }
      case Choice::point:
        result->set<Choice::kinds>(std::vector<Kind>{Kind::CIRCLE, Kind::SQUARE});
        break;
      case Choice::kinds:
        result->set<Choice::number>(static_cast<int32_t>(choice.get<Choice::kinds>().size()));
        break;
    }
    return ScopedAStatus::ok();
  }
  ScopedAStatus scale(const std::vector<int64_t>& values, int64_t factor,
                      std::vector<int64_t>* doubled, std::vector<int64_t>* result) override {
    result->clear();
    doubled->clear();
    for (int64_t value : values) {
      result->push_back(value * factor);
      doubled->push_back(value * 2);
    }
    return ScopedAStatus::ok();
  }
  ScopedAStatus relay(const std::shared_ptr<IProbeCallback>& callback, int32_t question,
                      int32_t* result) override {
    if (!callback->ask(question, result).isOk()) return ScopedAStatus::fromStatus(STATUS_UNKNOWN_ERROR);
    return callback->onEvent(*result, "relayed");
  }
  ScopedAStatus fail(int32_t mode) override {
    switch (mode) {
      case 1:
        return ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, "mode one");
      case 2:
        return ScopedAStatus::fromServiceSpecificErrorWithMessage(77, "mode two");
      case 3:
        return ScopedAStatus::fromExceptionCode(EX_SECURITY);
      default:
        return ScopedAStatus::ok();
    }
  }
  ScopedAStatus reopen(const ScopedFileDescriptor& descriptor,
                       ScopedFileDescriptor* result) override {
    *result = descriptor.dup();
    return ScopedAStatus::ok();
  }
  ScopedAStatus same(const SpAIBinder& binder, SpAIBinder* result) override {
    *result = binder;
    return ScopedAStatus::ok();
  }
  ScopedAStatus next(Kind kind, Kind* result) override {
    *result = kind == Kind::NONE     ? Kind::CIRCLE
              : kind == Kind::CIRCLE ? Kind::SQUARE
              : kind == Kind::SQUARE ? Kind::NONE
                                     : kind;
    return ScopedAStatus::ok();
  }
  ScopedAStatus names(const std::optional<std::vector<std::optional<std::string>>>& names,
                      std::optional<std::vector<std::optional<std::string>>>* result) override {
    *result = names;
    return ScopedAStatus::ok();
  }
};

class Listener : public BnProbeCallback {
 public:
  int32_t asked = 0;
  // Published last: a oneway call lands on a Binder thread after relay() returns.
  std::atomic<int32_t> event = 0;
  std::string event_text;

 private:
  ScopedAStatus ask(int32_t question, int32_t* result) override {
    asked = question;
    *result = question * 2;
    return ScopedAStatus::ok();
  }
  ScopedAStatus onEvent(int32_t id, const std::string& text) override {
    event_text = text;
    event = id;
    return ScopedAStatus::ok();
  }
};

#define EXPECT(condition) \
  do {                    \
    if (!(condition)) return __LINE__; \
  } while (false)

int Drive(const std::shared_ptr<IProbe>& probe) {
  int32_t sum = 0;
  EXPECT(probe->add(40, 2, &sum).isOk() && sum == IProbe::MAGIC);
  std::string text;
  EXPECT(probe->echo("grüße, 世界", &text).isOk() && text == "grüße, 世界");
  EXPECT(probe->echo("", &text).isOk() && text.empty());
  std::optional<std::string> optional = "stale";
  EXPECT(probe->maybe(std::nullopt, &optional).isOk() && !optional.has_value());
  EXPECT(probe->maybe("x", &optional).isOk() && optional == "x");

  Shape shape;
  shape.label = "label";
  shape.origin.x = 3;
  Point first, second;
  first.x = 1, first.y = 2, second.x = 3, second.y = 4;
  shape.vertices = {first, second};
  shape.weights = {1, -2, INT64_MAX};
  shape.blob = {0, 1, 0xff};
  shape.tag = {9, 8, 7, 6};
  shape.glyph = 0x4e16;
  Point anchor;
  anchor.x = -1, anchor.y = -2;
  shape.anchor = anchor;
  shape.tags = {"a", "bc"};
  shape.child = std::make_unique<Shape>();
  shape.child->name = "child";
  shape.child->kind = Kind::CIRCLE;
  Shape mirrored, result;
  Point cursor;
  cursor.x = 10, cursor.y = 20;
  EXPECT(probe->transform(shape, &mirrored, &cursor, &result).isOk());
  EXPECT(result.kind == Kind::SQUARE && result.scale == 1.5 && result.visible && result.glyph == 0x4e16);
  EXPECT(result.name == "shape" && result.label == "label");
  EXPECT(result.origin.x == 3 && result.origin.y == 7);
  EXPECT(result.vertices.size() == 2 && result.vertices[1].x == 3 && result.vertices[1].y == 4);
  EXPECT(result.weights == shape.weights && result.blob == shape.blob);
  EXPECT((result.rgb == std::array<int32_t, 3>{1, 2, 3}) && result.tag == shape.tag);
  EXPECT(result.anchor.has_value() && result.anchor->y == -2 && result.token.get() == nullptr);
  EXPECT(result.tags.size() == 2 && result.tags[1] == "bc");
  EXPECT(result.child && result.child->kind == Kind::CIRCLE && !result.child->child);
  EXPECT(mirrored.name == "mirror:shape");
  EXPECT(cursor.x == 11 && cursor.y == 21);

  Choice answer;
  EXPECT(probe->choose(Choice::make<Choice::number>(5), &answer).isOk() &&
         answer.getTag() == Choice::number && answer.get<Choice::number>() == 6);
  EXPECT(probe->choose(Choice::make<Choice::text>("four"), &answer).isOk() &&
         answer.getTag() == Choice::point && answer.get<Choice::point>().x == 4 &&
         answer.get<Choice::point>().y == 7);
  EXPECT(probe->choose(Choice::make<Choice::point>(), &answer).isOk() &&
         answer.getTag() == Choice::kinds &&
         answer.get<Choice::kinds>() == (std::vector<Kind>{Kind::ROUND, Kind::SQUARE}));
  EXPECT(probe->choose(Choice::make<Choice::kinds>(std::vector<Kind>(3, Kind::NONE)), &answer).isOk() &&
         answer.getTag() == Choice::number && answer.get<Choice::number>() == 3);

  std::vector<int64_t> scaled, doubled(3);
  EXPECT(probe->scale({1, 2, 3}, 10, &doubled, &scaled).isOk());
  EXPECT(scaled == (std::vector<int64_t>{10, 20, 30}) && doubled == (std::vector<int64_t>{2, 4, 6}));

  auto listener = ndk::SharedRefBase::make<Listener>();
  int32_t relayed = 0;
  EXPECT(probe->relay(listener, 21, &relayed).isOk() && relayed == 42 && listener->asked == 21);
  for (int waited = 0; listener->event == 0 && waited < 2000; waited++) usleep(1000);
  EXPECT(listener->event == 42 && listener->event_text == "relayed");

  EXPECT(probe->fail(0).isOk());
  ScopedAStatus failure = probe->fail(1);
  EXPECT(failure.getExceptionCode() == EX_ILLEGAL_ARGUMENT && std::string(failure.getMessage()) == "mode one");
  failure = probe->fail(2);
  EXPECT(failure.getExceptionCode() == EX_SERVICE_SPECIFIC && failure.getServiceSpecificError() == 77 &&
         std::string(failure.getMessage()) == "mode two");
  EXPECT(probe->fail(3).getExceptionCode() == EX_SECURITY);

  ScopedFileDescriptor source(open("/proc/self/exe", O_RDONLY | O_CLOEXEC));
  ScopedFileDescriptor reopened;
  EXPECT(source.get() >= 0 && probe->reopen(source, &reopened).isOk() && reopened.get() >= 0);
  struct stat expected {}, actual {};
  EXPECT(fstat(source.get(), &expected) == 0 && fstat(reopened.get(), &actual) == 0);
  EXPECT(reopened.get() != source.get() && expected.st_ino == actual.st_ino);

  SpAIBinder binder;
  EXPECT(probe->same(nullptr, &binder).isOk() && binder.get() == nullptr);
  EXPECT(probe->same(listener->asBinder(), &binder).isOk() && binder.get() == listener->asBinder().get());

  Kind kind = Kind::NONE;
  EXPECT(probe->next(Kind::CIRCLE, &kind).isOk() && kind == Kind::SQUARE);
  EXPECT(probe->next(static_cast<Kind>(99), &kind).isOk() && kind == static_cast<Kind>(99));
  std::optional<std::vector<std::optional<std::string>>> listed;
  EXPECT(probe->names(std::nullopt, &listed).isOk() && !listed.has_value());
  EXPECT(probe->names(std::vector<std::optional<std::string>>{"a", std::nullopt, ""}, &listed).isOk());
  EXPECT(listed.has_value() && listed->size() == 3 && (*listed)[0] == "a" && !(*listed)[1].has_value() &&
         (*listed)[2] == "");
  return 0;
}

}  // namespace

extern "C" AIBinder* azk_probe_ndk_new() {
  // The Binder object keeps its Probe alive; hand the caller one strong reference.
  SpAIBinder binder = ndk::SharedRefBase::make<Probe>()->asBinder();
  AIBinder_incStrong(binder.get());
  return binder.get();
}

extern "C" int azk_probe_ndk_drive(AIBinder* binder) {
  AIBinder_incStrong(binder);
  return Drive(IProbe::fromBinder(SpAIBinder(binder)));
}
