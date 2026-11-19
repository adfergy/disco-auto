# Disco Auto: what the app is made of

## Written for this project

| Where | What |
| --- | --- |
| `app/src/main/java/app/spinbridge/**` | The link to the car (mySPIN), the phone's screens, the car's logo card, the self-test, and the glue between the Android Auto receiver and the car |
| `app/src/main/java/io/motohub/android/androidauto/SpinBridgeShims.kt`, `.../tbox/TBoxSessionRegistry.kt` | Minimal stand-ins for MOTO-HUB types the receiver refers to |

The car side was written from the behaviour of the Land Rover InControl Apps launcher, for
interoperability with a head unit its owner already has. No Bosch or JLR code is included.
Two things in it are not invention but facts the car requires: the list of USB accessory
identities the launcher answers to (`res/xml/accessory_filter.xml`), and the protocol's numbers
and the greeting string the launcher sends.

These files are offered under the MIT License ([LICENSE](../LICENSE)) and carry an
`SPDX-License-Identifier: MIT` notice.

## Taken from other projects

| Where | Origin | Licence |
| --- | --- | --- |
| `app/src/main/java/io/motohub/android/aa/**`, `.../androidauto/AaInputBridge.kt`, `.../androidauto/AndroidAutoNightModeStore.kt` | MOTO-HUB at 9c42a4b (<https://github.com/vincenzobpt/MOTO-HUB>), which ported it from headunit-revived / Open Headunit. Changed here as listed below | AGPL-3.0 |
| `app/src/main/res/raw/aa_cert`, `aa_identity_data` | Open Headunit 4.0.0 (`res/raw/cert`, `privkey`): the identity the receiver presents to Android Auto. It is not this project's, and nobody connected with this project can grant rights in it | Shipped by Open Headunit under AGPL-3.0 |

Because of the first row the app is a work under the GNU AGPL v3.0
([LICENSES/AGPL-3.0.txt](../LICENSES/AGPL-3.0.txt)); the MIT files it also contains stay MIT on
their own, but the app as a whole is AGPL: anyone given the app must be given, or offered, this
source under the same licence.

### Changes to the code taken from MOTO-HUB

1. `VideoDecoder` asks `AndroidAutoRuntime.preferHardwareDecoder` instead of always preferring
   the phone's hardware decoder. Frames are read back on the CPU here, which the hardware
   decoder's frames did not survive.
2. `VideoDecoder`'s stall watchdog counts from the oldest input the decoder has not answered
   rather than from the last output. Android Auto sends video only when its screen changes, so
   the first frame after a still screen restarted a healthy decoder.
3. `SingleKeyKeyManager` also accepts a PEM-armoured key.
4. `AaReceiver` opens its dial-in listener (127.0.0.1:5288) only when
   `AndroidAutoRuntime.listenForAndroidAutoDialIn` is set, which it is not. Here the receiver
   only dials Android Auto's head unit server.
5. `AapTransport`'s handshake sends one version request and waits for the answer
   (`AndroidAutoRuntime.versionResponsePatienceMs`, a minute) instead of sending three, two
   seconds apart, and then abandoning the connection. Set to zero, MOTO-HUB's behaviour returns.
6. Not included: `AaSelfMode.kt`, `AaWirelessPairing.kt` and
   `androidauto/AndroidAutoSelfModeHelp.kt`, which ask Android Auto to connect by starting its
   wireless set-up services. Android Auto 17.4 and later cannot act on that, and on 18.1 one
   such request brought Android Auto's car process down.

## Libraries

| Library | Licence |
| --- | --- |
| Kotlin standard library, kotlinx.coroutines (JetBrains) | Apache License 2.0 ([licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)) |
| Protocol Buffers for Java (Google) | BSD 3-Clause |
| Conscrypt (Google) | Apache License 2.0 |
