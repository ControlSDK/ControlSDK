package org.btelman.controlsdk.services

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.btelman.controlsdk.BuildConfig
import org.btelman.controlsdk.R
import org.btelman.controlsdk.enums.ComponentStatus
import org.btelman.controlsdk.enums.ComponentType
import org.btelman.controlsdk.enums.ServiceStatus
import org.btelman.controlsdk.interfaces.*
import org.btelman.controlsdk.models.Component
import org.btelman.controlsdk.models.ComponentEventObject
import org.btelman.controlsdk.models.ComponentHolder
import org.btelman.controlsdk.utils.InlineBroadcastReceiver
import org.btelman.controlsdk.utils.NotificationUtil
import org.btelman.logutil.kotlin.LogLevel
import org.btelman.logutil.kotlin.LogUtil
import org.btelman.logutil.kotlin.LogUtilInstance
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.system.exitProcess

/**
 * The main ControlSDK control service.
 * This handles the lifecycle and communication to components that come from outside the sdk
 */
class ControlSDKService : Service(), ComponentEventListener, Handler.Callback {
    private var running = false
    private val componentList = ArrayList<ComponentHolder<*>>()
    private val listenerControllerList = HashMap<String, IControlSDKElement>()
    private val activeComponentList = ArrayList<IComponent>()
    private val log = LogUtil("ControlSDKService", loggerID)

    /**
     * Target we publish for clients to send messages to MessageHandler.
     */
    private lateinit var mMessenger: Messenger
    private var handlerThread : HandlerThread = HandlerThread("ControlSDK-main").also { it.start() }

    private val handler = Handler(handlerThread.looper, this)

    private var stopListenerReceiver: InlineBroadcastReceiver? = null

