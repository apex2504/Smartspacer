

package com.kieronquinn.app.smartspacer.service

import android.annotation.SuppressLint
import android.app.IActivityManager
import android.app.IActivityTaskManager
import android.app.PendingIntent
import android.app.prediction.AppPredictionContext
import android.app.prediction.AppPredictionManager
import android.app.prediction.AppPredictor
import android.app.smartspace.ISmartspaceManager
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSessionId
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ILauncherApps
import android.content.pm.ParceledListSlice
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraManager
import android.net.wifi.IWifiManager
import android.os.*
import android.system.Os
import com.kieronquinn.app.smartspacer.*
import com.kieronquinn.app.smartspacer.Smartspacer.Companion.PACKAGE_KEYGUARD
import com.kieronquinn.app.smartspacer.model.appshortcuts.AppShortcutIcon
import com.kieronquinn.app.smartspacer.model.appshortcuts.ShortcutQueryWrapper
import com.kieronquinn.app.smartspacer.utils.appprediction.AppPredictionSessionWrapper
import com.kieronquinn.app.smartspacer.utils.context.ShellContext
import com.kieronquinn.app.smartspacer.utils.extensions.getIdentifier
import com.kieronquinn.app.smartspacer.utils.extensions.getIntent
import com.kieronquinn.app.smartspacer.utils.extensions.getPrivilegedConfiguredNetworks
import com.kieronquinn.app.smartspacer.utils.extensions.getTaskPackages
import com.kieronquinn.app.smartspacer.utils.extensions.getUser
import com.kieronquinn.app.smartspacer.utils.extensions.processDied
import com.kieronquinn.app.smartspacer.utils.extensions.toggleTorch
import com.kieronquinn.app.smartspacer.utils.smartspace.ProxySmartspaceSession
import com.topjohnwu.superuser.internal.Utils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import rikka.shizuku.SystemServiceHelper
import java.util.*
import java.util.concurrent.Executors
import kotlin.system.exitProcess

@Suppress("DEPRECATION")
@SuppressLint("WrongConstant", "RestrictedApi")
class SmartspacerShizukuService: ISmartspacerShizukuService.Stub() {

    companion object {
        //5 crashes in 5 seconds will trigger safe mode if required
        private const val CRASH_COUNT = 5
        private const val CRASH_BUFFER = 5000L
        const val PACKAGE_SHELL = "com.android.shell"

        const val ROOT_UID = Process.ROOT_UID
        const val SHELL_UID = Process.SHELL_UID
        const val ROOT_PACKAGE = "android"
        const val SHELL_PACKAGE = "com.android.shell"
        private const val PACKAGE_ASI = "com.google.android.as"
    }

    private val canUseRoot = Process.myUid() == ROOT_UID

    init {
        //Switch to shell if needed, we can still spawn runtimes as root though
        if(canUseRoot){
            Os.setuid(SHELL_UID)
        }
    }

    private val context = Utils.getContext()
    private val shellContext = ShellContext(context, false)
    private val scope = MainScope()

    private val packageManager by lazy {
        context.packageManager
    }

    private val smartspaceManager by lazy {
        context.getSystemService("smartspace") as SmartspaceManager
    }

    private val systemSmartspaceManager by lazy {
        val proxy = SystemServiceHelper.getSystemService("smartspace")
        ISmartspaceManager.Stub.asInterface(proxy)
    }

    private val launcherApps by lazy {
        val proxy = SystemServiceHelper.getSystemService("launcherapps")
        ILauncherApps.Stub.asInterface(proxy)
    }

    private val activityTaskManager by lazy {
        val proxy = SystemServiceHelper.getSystemService("activity_task")
        IActivityTaskManager.Stub.asInterface(proxy)
    }

    private val activityManager by lazy {
        val stub = SystemServiceHelper.getSystemService("activity")
        IActivityManager.Stub.asInterface(stub)
    }

    private val userManager by lazy {
        val proxy = SystemServiceHelper.getSystemService("user")
        IUserManager.Stub.asInterface(proxy)
    }

    private val appPredictionManager by lazy {
        context.getSystemService("app_prediction") as? AppPredictionManager
    }

