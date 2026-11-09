package dev.wonderdesk.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import dev.wonderdesk.shared.AppFrame
import dev.wonderdesk.shared.AppInfo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Real cross-window blur behind the translucent home (API 31+). Solid-color
        // fixtures stay the same hue when blurred; detailed wallpapers frost.
        // Unsupported renderers keep the translucent fallback; never crash home.
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val attrs = window.attributes
                attrs.blurBehindRadius = 80
                window.attributes = attrs
            }
        }
        setContent {
            AppFrame(AppInfo("launcher", "Wonderdesk", "A touch-first home with installed apps, a dock, favorites, folders, widgets and local app search.", "No protected OS replacements, icon packs, cross-device sync or drag-and-drop yet. Widget support depends on installed providers."), transparentBackground = true) { LauncherScreen() }
        }
    }
}

fun defaultHomeIntent(context: Context): Intent {
    val roles = context.getSystemService(RoleManager::class.java)
    return if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) roles.createRequestRoleIntent(RoleManager.ROLE_HOME) else Intent(Settings.ACTION_HOME_SETTINGS)
}
