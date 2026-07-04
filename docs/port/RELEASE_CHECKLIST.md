# Before making this repo public

- [x] **App icon**: composited from the game's own `.ico` art. Decision (2026-07-04):
      ship as-is — it derives from the GPL-released game's own resources and stays
      within the same fair-use posture as screenshots/videos of the port. Revisit
      only if EA objects.
- [ ] **Assets**: re-verify no game data slipped into either repo — `git ls-files`
      must show no `*.big`, `*.bik`, `GameData/`, or anything from `~/GeneralsX/`.
- [ ] **License**: both repos ship GPL v3 only (EA's source release, via GeneralsX).
- [ ] **Fonts**: do not commit the renamed Liberation fonts; document the rename
      (`LiberationSans-Regular.ttf` → `arial.ttf`, etc.) instead. Liberation's own
      license permits redistribution, but renamed-to-arial copies invite confusion.
- [ ] **Prebuilt binaries**: the DXVK d3d8/d3d9 dylibs, MoltenVK.framework, SDL3,
      OpenAL, and GameSpy dylibs embedded by `package-ios-zh.sh` are not in git.
      `patches/dxvk-ios.patch` + `cmake/meson-arm64-ios-cross.ini` make DXVK
      reproducible; add pinned fetch/build notes for the rest or attach release
      artifacts with license notices (DXVK: zlib, MoltenVK: Apache-2.0, SDL3: zlib).
- [x] **Signing identifiers**: done — `GX_TEAM_ID`/`GX_BUNDLE_ID`/`GX_SIGN_IDENTITY`
      env overrides in the packaging script; project.yml documents them.
- [x] **Device UUIDs**: verified — the packaging script auto-detects the connected
      device; nothing hardcoded.
- [x] **Steam credentials**: verified — username is an argument, nothing cached
      in-repo, `.steamcmd_zh/` gitignored.
- [ ] **Upstreaming**: offer the genuine fixes back to fbraz3/GeneralsX — the radar
      alpha-fallback, streamed-speech EOF propagation + disallowSpeech self-heal,
      the pkg-config framework-token fix, and the iOS lifecycle pause pattern all
      benefit upstream (several are macOS-relevant too).
- [x] **Known issues**: README now has a Known Issues section (memory-pressure
      kills on long iPad sessions, rare backgrounding crash).
- [x] **README pass**: swept 2026-07-04 — all paths are `$HOME`-relative; no
      personal absolute paths in either repo.
