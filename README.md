# Disco Auto

Your phone on the built-in touchscreen of a 2015 Land Rover Discovery Sport, through the car's
own **InControl Apps** USB socket. No new head unit, nothing taken apart.

The car's InControl Apps feature was switched off by its makers at the end of 2023, but the
screen still speaks its language. Disco Auto is a small Android app that speaks it back.

> **An independent, unofficial, experimental project.** It is not made, endorsed or supported
> by Jaguar Land Rover, Bosch or Google. It has been tested on one car and one phone, and an
> update to Android, Android Auto or the car's software can break it without warning. Use it at
> your own risk.

- **Android Auto on the car's screen**, used by touch.
- **Or your phone's screen mirrored**, as soon as you plug in and say yes to Android's prompt.
- Sound and the steering wheel's play/pause, next and previous buttons over Bluetooth.
- About seven pictures a second: fine for maps and menus, not smooth video.

## Compatibility

| | Status |
| --- | --- |
| 2015 Land Rover Discovery Sport 2.2, 8-inch InControl Touch screen | **Works** (the only car tested) |
| Samsung phone, Android 17, Android Auto 18.1 | **Works** (the only phone tested) |
| Other Android phones, Android 12 or later | Should work; untested |
| Other JLR cars of about 2015 to 2019 with the InControl Apps tile and USB socket | Might work; untested |
| Android 11 or earlier, or cars without InControl Apps | Will not work |

## What you need

- A car with the InControl Apps USB socket (it has the InControl Apps symbol next to it).
- An Android phone, Android 12 or later, with the Android Auto app.
- A USB **data** cable. A charge-only cable does nothing.
- The phone paired with the car over Bluetooth.

## Install

1. On the phone, open the [latest release](https://github.com/adfergy/disco-auto/releases/latest)
   and download the APK from its **Assets** list. Only download Disco Auto from this
   repository's Releases page.
2. Open the downloaded file, allow your browser or file manager to install apps, then choose
   **Install**.
3. Open **Disco Auto** once and allow what it asks for: notifications, nearby devices and the
   microphone (for Android Auto's assistant).

Upgrading from 0.4.0 or earlier: 0.5.0 installs as a separate app, so uninstall the old
**Disco Auto** first.

## Using it

1. **Plug the phone into the InControl Apps USB socket.** The first time, choose **Always** when
   Android asks whether to open Disco Auto.
2. **On the car's screen, tap InControl Apps** (or switch on **Auto Load InControl Apps** in the
   car's settings).
3. If Android Auto's head unit server is running (below), Android Auto appears. Otherwise tap
   **Start** in Android's screen-sharing prompt on the phone to mirror its screen.

For sound, choose Bluetooth as the car's audio source.

### Android Auto set-up

Android Auto only connects to an app like this through its developer "head unit server":

1. Open Android Auto's settings (the app has a button for it).
2. First time only: scroll to the bottom, tap **Version** ten times and accept.
3. Tap the three dots at the top right and choose **Start head unit server**. Once after each
   phone restart.

### Touch control while mirroring

Open Android's **Settings > Accessibility > Installed apps** and switch on **Disco Auto touch**.
If Android says the setting is restricted, open Disco Auto's **App info**, tap the three dots,
choose **Allow restricted settings**, and try again. Disco Auto uses it only to pass on touches
from the car's screen while mirroring; it reads nothing from your screen.

## If it does not work

| What you see | What to do |
| --- | --- |
| Nothing on the car's screen | Tap **InControl Apps** on the car. Try another cable: it must carry data |
| The car shows the Disco Auto menu | Tap **MIRROR**, then **Start** on the phone, or start Android Auto's head unit server |
| The phone says Android Auto's server is not answering | In Android Auto's three-dot menu, **Stop head unit server**, then **Start head unit server** |
| Picture but no sound | Choose Bluetooth as the car's audio source |
| Something else | **Help and log** in the app shows what happened, and **Share log** sends it |

## Privacy and safety

Disco Auto collects nothing and sends nothing off the phone. Its network permission is used only
to reach Android Auto on the phone itself (127.0.0.1).

The car reports itself as always parked, so Android Auto does not apply its driving
restrictions, and mirroring shows whatever is on the phone. Set it up while parked and do not
use anything that takes your attention off the road.

## Building

```bash
cd bridge-app && ./gradlew :app:assembleRelease
```

Release builds are signed when `bridge-app/keystore.properties` exists (git-ignored), with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it the release APK is
unsigned; `./gradlew :app:assembleDebug` gives a debug-signed build instead.

## Support

Disco Auto is free. If you'd like to say thanks, you can
[buy me a coffee](https://buymeacoffee.com/adfergy).

## Licence

Code written for this project is under the [MIT License](LICENSE). The app also contains an
Android Auto receiver from [MOTO-HUB](https://github.com/vincenzobpt/MOTO-HUB) under the
[GNU AGPL v3.0](LICENSES/AGPL-3.0.txt), so the app as a whole is distributed under the AGPL v3.0.
See [bridge-app/NOTICE.md](bridge-app/NOTICE.md) for which file is which.

Land Rover, Jaguar, InControl, mySPIN, Android and Android Auto are their owners' trademarks and
are used here only to say what Disco Auto works with.
