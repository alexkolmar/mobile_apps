package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.db.BlockedAppRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: FlightModeViewModel

    // Activity launcher for system VPN permission dialog
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnBlockingService()
        } else {
            Toast.makeText(
                this,
                "VPN Permission is required to simulate in-flight mode.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Listens to status updates inside LocalBlockVpnService (e.g. if stopped from notification)
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocalBlockVpnService.ACTION_STATUS_CHANGED) {
                val isRunning = intent.getBooleanExtra(LocalBlockVpnService.EXTRA_IS_RUNNING, false)
                viewModel.setVpnState(isRunning)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize dependencies manually via simple custom factory injection
        val app = application as FlightModeApp
        val factory = FlightModeViewModelFactory(app, app.repository)
        viewModel = ViewModelProvider(this, factory)[FlightModeViewModel::class.java]

        // Dynamically request notification permissions on Android 13+ on launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }

        // Register status receiver from background service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStatusReceiver, IntentFilter(LocalBlockVpnService.ACTION_STATUS_CHANGED), RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(vpnStatusReceiver, IntentFilter(LocalBlockVpnService.ACTION_STATUS_CHANGED))
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    FlightModeMainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onToggleVpn = { active ->
                            if (active) {
                                prepareAndStartVpn()
                            } else {
                                stopVpnBlockingService()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnBlockingService()
        }
    }

    private fun startVpnBlockingService() {
        lifecycleScope.launch(Dispatchers.IO) {
            val blockedApps = (application as FlightModeApp).repository.allBlockedApps.first().map { it.packageName }
            if (blockedApps.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Please select at least one application down below first!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            val intent = Intent(this@MainActivity, LocalBlockVpnService::class.java).apply {
                putStringArrayListExtra(LocalBlockVpnService.EXTRA_BLOCKED_PACKAGES, ArrayList(blockedApps))
            }
            
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                viewModel.setVpnState(true)
            }
        }
    }

    private fun stopVpnBlockingService() {
        val intent = Intent(this, LocalBlockVpnService::class.java).apply {
            action = LocalBlockVpnService.ACTION_STOP
        }
        startService(intent)
        viewModel.setVpnState(false)
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkVpnRunning()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(vpnStatusReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlightModeMainScreen(
    viewModel: FlightModeViewModel,
    modifier: Modifier = Modifier,
    onToggleVpn: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val appItems by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterMode by viewModel.filterMode.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedTab by remember { mutableStateOf(NavigationTab.APPS) }

    // Derive isolated apps count
    val isolatedAppsCount = remember(appItems) {
        appItems.count { it.isBlocked }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // --- 1. Custom Slick Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left back-styled control icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
                    .clickable {
                        Toast
                            .makeText(context, "System isolation is active", Toast.LENGTH_SHORT)
                            .show()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Modern Elegant Header title
            Text(
                text = "App Flight Control",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Right settings icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
                    .clickable {
                        selectedTab = NavigationTab.RULES
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings info",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // --- 2. Main Content Body per active tab ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            when (selectedTab) {
                NavigationTab.STATUS -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Master Stealth Mode Blue Card
                        MasterStealthCard(
                            isVpnActive = isVpnActive,
                            isolatedCount = isolatedAppsCount,
                            onToggleVpn = onToggleVpn
                        )

                        Text(
                            text = "Currently Isolated Applications",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        // Isolated List or Empty illustration
                        val isolatedItems = remember(appItems) { appItems.filter { it.isBlocked } }
                        if (isolatedItems.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AirplanemodeInactive,
                                        contentDescription = "Disconnected",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(64.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "No Apps Isolated",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Go to the \"Apps\" tab below, search your applications, and turn on the flight mode toggle to block their connection.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(
                                    items = isolatedItems,
                                    key = { it.packageName }
                                ) { app ->
                                    AppRowItem(
                                        app = app,
                                        onToggle = { viewModel.toggleAppFlightMode(app) }
                                    )
                                }
                            }
                        }
                    }
                }

                NavigationTab.APPS -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Glassmorphic Frost Search Input Container
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color.White.copy(alpha = 0.65f))
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(28.dp)
                                )
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search icon",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search installed applications...",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                    // Inline simple clean search input logic
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.searchQuery.value = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontSize = 14.sp
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.searchQuery.value = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Filter classification indicators
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterMode.values().forEach { mode ->
                                val isSelected = filterMode == mode
                                val label = when (mode) {
                                    FilterMode.ALL -> "All Apps"
                                    FilterMode.USER_INSTALLED -> "Installed"
                                    FilterMode.SYSTEM_APPS -> "System"
                                    FilterMode.FLIGHT_MODE_ON -> "Flight Mode ON"
                                }

                                SleekFilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.filterMode.value = mode },
                                    label = label
                                )
                            }
                        }

                        // Listing Apps
                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Scanning packages...",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (appItems.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SearchOff,
                                        contentDescription = "Empty list",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(64.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "No applications found",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "We couldn't scan any packages matching security or spelling parameters. Modify search query filters.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(
                                    items = appItems,
                                    key = { it.packageName }
                                ) { app ->
                                    AppRowItem(
                                        app = app,
                                        onToggle = { viewModel.toggleAppFlightMode(app) }
                                    )
                                }
                            }
                        }
                    }
                }

                NavigationTab.RULES -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "How App Flight Control Works",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        Text(
                            text = "Standard Android security prevents an app from directly stopping another app's cellular or WiFi antennas. To deliver this simulation legally and securely without root permissions, we leverage a native Local VPN Loopback Service.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "🔒 100% Offline & Private",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Text(
                                    text = "Unlike standard commercial VPN products, our app runs completely locally inside your sandboxed device framework. NO external servers are ever connected, and absolutely zero navigation data leaves the phone.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "⚙️ Blackhole Routing Engine",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Text(
                                    text = "When you turn on Flight Mode for an application, its networking requests are routed exclusively into our simulated local dummy socket, which drops the tcp/udp connection packets. The app instantly falls back into 'offline' state, while all other applications continue to enjoy full internet speeds over LTE/WiFi.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Tips icon",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Quick Setup Tip",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "To ensure maximum simulation accuracy, toggle the app flight switches first, then slide the global 'Master Intercept' switch to ON. You can add or remove applications live anytime!",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 3. Custom Sleek Dock Navigation Bar ---
        CustomBottomDock(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )
    }
}

