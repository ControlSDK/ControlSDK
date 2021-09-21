package org.btelman.controlsdk.models

import android.app.NotificationManager
import android.content.Context
import android.os.*
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.btelman.controlsdk.R
import org.btelman.controlsdk.enums.ComponentStatus
import org.btelman.controlsdk.interfaces.ComponentEventListener
import org.btelman.controlsdk.interfaces.IComponent
import org.btelman.controlsdk.services.ControlSDKService
import org.btelman.logutil.kotlin.LogUtil
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Base component object to use to extend functionality of your robot.
 *
 * Runs on its own threads, as long as this.handler is used
 * Ex. can be used as an interface for LEDs based off of control messages
 */
abstract class Component : IComponent {
    protected var context: Context? = null
    protected var eventDispatcher : ComponentEventListener? = null
    private var handlerThread : HandlerThread? = null

    protected val log = LogUtil("ControlSDKComponent : ${javaClass.name}", ControlSDKService.loggerID)

    /**
     * Constructor that the service will use to start the component. For custom actions, please use onInitializeComponent
     */
    @Suppress("ConvertSecondaryConstructorToPrimary")
    constructor()

    protected lateinit var handler : Handler

    private var async: Boolean = true

    private var threadPriority : Int = Process.THREAD_PRIORITY_DEFAULT

    private var _status: ComponentStatus = ComponentStatus.DISABLED_FROM_SETTINGS
    var status : ComponentStatus
        get() = _status
        set(value) {
            if(_status == value) return //Only set state if changed
            _status = value
            log.v{
                "Update Status $value : ${eventDispatcher?.let { "Sending upwards" } ?: "dispatcher null"}"
            }
            eventDispatcher?.handleMessage(getType(),
                STATUS_EVENT, status, this)
        }

    init {
        status = ComponentStatus.DISABLED
    }
    protected val enabled = AtomicBoolean(false)

    protected abstract fun enableInternal()
    protected abstract fun disableInternal()

    open fun getInitialStatus() : ComponentStatus{
        return ComponentStatus.STABLE
    }

    override fun setEventListener(listener: ComponentEventListener?) {
        log.d{
            "setEventListener"
        }
        eventDispatcher = listener
    }

    open fun getName() : String{
        return javaClass.simpleName
    }


    protected fun reset() { //TODO this could potentially create thread locks?
        log.d{
            "reset"
        }
        runBlocking {
            disable().await()
            enable().await()
        }
    }

    /**
     * Called when component should startup. Will return without action if already enabled
     */
    override fun enable() = GlobalScope.async{
        log.d{
            "enable"
        }
        if(enabled.getAndSet(true)) return@async false
        status = getInitialStatus()
        awaitCallback<Boolean> { enableWithCallback(it) }
        return@async true
    }

    /**
     * Called when component should shut down
     *
     * Will return without action if already enabled
     */
    override fun disable() = GlobalScope.async{
        if(!enabled.getAndSet(false)) return@async false
        log.d{
            "disable"
        }
        awaitCallback<Boolean> { disableWithCallback(it) }
        status = ComponentStatus.DISABLED
        return@async true
    }

    fun enableWithCallback(callback: Callback<Boolean>){
        handler.post {
            try {
                enableInternal()
            } catch (e: Exception) {
                throwError(e)
                status = ComponentStatus.ERROR
            }
            callback.onComplete(true)
        }
    }

    fun disableWithCallback(callback: Callback<Boolean>){
        handler.post {
            try {
                disableInternal()
            }
            catch (e : Exception){
                e.printStackTrace()
                throwError(e)
                status = ComponentStatus.ERROR
            }
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

    private fun createHandler(looper: Looper) : Handler{
        return Handler(looper){
            handleMessage(it)
        }
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
        when(message.what){
            ControlSDKService.EVENT_BROADCAST -> {
                (message.obj as? ComponentEventObject)?.let {
                    result = handleExternalMessage(it)
                }
            }
            ControlSDKService.STATUS_UPDATE -> {
                status = status
            }
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
     * Used to retrieve Context and provide an initialization bundle. Must be called, preferably first
     */
    @CallSuper
    override fun onInitializeComponent(applicationContext: Context, bundle : Bundle?) {
        if(async){
            handlerThread = HandlerThread(
                javaClass.simpleName
            ).also { it.start() }
            handler = createHandler(handlerThread!!.looper)
            handler.post { //set thread priority of handlerThread
                Process.setThreadPriority(threadPriority)
            }
        }
        else{
            handler = createHandler(ControlSDKService.defaultLooper)
        }

        log.d{
            "onInitializeComponent"
        }
        context = applicationContext
    }

    override fun onRemoved() {
        log.d{
            "onRemoved"
        }
    }

    //TODO send component errors upwards
    open fun throwError(e : Exception){
        log.e("Error", e)
        if(!ControlSDKService.allowNotificationForExceptions) return
        context?.let {
            val errorMessage = getType().toString().toLowerCase() +" "+ it.getString(R.string.componentCrashedText)
            val notification = NotificationCompat.Builder(it, ControlSDKService.CONTROL_SERVICE)
                .setContentTitle(it.getString(R.string.componentCrashed))
                .setContentText(errorMessage)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$errorMessage\n" +
                        "Please report this to the developer if you believe this should not be happening:\n" +
                        e.message))
            val service = it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.notify(Random.nextInt(), notification.build())
        }
    }

    companion object {
        //some handler events (what)
        //some constant strings
        const val STATUS_EVENT = 0
        const val EVENT_MAIN = 1
        const val MESSAGE_TIMEOUT = 2

        fun instantiate(applicationContext: Context, holder: ComponentHolder<*>) : Component {
            val component : Component = holder.clazz.newInstance() as Component
            component.threadPriority = holder.threadPriority
            component.async = holder.async
            component.onInitializeComponent(applicationContext, holder.data)
            return component
        }
    }
}