    private val wifiManager by lazy {
        val proxy = SystemServiceHelper.getSystemService("wifi")
        IWifiManager.Stub.asInterface(proxy)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var appPredictionSession: AppPredictionSessionWrapper? = null
    private val appPredictionExecutor = Executors.newSingleThreadExecutor()
    private var lastTaskListPackages = emptyList<String>()
    private var taskObserver: ITaskObserver? = null
    private var suppressCrash = false
    private val lastCrashTimestamps = HashMap<String, HashSet<Long>>()
    private var crashListener: ISmartspacerCrashListener? = null

    private fun getUserHandle(): UserHandle {
        return context.getUser()
    }

    private fun getUserId(): Int {
        return getUserHandle().getIdentifier()
    }

    override fun ping() = true

    override fun isRoot() = canUseRoot

    override fun setSmartspaceService(
        component: ComponentName,
        userId: Int,
        killSystemUi: Boolean
    ) {
        val componentName = component.flattenToString()
        runCommand("cmd smartspace set temporary-service $userId $componentName 30000")
        if(killSystemUi) killSystemUi()
    }

    override fun clearSmartspaceService(userId: Int, killSystemUi: Boolean) {
        runCommand("cmd smartspace set temporary-service $userId")
        if(killSystemUi) killSystemUi()
    }

    override fun createSmartspaceSession(config: SmartspaceConfig): ISmartspaceSession {
        return ProxySmartspaceSession(smartspaceManager.createSmartspaceSession(config))
    }

    override fun createAppPredictorSession(listener: IAppPredictionOnTargetsAvailableListener) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        appPredictionSession?.session?.destroy()
        val appPredictionContext = AppPredictionContext.Builder(context)
            .setUiSurface("home")
            .setPredictedTargetCount(10)
            .build()
        val callback = AppPredictor.Callback {
            listener.onTargetsAvailable(ParceledListSlice(it))
        }
        try {
            appPredictionSession = appPredictionManager?.createAppPredictionSession(
                appPredictionContext
            )?.also {
                it.registerPredictionUpdates(appPredictionExecutor, callback)
            }?.let {
                AppPredictionSessionWrapper(it)
            }
        }catch (e: IllegalStateException){
            //Client has already been destroyed
        }
    }

    override fun destroyAppPredictorSession() {
        appPredictionSession?.session?.destroy()
    }

    override fun toggleTorch() = runWithClearedIdentity {
        scope.launch {
            cameraManager.toggleTorch()
        }
        Unit
    }

    override fun destroySmartspaceSession(sessionId: SmartspaceSessionId){
        systemSmartspaceManager.destroySmartspaceSession(sessionId)
    }

