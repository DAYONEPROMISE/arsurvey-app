# OpenPencil pass — arsurvey-app

## Design (Shared/OpenPencil/arsurvey-app/)
- `design.html` (dark-first capture flow: 3-step progress + 56dp capture button, DZ dark tokens)
  → `design.fig` via `openpencil import`
- `lint --preset accessibility`: 3 contrast errors on first pass
  (hint text + inactive step pills on dark surface) → fixed (solid pills, brighter hint)
  → re-lint: 0 errors. Genuine OpenPencil-driven fix.
- Note: dark-only UI is intentional (camera viewfinder); no light-mode change proposed.

## Code review (no rewrite needed)
`MainActivity.kt` camera-permission gate + capture-first flow left intact — AR session code
is high-risk to touch blind. Dark `MaterialTheme` matches the viewfinder design.

## APKs (release as requested)
`build-debug.yml` now also runs `:app:assembleRelease` (unsigned) and uploads `app-release`
alongside `app-debug`. GitHub Releases publishing (debug) unchanged. Release APK per run in Artifacts.
