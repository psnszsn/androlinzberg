# Desktop behavior

Launch an APK with `androlinzberg app.apk`. Stock Android window and activity
services manage it in a shared session; each Android task gets one Wayland
toplevel. Activities, dialogs, popups, SurfaceView and video within that task
remain Android windows/layers. `--join <session-dir>` adds an APK to an existing
session. Closing a task follows Android's task lifecycle.

The graphics path requires x86_64 Linux, a GBM/EGL-capable GPU and a Wayland
compositor with xdg-shell and compatible DMA-BUF import. SurfaceFlinger composes
GPU buffers for the presenter. There is no continuous screenshot/readback path
or fallback to the removed global renderer.

| Area | Behavior and limits |
| --- | --- |
| Windows | Independent task toplevels, attached dialogs/popups, Android layer transforms and video. Taskless system windows are rejected after the Android permission checks. |
| Fullscreen and resize | As in Android desktop mode, an application's immersive request hides its system bars inside its current window and never resizes it. F11 or the compositor makes a task fullscreen; a fullscreen entered while the application was immersive ends when the application leaves immersive. Resize and output scale changes update task configuration; activities receive callbacks or saved-state relaunch according to their manifest. |
| Coordinates | Android uses synthetic display/task coordinates. They are not physical global desktop positions. Input and inspection explicitly translate between Android, task and surface coordinates. |
| Input | Mouse, keyboard, scroll, touch and advertised tablet devices use stock InputDispatcher and input channels. Keyboard layout follows the compositor. Pointer capture and other optional features require the corresponding compositor protocols. |
| Text editing | Physical keyboard/editor actions and compositor text-input-v3 preedit/commit are supported. No Android software-IME service is installed: Android panel show/hide return false, the service inventory is empty and IME height is zero. A compositor input method owns its own panel UI. |
| Clipboard and drag | Text, HTML and supported URI transfers use bounded session storage, Android grants and task-addressed drop/input policy. Intent clipboard export and mixed URI/non-URI item lists are unsupported. |
| Accessibility | Inspection and actions are scoped to registered task/window identities, including virtual descendants. Stale window/process generations cannot receive actions. |
| Secure layers | Private task composition outputs preserve SurfaceFlinger's secure-layer exclusion policy. Secure content is not exported as ordinary desktop pixels. |
| Video | FFmpeg/VA-API frames pass through separately allocated RGBX buffers into stock Android surfaces. Supported SDR conversion preserves color metadata; unsupported HDR conversion is rejected. |
| PiP | Picture-in-picture is not advertised; the Activity entry point returns false. |
| Service loss | Fatal broker, graphics, presenter, system or compositor loss retires the affected session and its clients with bounded cleanup. Old Binder objects and surface IDs are not reconnected to a replacement service. |

The allocator, Binder transport, imported frames and desktop transfers have
separate resource limits. Mapper byte accounting estimates retained buffer
storage; it does not measure total physical GPU memory.

`AZK_WINDOW_BACKEND=stock` remains accepted for existing scripts. The legacy
backend has been removed. `AZK_TASK_WINDOWS=0` is only useful for headless
framework probes; ordinary APK launches require task presentation. The former
`--java`, `--frames` and `--fullscreen` presentation options are unavailable.

See [the architecture](architecture.md), [native boundaries](native-boundary.md)
and [acceptance testing](acceptance.md) for source ownership, protocol gates
and regression artifacts.