    override fun getShortcuts(query: ShortcutQueryWrapper): ParceledListSlice<ShortcutInfo> {
        val token = clearCallingIdentity()
        val shortcuts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            launcherApps.getShortcuts(
                PACKAGE_SHELL,
                query.toSystemShortcutQueryWrapper(),
                getUserHandle()
            ) as ParceledListSlice<ShortcutInfo>
        } else {
            launcherApps.getShortcuts(
                PACKAGE_SHELL,
                0,
                null,
                null,
                null,
                query.queryFlags,
                getUserHandle()
            )
        }
        restoreCallingIdentity(token)
        return shortcuts
    }

    override fun getAppShortcutIcon(packageName: String, shortcutId: String): AppShortcutIcon? {
        val token = clearCallingIdentity()
        //Occasionally the shortcut can be removed at exactly the wrong time, since we can't lock
        //the same way as the system, so return a null icon in that case.
        val result = try {
            val iconResId =
                launcherApps.getShortcutIconResId(PACKAGE_SHELL, packageName, shortcutId, getUserId())
            val iconUri =
                launcherApps.getShortcutIconUri(PACKAGE_SHELL, packageName, shortcutId, getUserId())
            val iconFd =
                launcherApps.getShortcutIconFd(PACKAGE_SHELL, packageName, shortcutId, getUserId())
            val icon = when {
                //Prioritise uri over res ID
                iconUri != null -> {
                    Icon.createWithContentUri(iconUri)
                }
                iconResId != 0 -> {
                    Icon.createWithResource(packageName, iconResId)
                }
                else -> null
            }
            AppShortcutIcon(icon = icon, descriptor = iconFd)
        }catch (e: NullPointerException){
            null
        }
        restoreCallingIdentity(token)
        return result
    }

    override fun startShortcut(packageName: String, shortcutId: String) {
        scope.launch {
            val token = clearCallingIdentity()
            allowBackgroundStarts()
            delay(250)
            launcherApps.startShortcut(
                PACKAGE_SHELL,
                packageName,
                null,
                shortcutId,
                null,
                null,
                getUserId()
            )
            delay(250)
            resetBackgroundStarts()
            restoreCallingIdentity(token)
        }
    }

    override fun getUserName(): String? {
        return try {
            userManager.userName
        }catch (e: SecurityException){
            null //Blocked on some devices
        }
    }

    private fun allowBackgroundStarts() {
        runCommand("cmd device_config put activity_manager default_background_activity_starts_enabled true")
    }

    private fun resetBackgroundStarts() {
        runCommand("cmd device_config put activity_manager default_background_activity_starts_enabled false")
    }

    override fun destroy() {
        scope.cancel()
        exitProcess(0)
    }

    private fun killSystemUi() {
        suppressCrash = true
        if(canUseRoot){
            runCommand("pkill systemui")
        }else{
            runCommand("am crash $PACKAGE_KEYGUARD")
        }
        runCommand("am force-stop com.google.android.apps.nexuslauncher")
        scope.launch {
            delay(500L)
            suppressCrash = false
        }
    }

    private fun runCommand(vararg commands: String) {
        Runtime.getRuntime().let { runtime ->
            commands.forEach { runtime.exec(it) }
        }
    }

    @Synchronized
    private fun <T> runWithClearedIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return block().also {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun setCrashListener(listener: ISmartspacerCrashListener?) {
        this.crashListener = listener
    }

    override fun setTaskObserver(observer: ITaskObserver?) {
        taskObserver = observer?.also {
            it.onTasksChanged(lastTaskListPackages)
        }
    }

    override fun resolvePendingIntent(pendingIntent: PendingIntent?): Intent? {
        if(pendingIntent == null) return null
        return pendingIntent.getIntent()
    }

    private fun setupCrashListener() = scope.launch {
        activityManager.processDied().collect { uid ->
            if(suppressCrash) return@collect
            val packages = packageManager.getPackagesForUid(uid) ?: return@collect
            packages.forEach { pkg ->
                onPackageDied(pkg)
            }
        }
    }

    @Synchronized
    private fun onPackageDied(packageName: String) {
        if(packageName == PACKAGE_ASI) {
            try {
                crashListener?.onAsiStopped()
            }catch (e: RemoteException){
                //Remote process died
                crashListener = null
            }
            return
        }
        val timestamps = lastCrashTimestamps[packageName] ?: HashSet()
        val now = System.currentTimeMillis()
        timestamps.removeIf {
            now - it > CRASH_BUFFER
        }
        timestamps.add(now)
        if(timestamps.size >= CRASH_COUNT) {
            try {
                crashListener?.onPackageCrashed(packageName)
            }catch (e: RemoteException){
                //Remote process died
                crashListener = null
            }
        }
        lastCrashTimestamps[packageName] = timestamps
    }

    private fun setupTaskListener() = scope.launch {
        activityTaskManager.getTaskPackages(getUserId()).collect {
            lastTaskListPackages = it
            try {
                taskObserver?.onTasksChanged(it)
            }catch (e: RemoteException){
                //Task observer is dead
                taskObserver = null
            }
        }
    }

    init {
        setupTaskListener()
        setupCrashListener()
    }

    override fun grantRestrictedSettings() {
        runCommand("cmd appops set ${BuildConfig.APPLICATION_ID} ACCESS_RESTRICTED_SETTINGS allow")
    }

    override fun getSavedWiFiNetworks(): ParceledListSlice<*> {
        return wifiManager.getPrivilegedConfiguredNetworks(shellContext)
    }

}