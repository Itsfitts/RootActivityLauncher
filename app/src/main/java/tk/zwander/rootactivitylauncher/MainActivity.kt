package tk.zwander.rootactivitylauncher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.BadParcelableException
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import tk.zwander.rootactivitylauncher.data.component.ComponentType
import tk.zwander.rootactivitylauncher.data.model.AppModel
import tk.zwander.rootactivitylauncher.data.model.BaseInfoModel
import tk.zwander.rootactivitylauncher.data.model.FavoriteModel
import tk.zwander.rootactivitylauncher.data.model.MainModel
import tk.zwander.rootactivitylauncher.data.prefs
import tk.zwander.rootactivitylauncher.util.LocalFavoriteModel
import tk.zwander.rootactivitylauncher.util.LocalMainModel
import tk.zwander.rootactivitylauncher.util.PermissionResultListener
import tk.zwander.rootactivitylauncher.util.distinctByPackageName
import tk.zwander.rootactivitylauncher.util.forEachParallel
import tk.zwander.rootactivitylauncher.util.getInstalledPackagesCompat
import tk.zwander.rootactivitylauncher.util.getPackageInfoCompat
import tk.zwander.rootactivitylauncher.util.hasShizukuPermission
import tk.zwander.rootactivitylauncher.util.requestShizukuPermission
import tk.zwander.rootactivitylauncher.util.updateProgress
import tk.zwander.rootactivitylauncher.views.MainView
import tk.zwander.rootactivitylauncher.views.theme.Theme
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("RestrictedApi")
open class MainActivity : ComponentActivity(), CoroutineScope by MainScope(),
    PermissionResultListener {
    protected open val isForTasker = false
    protected open var selectedItem: Pair<ComponentType, ComponentName>? = null

    protected val model = MainModel()
    private val modelScope by lazy {
        CoroutineScope(Dispatchers.IO + coroutineContext + Job(coroutineContext[Job]))
    }

    private val favoriteModel by lazy {
        FavoriteModel(
            activityKeys = prefs.favoriteActivities,
            serviceKeys = prefs.favoriteServices,
            receiverKeys = prefs.favoriteReceivers,
            context = this@MainActivity,
            scope = modelScope,
            mainModel = model
        )
    }

    private val packageUpdateReceiver = object : BroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun register() {
            registerReceiver(this, filter)
        }

        fun unregister() {
            unregisterReceiver(this)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            launch(Dispatchers.IO) {
                val pkg = intent?.data?.schemeSpecificPart

                when (intent?.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        if (pkg != null) {
                            val pkgInfo = getPackageInfo(pkg)

                            if (pkgInfo != null) {
                                val loaded = loadApp(pkgInfo, packageManager)

                                model.apps.value =
                                    (model.apps.value + loaded).distinctByPackageName()
                            }
                        }
                    }

                    Intent.ACTION_PACKAGE_REMOVED -> {
                        if (pkg != null) {
                            val new = model.apps.value.toMutableList().apply {
                                removeAll { it is AppModel && it.info.packageName == pkg }
                            }

                            model.apps.value = new.distinctByPackageName()
                        }
                    }

                    Intent.ACTION_PACKAGE_CHANGED, Intent.ACTION_PACKAGE_REPLACED -> {
                        if (pkg != null) {
                            val old = ArrayList(model.apps.value)
                            val oldIndex =
                                old.indexOfFirst { it is AppModel && it.info.packageName == pkg }
                                    .takeIf { it != -1 } ?: return@launch
                            val pkgInfo = getPackageInfo(pkg)

                            if (pkgInfo != null) {
                                old[oldIndex] = loadApp(pkgInfo, packageManager)

                                model.apps.value = old.distinctByPackageName()
                            }
                        }
                    }
                }
            }
        }
    }

    private var currentDataJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //Maybe if we ever get a KNOX license key...
