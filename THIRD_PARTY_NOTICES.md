# Third-party notices

## edge-tts protocol compatibility code

`EdgeSpeechGeneration.kt` and `EdgeSecurityToken.kt` are Android-native compatibility
implementations adapted from `rany2/edge-tts` 7.3.0, immutable commit
`6d3fff402a77dd4d43cccf074d042252770e41c2`: `src/edge_tts/constants.py`,
`drm.py`, and `communicate.py`.

- Project: https://github.com/rany2/edge-tts
- Copyright: rany and edge-tts contributors
- License for those referenced files: GNU Lesser General Public License v3.0 (LGPL-3.0).
  The upstream MIT exception applies only to `srt_composer.py`, which this app does not adapt.
- Pinned upstream license: https://github.com/rany2/edge-tts/blob/6d3fff402a77dd4d43cccf074d042252770e41c2/LICENSE

The adapted compatibility source remains available in this repository at
`app/src/main/java/com/yavin/wakeupsamurai/tts/`. The attribution and license notice must be
retained when those files are redistributed or modified.

The service contacted by this code is operated by Microsoft and is not provided by the
`edge-tts` project. No affiliation with or endorsement by Microsoft is implied.

The repository-level MIT LICENSE applies only to original Wake Up, Samurai material; it does
not relicense either adapted Kotlin file. The complete, unmodified official texts required for
the source distribution are `third_party/GNU-LGPL-3.0.txt` and
`third_party/GNU-GPL-3.0.txt`. `third_party/edge-tts-LICENSE.txt` is retained as the upstream
license notice, but is not represented as a substitute for those complete texts. See the
publication checklist in `docs/edge-tts-v2-migration.md`.
