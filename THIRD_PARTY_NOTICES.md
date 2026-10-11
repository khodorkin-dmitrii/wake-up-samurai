# Third-party notices

## edge-tts protocol compatibility code

`EdgeSpeechGeneration.kt` is an Android-native compatibility implementation derived from
the protocol behavior documented in the `rany2/edge-tts` project, release 7.2.8, especially
`constants.py`, `drm.py`, and `communicate.py`.

- Project: https://github.com/rany2/edge-tts
- Copyright: rany and edge-tts contributors
- License for the referenced files: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Upstream license text: https://github.com/rany2/edge-tts/blob/master/LICENSE

The adapted compatibility source remains available in this repository at
`app/src/main/java/com/yavin/wakeupsamurai/tts/EdgeSpeechGeneration.kt`. The attribution and
license notice must be retained when that file is redistributed or modified.

The service contacted by this code is operated by Microsoft and is not provided by the
`edge-tts` project. No affiliation with or endorsement by Microsoft is implied.
