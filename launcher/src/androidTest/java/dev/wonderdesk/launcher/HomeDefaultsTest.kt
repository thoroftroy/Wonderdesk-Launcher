package dev.wonderdesk.launcher

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wonderdesk.shared.LaunchableApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeDefaultsTest {
    @Test fun defaultsIncludeBlissPlayerAndExcludeLauncher() {
        fun app(packageName: String) = LaunchableApp(ComponentName(packageName, "MainActivity"), packageName, null, "1")
        val bliss = app("com.blissplayer.music.debug")
        val wayfinder = app("dev.wonderdesk.wayfinder")
        val home = app("dev.wonderdesk.launcher")
        val layout = defaultHomeLayout(listOf(home, bliss, wayfinder), home.component.packageName)
        assertEquals(listOf(wayfinder.key, bliss.key), layout.dock)
        assertTrue(bliss.key in layout.favorites)
        assertFalse(home.key in layout.favorites)
    }
    @Test fun releasedBlissIsPreferredWhenBothVariantsExist() {
        fun app(packageName: String) = LaunchableApp(ComponentName(packageName, "MainActivity"), packageName, null, "1")
        val release = app("com.blissplayer.music")
        val debug = app("com.blissplayer.music.debug")
        val layout = defaultHomeLayout(listOf(debug, release), "dev.wonderdesk.launcher")
        assertEquals(listOf(release.key), layout.dock)
    }
}
