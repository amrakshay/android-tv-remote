# Usage

## 1. Build the app

No Android Studio needed. JDK 17 or newer, plus the Android SDK command-line tools.

```bash
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`, about 22 MB because a debug build
carries the Compose tooling. A release build is 2.3 MB:

```bash
./gradlew :app:assembleRelease
```

That one is unsigned until you give it a keystore, and an unsigned APK will not install. For
sideloading onto your own phone the debug build is fine — it is signed with the debug key and does
not expire.

> **Note on `compileSdk`.** The current AndroidX libraries require compiling against 37, and the
> SDK package is `platforms;android-37.2` — plain `platforms;android-37` does not exist and
> `sdkmanager` unhelpfully reports it as not found. The build declares `compileSdk = 37` with
> `compileSdkMinor = 2`.

## 2. Install it

Over USB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it from a file manager, allowing "install unknown apps" for
that file manager once.

## 3a. A box or projector with no remote service

Some devices run Android TV but ship without `com.google.android.tv.remote.service`, so there is
nothing on port 6466 to pair with. The app drives those over ADB instead.

1. On the device: **Settings → Device Preferences → About**, click *Build* seven times to unlock
   developer options.
2. **Settings → Device Preferences → Developer options → USB debugging** (and, where the device
   offers it separately, *Wireless debugging* / *ADB over network*).
3. Open the app. The device appears under *Found on your network* marked **over ADB**.
4. There is no code. If the device has not seen this phone before it shows an
   **"Allow debugging?"** box — accept it, and tick *always allow from this computer* so it stops
   asking.

If the device does not appear, it is almost always that ADB is listening only over USB. Plugging
it into a computer once and running `adb tcpip 5555` turns on the network listener until the next
reboot; many of these devices then keep it on permanently.

Everything on the remote works the same afterwards, with the differences listed in the README
under *Devices without the TV Remote Service* — most usefully, the Apps sheet lists what is really
installed, and typing reaches apps the TV protocol cannot.

## 3. Pair with the TV

**Switch the TV on first.** It leaves the network entirely when asleep, so the app cannot find a
sleeping TV — and cannot wake one.

1. Open the app. It lists any Android TV it finds, by the name the TV advertises.
2. Tap yours. The TV shows **six characters** — letters A–F and digits, not just numbers.
3. Type them in and tap **Pair**.

That is the last time you will see that screen. The certificate is stored on the phone and the TV
remembers it, so every later launch goes straight to the remote.

The TV names the phone on its pairing screen — the app sends the handset model, so you can tell
which phone is asking.

## 3b. Renaming a device

Tap the name at the top of the remote. A device that reports itself as `t950s_be311` can be
called "Projector"; the name lives on this phone only, nothing is sent to the device, and a name
you have chosen is never replaced by the model it reports.

## 4. Using it

| | |
|---|---|
| D-pad | Tap to move, **hold to repeat** — the TV does the repeating, so holding is smooth rather than steppy |
| Volume | Tap or hold. The bar shows the TV's own level, so it moves when someone uses the plastic remote |
| Type | Opens your phone's keyboard and sends what you type to the TV's focused field |
| Apps | Deep links straight into an app, rather than walking the home screen |
| Switch | Back to the TV list |

## When it cannot find the TV

In order of likelihood:

1. **The TV is asleep.** It drops off the network completely — no ping, no ARP entry, every port
   closed. Wake it with the infrared remote.
2. **The phone is on a different network** — a guest SSID, or 5 GHz where the TV is on 2.4 GHz and
   the router keeps them apart.
3. **The router blocks multicast**, which is how discovery works. Use the address box at the
   bottom of the discovery screen instead; the TV's address is under *Settings → Network* on the
   TV.

To check from a computer on the same network:

```bash
dns-sd -B _androidtvremote2._tcp          # macOS
avahi-browse -rt _androidtvremote2._tcp   # Linux
```

If that finds nothing while the TV is awake, the problem is the network, not the app.

## Driving a device without a phone

The protocol module is a plain JVM library with a CLI attached. This is how the protocol was
developed and is still the quickest way to check a change:

```bash
./gradlew :protocol:tvcli --args="pair   <tv-ip> creds"
./gradlew :protocol:tvcli --args="keys   <tv-ip> creds VOLUME_DOWN VOLUME_UP HOME BACK"
./gradlew :protocol:tvcli --args="search <tv-ip> creds the bear season 3"
./gradlew :protocol:tvcli --args="launch <tv-ip> creds https://www.youtube.com"
```

`creds/` is a directory it creates, holding the certificate and key. **It is a credential the TV
trusts** — it is gitignored, and should stay that way.

Key names are any of the protocol's 305 codes without the `KEYCODE_` prefix: `DPAD_UP`, `HOME`,
`BACK`, `MEDIA_PLAY_PAUSE`, `CHANNEL_UP`, `TV_INPUT`, and so on.

## Things that do not work, and why

**The app cannot switch the TV on.** In standby this TV drops its Wi-Fi radio entirely, so there
is nothing listening to talk to, and Wake-on-LAN does not wake it — neither network interface
registers as wake-capable. Your infrared remote works because the IR receiver is a separate
always-on chip, and there is no network equivalent. The app says so rather than offering a button
that silently fails. The measurements are in [PLAN.md](PLAN.md) §4.4.

**Typing does not reach every app.** It lands in apps that use a standard Android text field.
YouTube, among others, draws its own keyboard into a `SurfaceView` and reports no text field at
all, so nothing can be typed into it — the official Google TV remote cannot either. The keyboard
sheet shows which case it is in: *ready*, or *waiting for a text field*.

**Some app shortcuts may not open.** The deep links are best guesses apart from YouTube, which is
verified. If one does nothing, the app does not answer that link scheme.

## Removing the pairing

On the TV: *Settings → Remote & accessories*, find the phone by name, remove it. The app will need
to pair again next time.
