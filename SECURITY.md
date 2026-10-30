# Security

## The threat model, stated plainly

**The client certificate is the credential.** Pairing puts this app's self-signed certificate on
the TV's trusted list, and from then on anything presenting that certificate and its private key
can control the TV. There is no second factor and no expiry — the TV remembers it until you remove
it under *Settings → Remote & accessories*.

What that buys an attacker is control of a television on your local network. They can change the
volume, press buttons, launch apps, type into a focused field, and switch the TV off. They cannot
switch it on (§4.4 of [docs/PLAN.md](docs/PLAN.md)), cannot read anything off the TV, and cannot
reach anything else on your network through it.

What that means in practice:

- **The private key lives in the app's private storage**, not the Android Keystore. On a
  non-rooted phone nothing else can read it. On a rooted phone, or one where someone has an ADB
  shell, it can be copied. Moving the key into the Keystore is a sensible hardening step and is
  not done yet.
- **Anyone who can pair can control the TV.** Pairing needs physical sight of the TV screen to
  read the code, which is the actual access control here, and it is a reasonable one for a device
  in your living room.
- **This is a LAN tool.** Do not forward ports 6466 or 6467, and do not expose the TV to the
  internet. The protocol was never designed for that.

## What it does and does not verify

- **The code you type is checked locally first.** Its first two characters are a checksum over the
  pairing hash, so a typo is rejected by the app before the TV is contacted. That is a usability
  feature, not a security boundary.
- **The TV's certificate is accepted without verification.** It is self-signed and cannot chain to
  any root, so there is nothing to check it against on first contact. The consequence is real:
  something on your network impersonating the TV could accept a pairing, and could see which keys
  you press. It cannot steal the private key, which never leaves the device — TLS client
  authentication proves possession by signature. **Pinning the TV's certificate after the first
  pairing would close this, and is not implemented.** The official Google TV remote does not do it
  either.
- **No certificate is ever sent anywhere but to the TV**, over TLS, on your own network. The app
  makes no other network calls — no analytics, no update check, no cloud.

## Reporting a vulnerability

Open a GitHub issue. Given the above, "anyone who can see the TV screen can pair with it" is not a
vulnerability — it is the design. Worth reporting: a way to pair without the code, a way to recover
the private key from outside the app, anything that makes the app reach a host other than the TV,
or a crash triggerable by a hostile device answering on the TV's ports.

If you would rather not discuss it publicly, say so in the issue without the details and we will
find another way.
