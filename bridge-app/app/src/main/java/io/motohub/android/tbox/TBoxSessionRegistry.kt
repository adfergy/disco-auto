// SPDX-License-Identifier: MIT
// SpinBridge: stand-in for MOTO-HUB's session registry. The lifted self-mode launcher asks it for
// the motorcycle's Wi-Fi credentials to offer Android Auto a wireless pairing; a car reached over
// USB has none, and a null answer makes the launcher skip that path, which is what we want.
package io.motohub.android.tbox

object TBoxSessionRegistry {
    class Motorcycle(val ssid: String, val password: String)

    class Handle(val motorcycle: Motorcycle)

    fun current(): Handle? = null
}