    override fun onCreate() {
        log.d{
            "ControlSDK ${BuildConfig.VERSION_NAME} starting..."
        }
        stopListenerReceiver = InlineBroadcastReceiver(SERVICE_STOP_BROADCAST){ _, _ ->
            log.d {
                "User requested service stop"
            }
            stopService()
            exitProcess(0)
        }.also {
            it.register(this)
        }
        NotificationUtil.tryCreateNotificationChannel(this,
            getString(R.string.channel_name),
            getString(R.string.channel_description))
        setupForeground()
        handler.obtainMessage(RESET).sendToTarget()
        mMessenger = Messenger(handler)
    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    override fun onBind(intent: Intent): IBinder? {
        log.d{
            Toast.makeText(applicationContext, "binding", Toast.LENGTH_SHORT).show()
            "onBind"
        }
        emitState()
        return mMessenger.binder
    }

    override fun onRebind(intent: Intent?) {
        log.d{
            Toast.makeText(applicationContext, "rebinding", Toast.LENGTH_SHORT).show()
            "onRebind"
        }
        emitState()
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        log.d{
            Toast.makeText(applicationContext, "unbinding", Toast.LENGTH_SHORT).show()
            "onUnbind"
        }
        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log.d{
            "onTaskRemoved"
        }
        stopService()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Message handler for Service level events, usually outside of the service
     */
    override fun handleMessage(msg: Message) : Boolean{
        when (msg.what) {
            START -> {
                enable()
            }
            STOP -> {
                log.d{
                    "handleMessage : STOP"
                }
                disable()
            }
            ATTACH_COMPONENT -> {
                log.v{
                    "handleMessage ATTACH_COMPONENT ${(msg.obj as? ComponentHolder<*>)?.javaClass?.name}"
                }
                (msg.obj as? ComponentHolder<*>)?.let {
                    addToLifecycle(it)
                }
            }
            DETACH_COMPONENT -> {
                log.v{
                    "handleMessage DETACH_COMPONENT ${(msg.obj as? ComponentHolder<*>)?.javaClass?.name}"
                }
                (msg.obj as? ComponentHolder<*>)?.let {
                    removeFromLifecycle(it)
                }
            }
            ATTACH_LISTENER_OR_CONTROLLER -> {
                log.v{
                    "handleMessage ATTACH_LISTENER_OR_CONTROLLER ${(msg.obj as? ComponentHolder<*>)?.clazz?.name}"
                }
                (msg.obj as? ComponentHolder<*>)?.let {
                    addListenerOrController(it)
                }
            }
            DETACH_LISTENER_OR_CONTROLLER -> {
                log.v{
                    "handleMessage DETACH_LISTENER_OR_CONTROLLER ${(msg.obj as? ComponentHolder<*>)?.clazz?.name}"
                }
                (msg.obj as? ComponentHolder<*>)?.let {
                    removeListenerOrController(it)
                }
            }
            RESET -> {
                log.d{
                    "handleMessage RESET"
                }
                reset()
            }
            EVENT_BROADCAST ->{
                sendToComponents(msg)
            }
            STATUS_UPDATE -> {
                sendToComponents(msg)
            }
        }
        return false
    }

    /**
     * Message handler for components that we are controlling.
     * Best thing to do after is to push it to the service handler for processing,
     * as this could be from any thread
     */
    override fun handleMessage(eventObject: ComponentEventObject) {
        handler.obtainMessage(EVENT_BROADCAST, eventObject).sendToTarget()
    }

    /**
     * Send a message to all components. Each component may decide whether or not it should use it
     */
    private fun sendToComponents(msg: Message) {
        val obj = msg.obj as? ComponentEventObject
        var targetFilter : ComponentType? = null
        obj?.let {
            if((obj.source as? IComponent)?.getType() != obj.type){
                //send a message to all components of type obj.type
                targetFilter = obj.type
            }
        }

        log.v{
            "handleMessage EVENT_BROADCAST FROM ${obj?.source?.javaClass?.name} : ${obj?.what} : ${obj?.data}"
        }

        activeComponentList.forEach { component ->
            targetFilter?.takeIf { component.getType() != it }
                ?: run{
                    component.dispatchMessage(msg)
                    log.v{
                        "handleMessage EVENT_BROADCAST send to ${component.javaClass.name}"
                    }
                }
        }

        sendToListeners(msg, targetFilter)
    }

    private fun sendToListeners(msg: Message, targetFilter : ComponentType? = null) {
        val eventObject = msg.obj as? ComponentEventObject ?: return
        forEachListener {
            if(eventObject.what == Component.STATUS_EVENT){
                it.onComponentStatus(eventObject.source.javaClass, eventObject.data as ComponentStatus)
            }
            else if(it.getComponentTypesForListening()?.contains(eventObject.type) != false){
                it.dispatchMessage(msg)
            }
        }
    }

    /**
     * Add a ComponentHolder to the service lifecycle. Will get instantiated into a Component when the service is enabled
     */
    private fun addToLifecycle(component: ComponentHolder<*>) {
        log.d("Component addToLifecycle ${component.clazz.name}")
        if(!componentList.contains(component)){
            componentList.add(component)
            forEachListener {
                it.onComponentAdded(ComponentHolder(component.clazz, null))
            }
        }
    }

    private fun addListenerOrController(component: ComponentHolder<*>){
        if(listenerControllerList[component.clazz.name] != null) return
        IControlSDKElement.instantiate(applicationContext, component)?.let {
            log.d{

                var message = "types: "
                if(it is IController)
                    message += "IController"
                if(it is IListener)
                    message += ",IListener"
                "addListenerOrController ${component.clazz.name} $message"
            }
            listenerControllerList[component.clazz.name] = it
            if(it is IController) {
                it.onControlAPI(ControlSdkWrapper().also {wrapper ->
                    wrapper.onMessenger(Messenger(mMessenger.binder))
                })
            }
        }
    }

    private fun removeListenerOrController(component: ComponentHolder<*>){
        log.d{
            "removeListenerOrController ${component.clazz.name}"
        }
        forEachControllerOrListener {
            it.onRemoved()
        }
        listenerControllerList.remove(component.clazz.name)
    }

    /**
     * Remove a ComponentHolder from the service. Only takes affect once the service is reset at the moment
     */
    private fun removeFromLifecycle(component: ComponentHolder<*>) {
        if(componentList.contains(component)){
            componentList.remove(component)
            forEachListener {
                it.onComponentRemoved(ComponentHolder(component.clazz, null))
            }
        }
    }

    /**
     * Instantiate all of the component holders on componentList and populate the cleared activeComponentList
     */
    private fun instantiateComponents() {
        log.d{
            "instantiateComponents"
        }
        activeComponentList.clear()
        componentList.forEach { holder ->
            try {
                val component = Component.instantiate(applicationContext, holder)
                activeComponentList.add(component)
                log.v{
                    "instantiateComponents ${component.javaClass.name}"
                }
            }catch (e : Exception){
                log.e{
                    "failed to instantiate ${holder.clazz.name}"
                }
                e.printStackTrace()
            }
        }
    }

    fun forEachListener(block : (IListener)->Unit){
        listenerControllerList.filter {
            it.value is IListener
        }.forEach {
            block(it.value as IListener)
        }
    }

    fun forEachController(block : (IController)->Unit){
        listenerControllerList.filter {
            it.value is IController
        }.forEach {
            block(it.value as IController)
        }
    }

    fun forEachControllerOrListener(block : (IControlSDKElement)->Unit){
        listenerControllerList.forEach {
            block(it.value)
        }
    }

    /**
     * enable the components via co-routines. Calling this is only allowed via a co-routine,
     * and blocks the current thread
     * This prevents race conditions from happening between the UI and the service.
     * This also holds up any new messages until after all components are enabled
     */
    fun enable(){
        val componentListener : ComponentEventListener = this
        runBlocking {
            setServiceState(ServiceStatus.ENABLING)
            log.d{
                Toast.makeText(applicationContext, "Starting ControlSDK", Toast.LENGTH_SHORT).show()
                "enable"
            }
            instantiateComponents()
            val list = ArrayList<Deferred<Boolean>>()

            //enable all of our components
            activeComponentList.forEach{
                log.v{
                    "enabling ${it.javaClass.name}"
                }
                it.setEventListener(componentListener)
                val deferred = it.enable()
                list.add(deferred) //add their deferred result to a list
            }

            //now wait for each one to complete
            list.forEach {
                log.v{
                    "waiting for ${it.javaClass.name} to complete enabling"
                }
                it.await()
            }
            setServiceState(ServiceStatus.ENABLED)
        }
    }

    /**
     * Disables components, blocking the service messaging thread until complete
     */
    fun disable(){
        runBlocking {
            setServiceState(ServiceStatus.DISABLING)
            log.d{
                Toast.makeText(applicationContext, "Stopping ControlSDK", Toast.LENGTH_SHORT).show()
                "disable"
            }
            activeComponentList.forEach{
                log.v{
                    "disabling ${it.javaClass.name}"
                }
                it.disable().await()
                it.setEventListener(null)
            }
            activeComponentList.clear()
            setServiceState(ServiceStatus.DISABLED)
        }
    }

    /**
     * Reset the service. If running, we will disable, reload, then start again
     */
    private fun reset() {
        if(running) {
            disable()
            reload()
            enable()
        }
        else{
            reload()
        }
    }

    /**
     * Reload the settings and prep for start
     */
    private fun reload() {
        componentList.clear()
    }

    /**
     * Stop the service properly, which involves running the disable command and then calling stopForeground
     */
    private fun stopService(){
        if(running)
            runBlocking { disable() }
        stopListenerReceiver?.unregister(this)
        setServiceState(ServiceStatus.KILLED)
        stopForeground(true)
        stopSelf()
    }

    private fun setServiceState(status: ServiceStatus){
        running = when(status){
            ServiceStatus.ENABLING -> true
            ServiceStatus.ENABLED -> true
            ServiceStatus.DISABLING -> true
            ServiceStatus.DISABLED -> false
            ServiceStatus.KILLED -> false
        }

        when(status){
            ServiceStatus.ENABLED -> emitState()
            ServiceStatus.DISABLED -> emitState()
            else -> {}
        }

        forEachListener {
            it.onServiceStateChange(status)
        }
    }

    /**
     * Broadcast the current state of the app to anything listening inside of the app
     */
    private fun emitState() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(SERVICE_STATUS_BROADCAST).also {
                it.putExtra("value", running)
            }
        )
    }

