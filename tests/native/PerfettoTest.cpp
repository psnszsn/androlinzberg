// Exercise the pinned tracing client/service and generated packet ABI used by
// SurfaceFlinger. The in-process backend makes this independent of traced.
#include <perfetto/tracing.h>
#include <protos/perfetto/trace/test_event.pbzero.h>
#include <protos/perfetto/trace/trace.pbzero.h>
#include <protos/perfetto/trace/trace_packet.pbzero.h>

#include <cstdlib>
#include <cstdio>
#include <string_view>

class FrameSource : public perfetto::DataSource<FrameSource> {};
PERFETTO_DEFINE_DATA_SOURCE_STATIC_MEMBERS(FrameSource);

static void check(bool condition) {
  if (!condition) {
    std::fputs("Perfetto session contract failed\n", stderr);
    std::abort();
  }
}

int main() {
  perfetto::TracingInitArgs args;
  args.backends = perfetto::kInProcessBackend;
  perfetto::Tracing::Initialize(args);
  perfetto::DataSourceDescriptor descriptor;
  descriptor.set_name("azk.test.frames");
  FrameSource::Register(descriptor);

  for (uint64_t sessionId = 1; sessionId <= 3; ++sessionId) {
    perfetto::TraceConfig config;
    config.add_buffers()->set_size_kb(1024);
    config.add_data_sources()->mutable_config()->set_name("azk.test.frames");
    auto session = perfetto::Tracing::NewTrace(perfetto::kInProcessBackend);
    session->Setup(config);
    session->StartBlocking();
    for (uint64_t frame = 0; frame < 8; ++frame) {
      FrameSource::Trace([&](FrameSource::TraceContext ctx) {
        auto packet = ctx.NewTracePacket();
        packet->set_timestamp(sessionId * 100 + frame);
        packet->set_for_testing()->set_str("composed-frame");
      });
    }
    FrameSource::Trace([](FrameSource::TraceContext ctx) { ctx.Flush(); });
    check(session->FlushBlocking(5000));
    session->StopBlocking();
    const auto data = session->ReadTraceBlocking();
    perfetto::protos::pbzero::Trace::Decoder trace(
        reinterpret_cast<const uint8_t *>(data.data()), data.size());
    size_t frames = 0;
    for (auto packets = trace.packet(); packets; ++packets) {
      perfetto::protos::pbzero::TracePacket::Decoder packet(*packets);
      if (!packet.has_for_testing())
        continue;
      perfetto::protos::pbzero::TestEvent::Decoder event(packet.for_testing());
      const auto value = event.str();
      check(std::string_view(value.data, value.size) == "composed-frame");
      check(packet.timestamp() == sessionId * 100 + frames);
      ++frames;
    }
    check(frames == 8);
  }
  perfetto::Tracing::Shutdown();
  std::puts("Perfetto: three independent sessions, 24 ordered frame packets");
}
