#!/usr/bin/env python3
"""Check Chromium's actual decoder/overlay path, alongside UI pixel checks."""
import collections
import json
import sys

with open(sys.argv[1], encoding="utf-8") as trace:
    events = json.load(trace)["traceEvents"]
counts = collections.Counter(event.get("name", "") for event in events)
# TracingController strips event arguments for privacy. These event names
# identify the platform decoder, overlay creation and actual update callbacks;
# merely attempting ProcessForOverlays or scheduling zero planes is insufficient.
required = {
    "MediaCodecVideoDecoder::StartLazyInit": 1,
    "MediaCodecVideoDecoder::CreateVideoFrame": 2,
    "OverlayProcessorWebview::Manager::CreateOverlay": 1,
    "OverlayProcessorWebview::Manager::UpdateBufferInTransaction": 2,
    "OverlayProcessorWebview::Manager::OnHWUITransactionAck": 1,
    "OverlayProcessorWebview::Manager::OnUpdateBufferTransactionAck": 2,
    "SurfaceControlTransaction": 2,
}
missing = {name: counts[name] for name, minimum in required.items() if counts[name] < minimum}
if missing:
    raise SystemExit(f"Chromium platform overlay evidence missing: {missing}")
print("Chromium platform overlay: PASS " + json.dumps({name: counts[name] for name in required}))
