## What does this change?

<!-- What is different for someone using the app, not only what changed in the code. -->

## Why?

<!-- Link an issue if there is one. -->

## How was it tested?

<!--
Every bug found in this project so far was about where code runs or how fast events arrive — a
main-thread socket write, two messages sent too close together, a sheet that did not close. The
test suite could not have caught any of them, so this section matters more than the CI badge.

For example: "Built and installed on a Pixel 6 (Android 14), paired with a Sony Bravia, held the
D-pad to scroll a long list, typed into the Play Store search."
-->

- [ ] `./gradlew :protocol:test` passes
- [ ] `./gradlew :app:assembleDebug` passes
- [ ] Tried against a real TV from a real phone

## Checklist

- [ ] `protocol/` still has no `android.*` import, so its tests and `tvcli` still run without a device
- [ ] Anything touching framing or the pairing hash has a test, checked against `python-androidtvremote2`
- [ ] `docs/PLAN.md` updated if what is verified on hardware changed
- [ ] `CHANGELOG.md` updated if behaviour changed
- [ ] No new dependency, or a paragraph in the PR saying why there is one
