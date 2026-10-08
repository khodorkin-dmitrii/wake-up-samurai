---
name: android-project-starter
description: Bootstrap a new Kotlin/Compose Android project with selectively installed official Android agent skills and reusable UI lessons from Android Handbook Audio. Use when starting a new Android app or explicitly adapting this project's UI patterns; not for routine feature work in the current app.
---

# Android project starter

Read [references/ANDROID_PROJECT_STARTER_PLAYBOOK.md](references/ANDROID_PROJECT_STARTER_PLAYBOOK.md) completely when the user asks to start a new Android app or reuse the Handbook Audio UI decisions.

Before acting, inspect the **target** project's requirements, repository instructions, existing build, and Git state. Use the playbook as a source of options and known pitfalls, not as a template to copy wholesale. Keep project policy in the target project's `AGENTS.md`, and select only skills relevant to its actual architecture. Check current Android CLI skill IDs and installed locations before changing skill setup; use the CLI for official skills, never `--all` by default. Preserve existing custom skills.

If the user only requests a plan or review, do not install skills or modify the app. If implementation is requested, make the smallest coherent changes, verify the applicable build/tests, and report any differences from the playbook. Media3, transcripts, animated ambient progress, and theme-reveal effects are product-specific, optional patterns.
