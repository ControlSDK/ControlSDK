package org.btelman.controlsdk.models

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.btelman.controlsdk.enums.ComponentStatus
import org.btelman.controlsdk.interfaces.ComponentEventListener
import org.btelman.controlsdk.interfaces.IComponent
import org.btelman.controlsdk.services.ControlSDKService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Base component object to use to extend functionality of your robot.
 *
 * Runs on its own threads, as long as this.handler is used
 * Ex. can be used as an interface for LEDs based off of control messages
 */
abstract class Component : IComponent {
    protected var context: Context? = null
    protected var eventDispatcher : ComponentEventListener? = null
    private var handlerThread = HandlerThread(
            javaClass.simpleName
    ).also { it.start() }

    /**
     * Constructor that the service will use to start the component. For custom actions, please use onInitializeComponent
     */
    @Suppress("ConvertSecondaryConstructorToPrimary")
    constructor()

    protected val handler = Handler(handlerThread.looper){
        handleMessage(it)
    }

    private var _status: ComponentStatus = ComponentStatus.DISABLED_FROM_SETTINGS
    var status : ComponentStatus
        get() = _status
        set(value) {
            if(_status == value) return //Only set state if changed
            _status = value
            eventDispatcher?.handleMessage(getType(),
                STATUS_EVENT, status, this)
        }

    init {
        status = ComponentStatus.DISABLED
    }
    protected val enabled = AtomicBoolean(false)

    protected abstract fun enableInternal()
    protected abstract fun disableInternal()

    override fun setEventListener(listener: ComponentEventListener?) {
        eventDispatcher = listener
    }

    open fun getName() : String{
        return javaClass.simpleName
    }


    protected fun reset() { //TODO this could potentially create thread locks?
        runBlocking {
            disable().await()
            enable().await()
        }
    }

    /**
     * Called when component should startup. Will return without action if already enabled
     */
    override fun enable() = GlobalScope.async{
        if(enabled.getAndSet(true)) return@async false
        status = ComponentStatus.CONNECTING
        awaitCallback<Boolean> { enableWithCallback(it) }
        return@async true
    }

    fun enableWithCallback(callback: Callback<Boolean>){
        handler.post {
            enableInternal()
            callback.onComplete(true)
        }
    }

    fun disableWithCallback(callback: Callback<Boolean>){
        handler.post {
            disableInternal()
            handler.removeCallbacksAndMessages(null)
            callback.onComplete(true)
        }
    }

    interface Callback<T> {
        fun onComplete(result: T)
        fun onException(e: Exception?)
    }

    suspend fun <T> awaitCallback(block: (Callback<T>) -> Unit) : T =
            suspendCancellableCoroutine { cont ->
                block(object : Callback<T> {
                    override fun onComplete(result: T) = cont.resume(result)
                    override fun onException(e: Exception?) {
                        e?.let { cont.resumeWithException(it) }
                    }
                })
            }

    /**
     * Called when component should shut down
     *
     * Will return without action if already enabled
     */
    override fun disable() = GlobalScope.async{
        if(!enabled.getAndSet(false)) return@async false
        awaitCallback<Boolean> { disableWithCallback(it) }
        status = ComponentStatus.DISABLED
        return@async true
    }

    /**
     * Called when we have not received a response from the server in a while
     */
    open fun timeout(){}

    /**
     * Handle message sent to this component's handler
     */
    open fun handleMessage(message: Message): Boolean{
        var result = false
        if(message.what == ControlSDKService.EVENT_BROADCAST)
            (message.obj as? ComponentEventObject)?.let {
                result = handleExternalMessage(it)
            }
        return result
    }

    /**
     * Handle a message from outside of the component.
     * Used so we could grab control events or tts commands and similar
     */
    open fun handleExternalMessage(message: ComponentEventObject) : Boolean{
        return false
    }

    override fun dispatchMessage(message: Message) {
        val newMessage = Message.obtain(message)
        newMessage.target = handler
        newMessage.sendToTarget()
    }

    /**
     * Used to retrieve Context and provide an initialization bundle
     */
    open fun onInitializeComponent(applicationContext: Context?, bundle : Bundle?) {
        context = applicationContext
    }

    companion object {
        //some handler events (what)
        const val DO_SOME_WORK = 0

        //some constant strings
        const val STATUS_EVENT = 0
        const val EVENT_MAIN = 1
        const val MESSAGE_TIMEOUT = 2

        fun instantiate(applicationContext: Context?, holder: ComponentHolder<*>) : Component {
            val component : Component = holder.clazz.newInstance()
            component.onInitializeComponent(applicationContext, holder.data)
            return component
        }
    }
}
