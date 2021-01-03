package org.btelman.controlsdk.hardware.drivers

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import org.btelman.controlsdk.enums.ComponentStatus
import org.btelman.controlsdk.hardware.activities.ChooseBluetoothActivity
import org.btelman.controlsdk.hardware.drivers.libs.bluetooth.BluetoothClassic
import org.btelman.controlsdk.hardware.drivers.libs.bluetooth.Connection
import org.btelman.controlsdk.hardware.interfaces.DriverComponent
import org.btelman.controlsdk.hardware.interfaces.HardwareDriver
import org.btelman.controlsdk.interfaces.ComponentEventListener
import org.btelman.controlsdk.services.ControlSDKService
import org.btelman.logutil.kotlin.LogUtil

/**
 * communication class that works with bluetooth classic
 * and takes control data via EventManager.ROBOT_BYTE_ARRAY event
 *
 * TODO better error handling
 */
@DriverComponent(description = "Send data over bluetooth classic (Serial) at 9600 BAUD", requiresSetup = true)
class BluetoothClassicDriver : HardwareDriver {
    var bluetoothClassic : BluetoothClassic? = null
    var addr : String? = null
    var name : String? = null
    private val log = LogUtil("BluetoothClassicDriver", ControlSDKService.loggerID)

    override fun clearSetup(context: Context) {
        log.d{
            "Clear prefs"
        }
        context.getSharedPreferences(CONFIG_PREFS, 0).edit().clear().apply()
    }

    override fun usesCustomSetup(): Boolean {
        return true
    }

    override fun needsSetup(activity: Activity): Boolean {
        val pairingRequired = !activity.applicationContext.getSharedPreferences(CONFIG_PREFS, 0).contains(
            BLUETOOTH_ADDR
        )
        val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        //Return that additional setup is needed if no preferred device OR bluetooth is off
        return pairingRequired || !mBluetoothAdapter.isEnabled
    }

    override fun setupComponent(activity: Activity, force : Boolean): Int {
        log.d{
            "setupComponent force=$force"
        }
        //Make sure we turn bluetooth on for setup
        val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if(!mBluetoothAdapter.isEnabled) {
            log.d{
                "enable bluetooth..."
            }
            mBluetoothAdapter.enable()
        }
        //Start an activity to select our preferred device
        val pairingRequired = !activity.applicationContext.getSharedPreferences(CONFIG_PREFS, 0).contains(
            BLUETOOTH_ADDR
        )
        if(force || pairingRequired) {
            log.d{
                "Launching ChooseBluetoothActivity..."
            }
            activity.startActivityForResult(
                    Intent(activity, ChooseBluetoothActivity::class.java),
                RESULT_CODE
            )
            return RESULT_CODE
        }
        return -1
    }

    override fun receivedComponentSetupDetails(context: Context, intent: Intent?) {
        intent?.let {
            val addr = intent.extras.getString(ChooseBluetoothActivity.EXTRA_DEVICE_ADDRESS, null)
            val name = intent.extras.getString(ChooseBluetoothActivity.EXTRA_DEVICE_NAME, null)
            val prefsEdit = context.applicationContext.getSharedPreferences(CONFIG_PREFS, 0).edit()
            prefsEdit.putString(BLUETOOTH_ADDR, addr)
            prefsEdit.putString(BLUETOOTH_NAME, name)
            prefsEdit.apply()
        }
    }

    override fun isConnected(): Boolean {
        return bluetoothClassic?.status == Connection.STATE_CONNECTED
    }

    override fun send(byteArray: ByteArray): Boolean {
        log.v{
            "send $byteArray"
        }
        bluetoothClassic?.writeBytes(byteArray)
        return true
    }

    override fun initConnection(context: Context) {
        log.d{
            "initConnection"
        }
        addr = context.getSharedPreferences(CONFIG_PREFS, 0).getString(
            BLUETOOTH_ADDR, null)
        addr?.let {
            bluetoothClassic = BluetoothClassic(it)
        } ?: throw Exception("No bluetooth address supplied!")
    }

    override fun enable() {
        setActive(true)
    }

    override fun disable() {
        setActive(false)
    }

    fun setActive(value : Boolean){
        val message : String = if(value){
            bluetoothClassic?.connect()
            "enable"
        }
        else{
            bluetoothClassic?.disconnect()
            "disable"
        }
        log.d{
            message
        }
    }

    override fun getStatus(): ComponentStatus {
        bluetoothClassic?.status?.let{
            return when(it){
                Connection.STATE_CONNECTED -> ComponentStatus.STABLE
                Connection.STATE_CONNECTING -> ComponentStatus.CONNECTING
                Connection.STATE_ERROR -> ComponentStatus.ERROR
                Connection.STATE_IDLE -> ComponentStatus.DISABLED
                Connection.STATE_DISCONNECTED -> ComponentStatus.ERROR
                else -> ComponentStatus.ERROR
            }
        }
        return ComponentStatus.DISABLED
    }

    override fun getAutoReboot(): Boolean {
        return true
    }

    override fun setEventListener(listener: ComponentEventListener?) {
        // ignore
    }

    companion object {
        const val BLUETOOTH_ADDR = "addr"
        const val BLUETOOTH_NAME = "name"
        const val CONFIG_PREFS = "BluetoothClassicConfig"
        const val RESULT_CODE = 312
    }
}
