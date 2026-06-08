package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.db.BlockedApp
import com.example.db.BlockedAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppItem(
    val packageName: String,
    val appLabel: String,
    val isSystem: Boolean,
    val isBlocked: Boolean
)

enum class FilterMode {
    ALL,
    USER_INSTALLED,
    SYSTEM_APPS,
    FLIGHT_MODE_ON
}

class FlightModeViewModel(
    private val application: FlightModeApp,
    private val repository: BlockedAppRepository
) : ViewModel() {
    private val pm: PackageManager = application.packageManager

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val searchQuery = MutableStateFlow("")
    val filterMode = MutableStateFlow(FilterMode.ALL)
    val isVpnActive = MutableStateFlow(false)
    val isLoading = MutableStateFlow(true)

    // Dynamic, reactive list of filtering and sorting app packages
    val uiState: StateFlow<List<AppItem>> = combine(
        _installedApps,
        repository.allBlockedApps,
        searchQuery,
        filterMode
    ) { installed, blocked, query, filter ->
        val blockedPackages = blocked.map { it.packageName }.toSet()
        val updatedList = installed.map { app ->
            app.copy(isBlocked = blockedPackages.contains(app.packageName))
        }

        updatedList.filter { app ->
            val matchesQuery = app.appLabel.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            
            val matchesFilter = when (filter) {
                FilterMode.ALL -> true
                FilterMode.USER_INSTALLED -> !app.isSystem
                FilterMode.SYSTEM_APPS -> app.isSystem
                FilterMode.FLIGHT_MODE_ON -> app.isBlocked
            }
            
            matchesQuery && matchesFilter
        }.sortedWith(
            compareByDescending<AppItem> { it.isBlocked }
                .thenBy { it.appLabel.lowercase() }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        loadInstalledApplications()
        checkVpnRunning()
    }

    fun loadInstalledApplications() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                // Fetch packages
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val appItems = apps.map { appInfo ->
                    AppItem(
                        packageName = appInfo.packageName,
                        appLabel = pm.getApplicationLabel(appInfo).toString(),
                        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        isBlocked = false
                    )
                }
                _installedApps.value = appItems
            } catch (e: Exception) {
                Log.e("FlightModeViewModel", "Failed to scan packages on device", e)
            } finally {
                isLoading.value = false
            }
        }
    }

    fun toggleAppFlightMode(app: AppItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (app.isBlocked) {
                repository.deleteByPackage(app.packageName)
            } else {
                repository.insert(BlockedApp(packageName = app.packageName, appName = app.appLabel))
            }
            
            // If blocking is already actively running, update the tunnel parameters
            if (isVpnActive.value) {
                restartVpnService()
            }
        }
    }

    fun checkVpnRunning() {
        isVpnActive.value = isServiceRunning(application, LocalBlockVpnService::class.java)
    }

    fun setVpnState(active: Boolean) {
        isVpnActive.value = active
    }

    private fun restartVpnService() {
        viewModelScope.launch(Dispatchers.IO) {
            val blockedApps = repository.allBlockedApps.first().map { it.packageName }
            if (blockedApps.isNotEmpty()) {
                val intent = Intent(application, LocalBlockVpnService::class.java).apply {
                    putStringArrayListExtra(LocalBlockVpnService.EXTRA_BLOCKED_PACKAGES, ArrayList(blockedApps))
                }
                application.startService(intent)
            } else {
                // If there are no configured apps left, turn off the VPN
                val intent = Intent(application, LocalBlockVpnService::class.java).apply {
                    action = LocalBlockVpnService.ACTION_STOP
                }
                application.startService(intent)
            }
        }
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val runningServices = manager.getRunningServices(Int.MAX_VALUE)
            for (service in runningServices) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("FlightModeViewModel", "Failed to query active services", e)
        }
        return false
    }
}

class FlightModeViewModelFactory(
    private val application: FlightModeApp,
    private val repository: BlockedAppRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FlightModeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FlightModeViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