    /**
     * Setup the foreground service notification
     */
    private fun setupForeground() {
        log.d{
            "Start foreground service"
        }
        val intentHide = Intent(SERVICE_STOP_BROADCAST)
        intentHide.setPackage(packageName)
        val hide = PendingIntent.getBroadcast(this,
                System.currentTimeMillis().toInt(), intentHide, PendingIntent.FLAG_CANCEL_CURRENT)
        val notification = NotificationCompat.Builder(this, CONTROL_SERVICE)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.appBackgrounded))
                .addAction(R.drawable.ic_power_settings_new_black_24dp, getString(R.string.terminateApp), hide)
                .setSmallIcon(R.drawable.ic_settings_remote_black_24dp)
        startForeground(Random().nextInt(), notification.build())
    }

    companion object {
        var defaultHandlerThread = HandlerThread(ControlSDKService::class.java.simpleName).also {
            it.start()
        }
        val defaultLooper : Looper
            get() {
                return defaultHandlerThread.looper
            }

        var allowNotificationForExceptions = true
        const val START = 1
        const val STOP = 2
        const val RESET = 4
        const val ATTACH_COMPONENT = 5
        const val DETACH_COMPONENT = 6
        const val EVENT_BROADCAST = 7
        const val ATTACH_LISTENER_OR_CONTROLLER = 8
        const val DETACH_LISTENER_OR_CONTROLLER = 9
        const val STATUS_UPDATE = 10
        const val CONTROL_SERVICE = "control_service"
        const val SERVICE_STATUS_BROADCAST = "org.btelman.controlsdk.ServiceStatus"
        const val SERVICE_STOP_BROADCAST = "org.btelman.controlsdk.request.stop"
        val loggerID: String = ControlSDKService::class.java.name.also {
            LogUtilInstance(CONTROL_SERVICE, LogLevel.ERROR).also {
                Log.d("ControlSDKService", "Setup logger")
                if(!LogUtil.customLoggers.containsKey(ControlSDKService::class.java.name))
                    LogUtil.addCustomLogUtilInstance(ControlSDKService::class.java.name, it)
            }
        }
    }
}