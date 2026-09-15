# Media contract fixtures

Small synthetic inputs for stock extractor packet delivery and remote thumbnail
regressions. They contain generated tones, silent codec frames, or solid red
video; no third-party media is included. Parsing tests do not imply that every
contained codec is available for decoding.

Regenerate from the repository root in the development shell (FFmpeg 9.0):

```python
from pathlib import Path
import subprocess
out=Path("testdata/media")
ffmpeg="ffmpeg"
jobs=[("tone.flac",["-c:a","flac"]),("tone.ogg",["-c:a","libvorbis"]),("tone.opus",["-c:a","libopus"]),("tone.ts",["-c:a","aac","-f","mpegts"]),("tone.mpg",["-c:a","mp2","-f","vob"])]
for name,args in jobs:
 duration = 1 if name in ("tone.ts", "tone.mpg") else 0.25
 subprocess.run([ffmpeg,"-v","error","-f","lavfi","-i",f"sine=frequency=440:sample_rate=48000:duration={duration}",*args,"-y",str(out/name)],check=True)
subprocess.run([ffmpeg,"-v","error","-f","lavfi","-i","color=c=red:s=128x96:r=4:d=0.5","-c:v","libvpx-vp9","-pix_fmt","yuv420p","-y",str(out/"thumbnail.webm")],check=True)
# 20 valid AMR-NB mode-0 frames, 12 payload bytes following each TOC.
(out/"silence.amr").write_bytes(b"#!AMR\n"+(b"\x04"+bytes(12))*20)
```

Generate the AVC thumbnail and its title metadata with:

```sh
ffmpeg -f lavfi -i color=c=red:s=128x96:r=1 -frames:v 2 -c:v libx264 \
  -pix_fmt yuv420p -x264-params keyint=1 -bsf:v filter_units=remove_types=6 \
  -metadata title='Stock thumbnail' -movflags +faststart -y testdata/media/thumbnail.mp4
```
