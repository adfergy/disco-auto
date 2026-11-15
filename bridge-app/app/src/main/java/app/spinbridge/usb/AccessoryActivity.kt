// SPDX-License-Identifier: MIT
package app.spinbridge.usb

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import app.spinbridge.BridgeLog
import app.spinbridge.BridgeService
import app.spinbridge.aa.AndroidAuto
import app.spinbridge.picture.AlternativePicture
import app.spinbridge.ui.MainActivity

/**
 * What Android starts when the car's head unit switches the phone into accessory mode and its
 * identification strings match res/xml/accessory_filter.xml. Being started this way is also what
 * grants permission for the accessory, so all this does is hand it to the service, bring the
 * app's own screen forward if the owner will be needed, and vanish.
 */
class AccessoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSafely(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSafely(intent)
    }

    /**
     * Whatever happens, finish (a NoDisplay activity must, before it is resumed) and leave a line
     * in the log: in the car there is no debugger, and a refusal by Android to start the service
     * would otherwise look exactly like the car never connecting.
     */
    private fun handleSafely(intent: Intent?) {
        try {
            handle(intent)
        } catch (failure: Exception) {
            BridgeLog.log("could not hand the accessory to the service: ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        BridgeLog.attach(applicationContext)
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val accessory: UsbAccessory? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
        if (accessory == null) {
            BridgeLog.log("accessory-attached intent without an accessory")
            return
        }
        BridgeLog.log("started by the system for a USB accessory")
        BridgeService.attach(applicationContext, accessory)
        // Showing the phone's screen needs the owner's yes to Android's prompt, and only one of the
        // app's own screens can put it. So unless Android Auto is ready to take
        // the car's screen by itself, bring that screen forward now, while Android still lets us.
        if (AndroidAuto.availability() != AlternativePicture.Availability.AVAILABLE) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