//        val cInfoClass = Class.forName("com.samsung.android.knox.ContextInfo")
//        val cInfo = cInfoClass.getDeclaredConstructor(Int::class.java)
//                .newInstance(myUid())
//
//        val lmClass = Class.forName("com.samsung.android.knox.license.IEnterpriseLicense\$Stub")
//        val lm = lmClass.getMethod("asInterface", IBinder::class.java)
//                .invoke(null, SystemServiceHelper.getSystemService("enterprise_license_policy"))
//
//        lmClass.getMethod("activateLicense", cInfoClass, String::class.java, String::class.java, String::class.java)
//                .invoke(lm, cInfo, "<KPE_LICENSE>", null, null)

        packageUpdateReceiver.register()

        if (Shizuku.pingBinder()) {
            if (!hasShizukuPermission) {
                requestShizukuPermission(::onPermissionResult)
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            Theme {
                val darkTheme = isSystemInDarkTheme()
                val variant = MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp)

                LaunchedEffect(variant) {
                    window.navigationBarColor = variant.toArgb()
                }

                LaunchedEffect(darkTheme) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        window.decorView.apply {
                            @Suppress("DEPRECATION")
                            systemUiVisibility = if (darkTheme) {
                                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                            } else {
                                systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            }
                        }
                    }
                }

                CompositionLocalProvider(
                    LocalMainModel provides model,
                    LocalFavoriteModel provides favoriteModel,
                ) {
                    MainView(
                        modifier = Modifier.fillMaxSize(),
                        onItemSelected = {
                            selectedItem = it.type() to it.component
                        },
                        isForTasker = isForTasker,
                        onRefresh = {
                            currentDataJob?.cancel()
                            currentDataJob = loadDataAsync()
                        }
                    )
                }
            }
        }

        currentDataJob = loadDataAsync()
    }

    override fun onPermissionResult(granted: Boolean) {
        // Currently no-op
    }

    override fun onDestroy() {
        super.onDestroy()

        packageUpdateReceiver.unregister()
        cancel()
    }

    @SuppressLint("InlinedApi")
    private suspend fun loadAppsSeparately(): List<PackageInfo> {
        return packageManager.getInstalledPackagesCompat(PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { info ->
                //Try to get all the components for this package at once.
                packageManager.getPackageInfoCompat(
                    info.packageName,
                    PackageManager.GET_ACTIVITIES or
                            PackageManager.GET_SERVICES or
                            PackageManager.GET_RECEIVERS or
                            PackageManager.GET_PERMISSIONS or
                            PackageManager.GET_CONFIGURATIONS,
                ) ?: run {
                    listOf(
                        PackageManager.GET_ACTIVITIES,
                        PackageManager.GET_SERVICES,
                        PackageManager.GET_RECEIVERS,
                        PackageManager.GET_PERMISSIONS,
                        PackageManager.GET_CONFIGURATIONS,
                    ).map { flag ->
                        async {
                            val element = packageManager.getPackageInfoCompat(
                                info.packageName,
                                flag,
                            )
                            element?.activities?.let { info.activities = it }
                            element?.services?.let { info.services = it }
                            element?.receivers?.let { info.receivers = it }
                            element?.permissions?.let { info.permissions = it }
                            element?.configPreferences?.let {
                                info.configPreferences = it
                            }
                            element?.reqFeatures?.let { info.reqFeatures = it }
                            element?.featureGroups?.let { info.featureGroups = it }
                        }
                    }.awaitAll()

                    info
                }
            }
    }

    @SuppressLint("InlinedApi")
    private fun loadDataAsync(silent: Boolean = false): Job {
        return launch {
            if (!silent) {
                model.progress.value = 0f
            }

            //This mess is because of a bug in Marshmallow and possibly earlier that
            //causes getInstalledPackages() to fail because one of the PackageInfo objects
            //is too large. It'll throw a DeadObjectException internally and then just return
            //as much as it already transferred before the error. The bug seems to be fixed in
            //Nougat and later.
            //If the device is on Marshmallow, retrieve a list of packages without any components
            //attached. Attempt to retrieve all components at once per-app. If that fails, retrieve
            //each set of components individually and combine them.
            //https://twitter.com/Wander1236/status/1412928863798190083?s=20
            val apps = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
                    try {
                        packageManager.getInstalledPackagesCompat(
                            PackageManager.GET_ACTIVITIES or
                                    PackageManager.GET_SERVICES or
                                    PackageManager.GET_RECEIVERS or
                                    PackageManager.GET_PERMISSIONS or
                                    PackageManager.GET_CONFIGURATIONS or
                                    PackageManager.MATCH_DISABLED_COMPONENTS,
                        )
                    } catch (e: BadParcelableException) {
                        loadAppsSeparately()
                    }
                } else {
                    loadAppsSeparately()
                }
            }

            val max = apps.size - 1
            val loaded = ConcurrentLinkedQueue<BaseInfoModel>()

            loaded.add(favoriteModel)

            val progressIndex = atomic(0)
            val lastUpdate = atomic(0L)

            apps.forEachParallel(context = Dispatchers.IO, scope = this) { app ->
                loadApp(app, packageManager).let {
                    loaded.add(it)
                }

                if (!silent) {
                    updateProgress(progressIndex, lastUpdate, max) {
                        model.progress.value = it
                    }
                }
            }

            launch(Dispatchers.IO) {
                model.apps.value = loaded.distinctByPackageName()
                model.progress.value = null
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun getPackageInfo(packageName: String): PackageInfo? {
        return packageManager.getPackageInfoCompat(
            packageName,
            PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_CONFIGURATIONS or
                    PackageManager.MATCH_DISABLED_COMPONENTS,
        )
    }

    private suspend fun loadApp(app: PackageInfo, pm: PackageManager): AppModel = coroutineScope {
        val appLabel = app.applicationInfo.loadLabel(pm)

        return@coroutineScope AppModel(
            pInfo = app,
            label = appLabel.ifEmpty { app.packageName },
            context = this@MainActivity,
            scope = modelScope,
            mainModel = model,
        )
    }
}