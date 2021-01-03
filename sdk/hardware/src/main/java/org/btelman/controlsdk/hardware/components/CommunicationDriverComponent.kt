package org.btelman.controlsdk.hardware.components

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import org.btelman.controlsdk.enums.ComponentStatus
import org.btelman.controlsdk.enums.ComponentType
import org.btelman.controlsdk.hardware.interfaces.HardwareDriver
import org.btelman.controlsdk.interfaces.ComponentEventListener
import org.btelman.controlsdk.models.Component

/**
 * Main communication Component that holds a reference to CommunicationInterface and controls it
 */
class CommunicationDriverComponent : Component() , Runnable{
    override fun getType(): ComponentType {
        return ComponentType.HARDWARE
    }

    private var hardwareDriver: HardwareDriver? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onInitializeComponent(applicationContext: Context, bundle: Bundle?) {
        super.onInitializeComponent(applicationContext, bundle)
        val interfaceClazz = bundle?.let {
            HardwareDriver.fromBundle(bundle)
        } ?: return
        hardwareDriver = HardwareDriver.init(interfaceClazz)
        hardwareDriver?.initConnection(applicationContext)
        uiHandler.post(this)
    }

    override fun setEventListener(listener: ComponentEventListener?) {
        super.setEventListener(listener)
        hardwareDriver?.setEventListener(listener)
    }

    override fun getInitialStatus(): ComponentStatus {
        return ComponentStatus.CONNECTING
    }

    @Synchronized
    override fun enableInternal(){
        hardwareDriver?.enable()
    }

    @Synchronized
    override fun disableInternal(){
        hardwareDriver?.disable()
    }

    private var errorCounter = 0

    override fun run() {
        if(enabled.get())
            status = hardwareDriver?.getStatus() ?: ComponentStatus.ERROR
        if(hardwareDriver?.getAutoReboot() == true
                && hardwareDriver?.getStatus() == ComponentStatus.ERROR){
            errorCounter++
            if(errorCounter > 10){
                reset()
                errorCounter = 0
            }
        }
        else{
            errorCounter = 0
        }
        uiHandler.postDelayed(this, 200)
    }

    open fun sendToDriver(data : ByteArray){
        dispatchMessage(Message.obtain(handler, EVENT_MAIN, data))
    }

    override fun handleMessage(message: Message): Boolean {
        when(message.what){
            EVENT_MAIN -> {
                hardwareDriver?.send(message.obj as ByteArray) ?: return false
                return true
            }
        }
        return super.handleMessage(message)
    }
}
