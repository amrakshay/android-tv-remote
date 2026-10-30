<p align="center">
  <strong>android-tv-remote</strong>
</p>

<p align="center">
  Your phone as the remote for an Android TV. Nothing installed on the TV.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT licence"></a>
  <a href="../../actions/workflows/ci.yml"><img src="../../actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/app-Android%208%2B-blue" alt="Android 8+">
  <img src="https://img.shields.io/badge/apk-2.3%20MB-blue" alt="2.3 MB">
</p>

---

## What it is

Android TVs already listen for a remote on the local network — it is how the official Google TV
app works. This speaks that protocol, so **the TV needs no app, no sideloading and no setting
changed**. Pair once with a code shown on screen, and the phone is a remote from then on.

| Piece | What it does |
|---|---|
| [`protocol/`](protocol/) | The Android TV remote protocol v2, in plain Kotlin. **No Android dependency**, so it unit-tests on a desktop and ships a CLI that drives a real TV |
| [`app/`](app/) | The phone app — discovery, pairing, and the remote itself, in Compose |
| [`spike/`](spike/) | A Dart implementation of the same protocol, kept as a second reference |

```
phone                                   TV
┌────────────────────────┐             ┌──────────────────────────┐
│ mDNS browse            │ ──────────► │ _androidtvremote2._tcp   │
│                        │             │                          │
│ TLS :6467  pair once   │ ◄─────────► │ shows a 6-character code │
│ TLS :6466  key presses │ ──────────► │                          │
│            volume, app │ ◄────────── │ pushes its own state     │
└────────────────────────┘             └──────────────────────────┘
```

The TV talks back, which is the part that makes it feel connected rather than hopeful: the volume
bar moves when somebody uses the plastic remote, and the header shows what is playing.

## Quick start

```bash
git clone git@github.com:amrakshay/android-tv-remote.git
cd android-tv-remote
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app with the TV **switched on**. It finds the TV, the TV shows six characters, you type
them in, and that is the last time you will see that screen.

See [docs/USAGE.md](docs/USAGE.md) for the SDK setup, a release build, and what to do when
discovery finds nothing.

## Driving a TV without a phone

The protocol module is a plain JVM library, so it has a CLI. This is how the protocol was
developed and is still the quickest way to test a change:

```bash
./gradlew :protocol:tvcli --args="pair    192.168.0.236 creds"
./gradlew :protocol:tvcli --args="keys    192.168.0.236 creds VOLUME_DOWN VOLUME_UP HOME"
./gradlew :protocol:tvcli --args="search  192.168.0.236 creds the bear season 3"
./gradlew :protocol:tvcli --args="launch  192.168.0.236 creds https://www.youtube.com"
```

## What works

Verified against a Xiaomi MiTV-AXSO0 from a OnePlus 7 — see
[docs/PLAN.md](docs/PLAN.md) for how each was checked.

- Discovery over mDNS, and a typed address for networks that block it
- Pair once; the certificate is kept and reused
- D-pad and OK, Back, Home, Search
- Volume and mute, with the TV's own level shown live
- **Press and hold to repeat**, which the TV does itself rather than the phone spamming keys
- Media transport
- Typing into a text field on the TV
- Launching an app by deep link

## What it deliberately does not do

- **It cannot switch the TV on.** This TV drops off the network entirely in standby — no ping, no
  ARP entry, every port closed — and Wake-on-LAN does not wake it. Your infrared remote works
  because the IR receiver is a separate always-on chip; there is no network equivalent. The app
  says so plainly rather than offering a button that silently fails. The measurements are in
  [docs/PLAN.md](docs/PLAN.md) §4.4.
- **No voice.** The protocol supports streaming the microphone; this does not.
- **No cloud, no account, no internet.** Everything is on your Wi-Fi.
- **iPhone is not supported.** The protocol is Kotlin, so that is a rewrite rather than a build
  step. Tracked as an issue.

## Typing does not work everywhere

Typing lands in apps that use a standard Android text field. Some apps — YouTube among them —
draw their own keyboard into a `SurfaceView`, so no text field is ever reported and nothing can
be typed into them. The official Google TV remote cannot type into those either. The keyboard
sheet shows which case it is in instead of swallowing the difference.

## Tested on

| | |
|---|---|
| TV | Xiaomi MiTV-AXSO0 (`dangal`, sold as Mi TV 4A Pro / 4C Pro), Android TV 9, firmware PI.6036 |
| Phone | OnePlus 7 (GM1901), Android 10 |

Other Android TVs should work — the protocol is Google's, not Xiaomi's — but nothing else has
been tried.

## Documentation

| | |
|---|---|
| [docs/USAGE.md](docs/USAGE.md) | Building, installing, and what to do when it does not find the TV |
| [docs/PLAN.md](docs/PLAN.md) | The engineering record: the protocol in detail, what was measured, what is still untested |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to work on it |
| [SECURITY.md](SECURITY.md) | What the pairing certificate does and does not protect |

## Requirements

- **To build:** JDK 17+, the Android SDK (`platforms;android-37.2`, `build-tools;37.0.0`). No
  Android Studio required — the Gradle wrapper is checked in.
- **To run:** Android 8.0 (API 26) or newer, on the same Wi-Fi as the TV.
- **The TV:** any Android TV. Nothing is installed on it.

## Licence

[MIT](LICENSE).
