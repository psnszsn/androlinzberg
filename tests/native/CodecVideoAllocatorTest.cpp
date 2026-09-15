#include "../../src/codec/azk_codec.h"
#include "Codec2VideoFixtures.h"
#include "GpuBufferRenderer.h"
#include <android/binder_process.h>
#include <fcntl.h>
#include <iostream>
#include <set>
#include <sys/stat.h>

using namespace android;
int main() {
    ABinderProcess_startThreadPool();
    GpuBufferRenderer renderer;
    const auto packets = videoPackets();
    const auto baseline = azk_graphics_live_buffers();
    auto *decoder = azk_codec_open("h264");
    assert(decoder && azk_codec_use_video_allocator(decoder) == 0);
    assert(azk_codec_configure(decoder, nullptr, 0, 0, 0) == 0);
    assert(azk_codec_use_video_allocator(decoder) < 0);
    // Repeat after flush, retaining the final output allocation across flush
    // and decoder destruction to verify independent destination ownership.
    sp<GraphicBuffer> retained;
    for (int pass = 0; pass < 3; ++pass) {
        std::set<uint64_t> expected, output, completed;
        for (size_t i = 0; i < packets.size(); ++i) {
            const uint64_t index = UINT64_MAX - i;
            expected.insert(index);
            assert(azk_codec_send_work(decoder, packets[i].data(), packets[i].size(), 12345, index) == 0);
        }
        assert(azk_codec_send(decoder, nullptr, 0, 0, 1) == 0);
        int meta[4]; int64_t pts; uint64_t index;
        while (azk_codec_peek_video_work(decoder, pass == 2, meta, &pts, &index) == 0) {
            assert(meta[1] == 318 && meta[2] == 238 && meta[3] != 0 && pts == 12345);
            assert(expected.count(index) && output.insert(index).second);
            if (pass == 2) {
                assert(((meta[3] >> 27) & 7) == 2); // unexpanded, limited-range YUV
                const int ys = 320, cs = 160;
                std::vector<uint8_t> y(ys * 238, 0xff), u(cs * 119, 0xff), v(cs * 119, 0xff);
                assert(azk_codec_read_video_work(decoder, y.data(), 1, u.data(), v.data(), cs) < 0);
                uint64_t retry;
                assert(azk_codec_peek_video_work(decoder, 1, meta, &pts, &retry) == 0 && retry == index);
                const auto count = azk_graphics_live_buffers();
                assert(azk_codec_read_video_work(decoder, y.data(), ys, u.data(), v.data(), cs) == 0);
                assert(azk_graphics_live_buffers() == count);
                assert(y[0] > 60 && y[0] < 100 && u[0] > 70 && u[0] < 120 && v[0] > 200);
                assert(y[237 * ys + 317] == y[0] && u[118 * cs + 158] == u[0]);
                assert(y[318] == 0xff && u[159] == 0xff && v[159] == 0xff);
                while (azk_codec_poll_completed_work(decoder, &index) == 1)
                    assert(completed.insert(index).second);
                continue;
            }
            const uint64_t usage = GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_HW_RENDER |
                    (pass == 0 ? GRALLOC_USAGE_SW_READ_OFTEN : 0);
            auto buffer = sp<GraphicBuffer>::make(meta[1], meta[2], PIXEL_FORMAT_RGBX_8888,
                                                  1, usage, "Codec2 VAAPI destination test");
            assert(buffer->initCheck() == OK);
            AzkGraphicsBufferInfo target{};
            assert(azk_graphics_describe(buffer->handle, &target) == 0);
            struct stat before{}, after{};
            assert(fstat(target.fd, &before) == 0);
            auto invalid = target; invalid.width++;
            assert(azk_codec_render_video_work(decoder, &invalid) < 0);
            uint64_t retryIndex;
            assert(azk_codec_peek_video_work(decoder, 0, meta, &pts, &retryIndex) == 0 && retryIndex == index);
            const auto count = azk_graphics_live_buffers();
            assert(azk_codec_render_video_work(decoder, &target) == 0);
            assert(azk_graphics_live_buffers() == count);
            assert(fstat(target.fd, &after) == 0 && before.st_ino == after.st_ino && before.st_dev == after.st_dev);
            assertRed(renderer.readPixel(buffer));
            retained = buffer;
            while (azk_codec_poll_completed_work(decoder, &index) == 1)
                assert(completed.insert(index).second);
        }
        assert(azk_codec_peek_video_work(decoder, 0, meta, &pts, &index) == 2);
        while (azk_codec_poll_completed_work(decoder, &index) == 1)
            assert(completed.insert(index).second);
        assert(output == expected && completed == expected);
        azk_codec_flush(decoder);
        assert(azk_codec_poll_completed_work(decoder, &index) == 0);
        assertRed(renderer.readPixel(retained));
    }
    azk_codec_close(decoder);
    assertRed(renderer.readPixel(retained));
    retained.clear();
    assert(azk_graphics_live_buffers() == baseline);
    std::cout << "PASS allocator DMA-BUF video: crop, B-frame work identity, duplicate PTS, "
                 "linear/GPU layouts, CPU YUV, retry, flush and retained output pixels" << std::endl;
}
