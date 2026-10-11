# Edge TTS Android feasibility spike

Date: 2026-10-11

## Result

The direct Android flow works without a Python runtime and without an application backend:
editor text is sent from the app over WebSocket, the returned MP3 frames are written to an
app-owned temporary file, the file is validated, promoted to durable app-owned storage, and
played locally through Media3.

This is an experimental compatibility integration, not a supported Microsoft public API.
The endpoint, browser identity, token calculation, framing, voices, or access policy can change
without notice.

## Integration method and upstream references

The Kotlin client follows the protocol behavior in `rany2/edge-tts` 7.2.8:

- [project README](https://github.com/rany2/edge-tts/blob/7.2.8/README.md);
- [protocol constants](https://github.com/rany2/edge-tts/blob/7.2.8/src/edge_tts/constants.py);
- [Sec-MS-GEC token and MUID handling](https://github.com/rany2/edge-tts/blob/7.2.8/src/edge_tts/drm.py);
- [WebSocket request/response framing](https://github.com/rany2/edge-tts/blob/7.2.8/src/edge_tts/communicate.py);
- [7.2.8 release](https://github.com/rany2/edge-tts/releases/tag/7.2.8).

The app connects directly to the consumer Edge `readaloud/edge/v1` WebSocket endpoint. It sends
the same restricted SSML shape used by Edge and receives `audio/mpeg` frames. English uses
`en-US-EmmaMultilingualNeural`; drafts containing Cyrillic use `ru-RU-SvetlanaNeural`. There is no
voice-selection UI in this spike.

The implementation deliberately sits behind `SpeechGeneration`. OkHttp owns the WebSocket and
Media3 is hidden behind `AudioPreview`; neither protocol nor player ownership leaks into Compose.
No request/response logging is enabled, and user text or audio content is not logged.

## Storage and lifecycle

Generation writes a `.mp3.part` file under `files/speech`, requires a non-empty MP3 stream and a
readable positive duration, then promotes it to a UUID-named `.mp3`. Failed and cancelled temporary
files are removed. Every successfully completed file is retained as a reusable local asset,
including older generations and results that finish after their editor revision has become stale.
Only the current revision can replace the editor preview. Audio-library UI and explicit asset
deletion/cleanup are deferred.

The 45-second editor timeout and explicit Cancel action cancel the WebSocket without blocking the
main thread. Editing during generation also cancels the current request. Duplicate Generate taps
are ignored. A failed request can be retried for the current revision, while a previous valid asset
remains intact. Leaving the editor releases the Media3 player but does not delete completed audio.

## Dependencies and licensing

- OkHttp 5.5.0: WebSocket transport; no logging interceptor.
- AndroidX Media3 ExoPlayer 1.11.1: local preview playback.
- Existing Kotlin Coroutines 1.11.0: cancellation, timeout, and asynchronous state handling.

The referenced upstream files are LGPL-3.0. Attribution is recorded in
`THIRD_PARTY_NOTICES.md`, the compatibility source carries a notice, and the upstream license
location is recorded in `third_party/edge-tts-LICENSE.txt`. No Python package is bundled. The
project's MIT-only `srt_composer.py` was not used.

## Device observations

Test device: Samsung SM-S918B, primary Android user 0. The APK and instrumentation APK were
installed explicitly for user 0 only.

- Supplied English phrase: generated and validated as a 24,336-byte MP3, duration 4.0 s. The
  end-to-end UI observation reached Ready in approximately 3.0 s (polling granularity included).
- Russian message (`Доброе утро. Пора вставать.`): generated natively with the Russian fixed voice
  in 655 ms on the first run and 935 ms after the offline-failure probe; 25,344 bytes, duration
  4.224 s.
- Offline replay: with Wi-Fi and mobile data both disabled, Media3 entered Playing for the already
  generated English local file. Both network settings were restored to their original enabled state.
- With Wi-Fi and mobile data disabled, the direct client produced a `SpeechGenerationException`
  in 93 ms. After both connections were restored, the Russian request succeeded in 935 ms. The
  editor retry transition is additionally covered by a JVM test.
- Cancellation completed in the device probe without leaving any `.part` file. Completed English
  and Russian MP3 files remained in `files/speech` after preview replacement, editor/process exit,
  APK replacement, and subsequent generations.

No screenshot, golden, or other visual device testing was performed, per project instruction.

## Stability limits and recommendation

Recent upstream releases have had to change endpoints and Edge identity values, which is direct
evidence that this compatibility protocol is volatile. Clock skew, server-side throttling, regional
availability, voice removal, Edge protocol changes, or Microsoft access-policy changes can break
generation independently of an app release. There is no service-level agreement, public API
contract, or production support path.

For Step 4, keep this implementation explicitly experimental behind `SpeechGeneration`; never
depend on network synthesis while an alarm is ringing, and only enable an alarm after a completed
local asset is readable. Before a production release, choose deliberately between maintaining this
compatibility client and moving to a supported TTS service (which may require a controlled backend
to protect credentials). Add a bundled fallback sound regardless of that choice.