enum class NavigationTab {
    STATUS,
    APPS,
    RULES
}

@Composable
fun MasterStealthCard(
    isVpnActive: Boolean,
    isolatedCount: Int,
    onToggleVpn: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFD3E4FF)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isVpnActive) Color(0xFF005FB0).copy(alpha = 0.3f) else Color.Transparent,
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Master Stealth Mode",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF001C38)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Isolate all marked apps from cellular & WiFi networks instantly.",
                        fontSize = 13.sp,
                        color = Color(0xFF001C38).copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Custom matching design switch
                Switch(
                    checked = isVpnActive,
                    onCheckedChange = { onToggleVpn(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF005FB0),
                        uncheckedThumbColor = Color(0xFF757780),
                        uncheckedTrackColor = Color(0xFFE1E2EC)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info design chips shown side by side
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // System Status Chip
                Box(
                    modifier = Modifier
                        .background(
                            color = if (isVpnActive) Color(0xFF005FB0) else Color(0xFF757780),
                            shape = RoundedCornerShape(100.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isVpnActive) "System Active" else "Offline Simulation Set",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Apps Isolated Chip
                Box(
                    modifier = Modifier
                        .background(
                            color = Color(0xFF005FB0).copy(alpha = 0.1f),
                            shape = RoundedCornerShape(100.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "$isolatedCount Apps Isolated",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF005FB0)
                    )
                }
            }
        }
    }
}

@Composable
fun CustomBottomDock(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit
) {
    // Dock container
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding() // Safeguard bottom gesture area
            .height(80.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            // Tab 1: Status
            DockItem(
                label = "Status",
                icon = Icons.Default.Home,
                selected = selectedTab == NavigationTab.STATUS,
                onClick = { onTabSelected(NavigationTab.STATUS) }
            )

            // Tab 2: Apps
            DockItem(
                label = "Apps",
                icon = Icons.Default.GridView,
                selected = selectedTab == NavigationTab.APPS,
                onClick = { onTabSelected(NavigationTab.APPS) }
            )

            // Tab 3: Rules
            DockItem(
                label = "Rules",
                icon = Icons.Default.Shield,
                selected = selectedTab == NavigationTab.RULES,
                onClick = { onTabSelected(NavigationTab.RULES) }
            )
        }
    }
}

@Composable
fun RowScope.DockItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (selected) 1f else 0.6f

    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Active visual horizontal pill matching Tailwind
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(32.dp)
                .background(
                    color = if (selected) Color(0xFFD3E4FF) else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color(0xFF001C38) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Stylish label formatting uppercase and wider
        Text(
            text = label.uppercase(),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 1.sp,
            color = if (selected) Color(0xFF001C38) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )
    }
}

@Composable
fun AppRowItem(
    app: AppItem,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Choose dynamic soft pastel colors from package hashCode for icon container
    val backgroundColors = remember {
        listOf(
            Color(0xFFE7E0FF), // Soft Lavender
            Color(0xFFFFDAD6), // Soft Peach
            Color(0xFFD3E4FF), // Soft Blue
            Color(0xFFE0E2EC), // Soft Slate
            Color(0xFFD5F3E5), // Soft Mint
            Color(0xFFFFF2C2), // Soft Sunshine
        )
    }
    
    val containerBg = remember(app.packageName) {
        backgroundColors[Math.abs(app.packageName.hashCode()) % backgroundColors.size]
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (app.isBlocked) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Elegant package container with dynamic background styling
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerBg),
                contentAlignment = Alignment.Center
            ) {
                AppIconView(
                    packageName = app.packageName,
                    modifier = Modifier
                        .size(36.dp)
                        .padding(2.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )

                    if (app.isSystem) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "SYSTEM",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = app.packageName,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (app.isBlocked) "Network access denied" else "Online (Tap to isolate)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (app.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Switch layout indicator matching custom design spec
            Switch(
                checked = app.isBlocked,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF005FB0),
                    uncheckedThumbColor = Color(0xFF757780),
                    uncheckedTrackColor = Color(0xFFE1E2EC)
                )
            )
        }
    }
}

@Composable
fun AppIconView(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                // Add a description for accessibility compliance
                contentDescription = "Application icon"
            }
        },
        update = { imageView ->
            try {
                val pm = context.packageManager
                val icon = pm.getApplicationIcon(packageName)
                imageView.setImageDrawable(icon)
            } catch (e: Exception) {
                try {
                    imageView.setImageDrawable(context.packageManager.defaultActivityIcon)
                } catch (e2: Exception) {
                    imageView.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        },
        modifier = modifier
    )
}

@Composable
fun SleekFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            )
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
