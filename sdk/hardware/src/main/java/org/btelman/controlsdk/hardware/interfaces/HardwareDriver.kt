package org.btelman.controlsdk.hardware.interfaces

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.btelman.controlsdk.enums.ComponentStatus
import org.btelman.controlsdk.interfaces.ComponentEventListener
import org.btelman.controlsdk.utils.BundleUtil

/**
 * Connection interface for outbound connections.
 *
 * This has 2 modes.
 * 1. Instantiated for setup
 * 2. Instantiated for use with the control system
 */
interface HardwareDriver{
    /**
     * Get if we should try to reboot component with error
     */
    fun getAutoReboot() : Boolean
    fun getStatus() : ComponentStatus
    fun initConnection(context: Context)
    fun enable()
    fun disable()

    /**
     * Reset setup for class
     */
    fun clearSetup(context: Context)

    /**
     * Query this to see if there is a settings page for this setting
     */
    fun usesCustomSetup() : Boolean

    /**
     * Query this component to see if it needs custom setup
     *
     * Pass in activity
     */
    fun needsSetup(activity: Activity) : Boolean

    /**
     * Start setting up a component. Must pass in the current activity as it may
     * try launching an activity and wait for result
     */
    fun setupComponent(activity: Activity, force : Boolean = false) : Int
    fun setEventListener(listener: ComponentEventListener?)

    /**
     * This gets called in onActivityResult in parent activity. Probably not a good way to do this.
     * May get refactored at some point
     */
    fun receivedComponentSetupDetails(context: Context, intent: Intent?)
    fun isConnected() : Boolean
    fun send(byteArray: ByteArray) : Boolean

    companion object{
        fun fromBundle(bundle : Bundle) : Class<*>{
            val clazz = BundleUtil.getClassFromBundle(bundle, BUNDLE_ID)
            //yes this will crash, that is what I want for now
            assert(BundleUtil.checkIfClassMatches(clazz, HardwareDriver::class.java))
            return clazz!!
        }

        fun init(clazz: Class<*>) : HardwareDriver{
            return clazz.newInstance() as HardwareDriver
        }

        const val BUNDLE_ID = "hardware.CommunicationInterface"
    }
}


