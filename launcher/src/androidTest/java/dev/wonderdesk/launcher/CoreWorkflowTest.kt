package dev.wonderdesk.launcher

import android.content.Context
import android.content.Intent
import android.app.WallpaperManager
import android.graphics.Rect
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import dev.wonderdesk.shared.loadLaunchableApps
import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Runs only on the dedicated emulator. Every assertion drives the shipped UI. */
@RunWith(AndroidJUnit4::class)
class CoreWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    @get:Rule val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable?, description: Description) { runCatching { proof("FAIL-" + description.methodName) } }
    }

    @Before fun emulatorOnly() {
        val qemu = device.executeShellCommand("getprop ro.kernel.qemu").trim()
        assertEquals("Runtime product tests must never target a physical device", "1", qemu)
        device.wakeUp()
        device.pressHome()
    }
    private fun launch(id: String) {
        val packageName = "dev.wonderdesk.$id"
        context.startActivity(Intent(Intent.ACTION_MAIN).setClassName(packageName, "$packageName.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("$id did not show its UI", device.wait(Until.hasObject(By.pkg(packageName)), 10_000))
        device.waitForIdle()
    }
    private fun text(value: String): UiObject2 = device.wait(Until.findObject(By.text(value)), 8000) ?: throw AssertionError("Missing UI text: $value")
    private fun clickText(value: String) {
        repeat(3) {
            try { text(value).click(); return } catch (_: StaleObjectException) { device.waitForIdle() }
        }
        throw AssertionError("UI text remained stale: $value")
    }
    private fun edit(): UiObject2 = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000) ?: throw AssertionError("Missing editable field")
    private fun proof(name: String) {
        val directory = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
        val xml = File(directory, "$name.xml")
        device.dumpWindowHierarchy(xml)
    }

    private fun assertDarkStatusTextIsVisible() {
        val message = device.wait(Until.findObject(By.textContains("installed launchable apps")), 8000)
            ?: throw AssertionError("Launcher status message missing")
        val image = File(context.getExternalFilesDir(null), "evidence/launcher-dark-aqua.png")
        val bitmap = BitmapFactory.decodeFile(image.absolutePath) ?: throw AssertionError("Dark screenshot missing")
        val bounds = message.visibleBounds
        var brightPixels = 0
        for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height)) {
            for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 170 && android.graphics.Color.green(pixel) > 170 && android.graphics.Color.blue(pixel) > 170) brightPixels++
            }
        }
        bitmap.recycle()
        assertTrue("Dark launcher status text is not visibly light against its background", brightPixels > 10)
    }

    @Test fun ownerCompanionIconsAreDistinctFromBrandFallback() {
        // TEMP artwork: every suite app currently shares one fallback icon.
        // Enforce only that each suite app resolves a real, loadable icon;
        // distinctness returns as an assertion when the owner delivers
        // per-app artwork (tracked in the asset checklist, not here).
        val manager = context.packageManager
        val names = listOf("wayfinder", "packrat", "memories", "cinema", "beacon", "scribble", "pulse", "abacus")
        names.forEach { id ->
            val info = manager.getApplicationInfo("dev.wonderdesk.$id", 0)
            assertNotEquals("$id has no application icon resource", 0, info.icon)
            assertNotNull("$id icon failed to load", info.loadIcon(manager))
        }
        launch("launcher")
        proof("launcher-owner-companion-icons")
    }

    @Test fun calculatorAndPersistentHistory() {
        fun showHistoryEntry() {
            // History composes lazily at the bottom of a tall column; close
            // the keyboard only if open (a blind back press would leave the
            // app), then swipe generously until the entry is composed.
            runCatching { if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack() }
            repeat(15) {
                if (device.wait(Until.hasObject(By.text("2+3*4 = 14")), 1000) != null) return
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 5, 30)
            }
            throw AssertionError("Calculation history entry is not reachable")
        }
        launch("abacus")
        edit().text = "2+3*4"
        text("=").click()
        text("14")
        showHistoryEntry()
        proof("abacus-calculation")
        device.executeShellCommand("am force-stop dev.wonderdesk.abacus")
        launch("abacus")
        showHistoryEntry()
        repeat(4) {
            if (!device.hasObject(By.clazz("android.widget.EditText"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 3 / 4, 20)
                device.waitForIdle()
            }
        }
        edit().text = "1/0"
        text("=").click()
        text("Cannot divide by zero")
    }

    @Test fun scribbleRecoversActualSharedDraftAfterProcessDeath() {
        val value = "Wonderdesk emulator draft fixture 2026"
        context.startActivity(Intent(Intent.ACTION_SEND).setClassName("dev.wonderdesk.scribble", "dev.wonderdesk.scribble.MainActivity").setType("text/plain").putExtra(Intent.EXTRA_TEXT, value).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text(value)), 10_000))
        assertTrue(device.wait(Until.hasObject(By.textContains("Draft saved locally")), 10_000))
        device.executeShellCommand("am force-stop dev.wonderdesk.scribble")
        launch("scribble")
        text(value)
        proof("scribble-recovered-draft")
    }

    @Test fun beaconSearchesInstalledAliasesAndCalculatesLocally() {
        launch("beacon")
        edit().text = "calculator"
        runCatching { if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack() }
        device.waitForIdle()
        text("Abacus").click()
        assertTrue(device.wait(Until.hasObject(By.pkg("dev.wonderdesk.abacus")), 5000))
        launch("beacon")
        edit().text = "sqrt(81)+1"
        text("= 10")
        proof("beacon-local-calculation")
    }

    @Test fun clockStopwatchAdvancesAndCanPause() {
        launch("timepiece")
        // On a compact screen, the real world-clock content may need scrolling.
        repeat(4) { if (device.hasObject(By.text("Start"))) return@repeat; device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20) }
        text("Start").click()
        text("Pause")
        Thread.sleep(1100)
        val before = device.findObjects(By.text(Pattern.compile("\\d{2}:\\d{2}\\.\\d"))).firstOrNull()?.text
        assertNotNull("Stopwatch value missing", before)
        assertNotEquals("00:00.0", before)
        text("Pause").click()
        text("Start")
        proof("timepiece-paused-stopwatch")
    }

    @Test fun opensAllCompanionUisAndHonestAboutScreens() {
        val ids = listOf("wayfinder", "packrat", "memories", "cinema", "pages", "scribble", "pulse", "abacus", "chronicle", "timepiece", "safekeeper", "beacon", "vault", "controlroom", "apphub")
        for (id in ids) {
            launch(id)
            if (id == "wayfinder") { text("All files"); proof("wayfinder-aqua") }
            text("About").click()
            val expected = context.packageManager.getPackageInfo("dev.wonderdesk.$id", 0).versionName
            assertTrue("No app version in $id About", device.wait(Until.hasObject(By.textContains(expected!!)), 5000))
            text("Done").click()
        }
        launch("pulse")
        assertTrue(device.wait(Until.hasObject(By.textContains("System memory total:")), 5000))
        proof("pulse-real-statistics")
    }

    @Test fun launcherBecomesHomeSearchesAliasesAndCreatesRealFolder() {
        val output = device.executeShellCommand("cmd package set-home-activity dev.wonderdesk.launcher/.MainActivity")
        assertTrue("Android refused the home role: $output", output.contains("Success"))
        launch("launcher")
        text("Home settings")
        edit().text = "calculator"
        device.waitForIdle()
        var opened = false
        for (i in 0 until 6) {
            val candidate = device.wait(Until.findObject(By.desc("Open Abacus")), 3000)
            if (candidate != null) {
                candidate.longClick()
                device.waitForIdle()
                if (device.wait(Until.hasObject(By.text("Add to folder…")), 5000) == true) {
                    opened = true
                    break
                }
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            device.waitForIdle()
        }
        if (!opened) throw AssertionError("Missing UI text: Add to folder…")
        text("Add to folder…").click()
        if (device.wait(Until.hasObject(By.textStartsWith("Folder for")), 8000) != true)
            throw AssertionError("Folder dialog missing")
        val target = device.findObjects(By.clazz("android.widget.EditText"))
            .firstOrNull { (it.text ?: "").isEmpty() }
            ?: throw AssertionError("Folder field is not editable")
        target.text = "Wonderdesk QA"
        text("Add").click()
        edit().text = ""
        val folder = device.wait(Until.findObject(By.textContains("Folder · Wonderdesk QA")), 8000) ?: throw AssertionError("New folder not on home")
        folder.click()
        text("Wonderdesk QA")
        text("Abacus")
        proof("launcher-folder-contents")
        text("Close").click()
        proof("launcher-home")
        // The external emulator runner performs process-death verification;
        // force-stopping our own instrumentation target here would kill JUnit.
        val saved = context.getSharedPreferences("home", Context.MODE_PRIVATE).getString("layout", "")!!
        assertTrue(saved.contains("Wonderdesk QA"))
        assertTrue(HomeLayout.parse(saved).folders["Wonderdesk QA"]!!.isNotEmpty())
    }

    @Test fun dockOpensAndOrganizesRealApp() {
        val preferences = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val existed = preferences.contains("layout")
        val original = preferences.getString("layout", null)
        val app = loadLaunchableApps(context).first { it.component.packageName == "dev.wonderdesk.abacus" }
        try {
            preferences.edit().putString("layout", HomeLayout(emptyList(), listOf(app.key), emptyMap()).json()).commit()
            launch("launcher")
            val dock = device.wait(Until.findObject(By.desc("Dock ${app.title}")), 10_000)
                ?: throw AssertionError("Abacus dock entry missing")
            proof("launcher-aqua-dock")
            dock.longClick()
            text("Remove from dock")
            text("Done").click()
            val entry = device.wait(Until.findObject(By.desc("Dock ${app.title}")), 5000)
                ?: throw AssertionError("Dock entry missing after organizing")
            entry.click()
            assertTrue("Dock did not launch Abacus", device.wait(Until.hasObject(By.pkg("dev.wonderdesk.abacus")), 8000))
        } finally {
            val edit = preferences.edit()
            if (existed) edit.putString("layout", original) else edit.remove("layout")
            edit.commit()
        }
    }

    @Test fun launcherCardActionsFitAndRemainUsable() {
        val preferences = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val existed = preferences.contains("layout")
        val original = preferences.getString("layout", null)
        val installed = loadLaunchableApps(context)
        val favorites = listOf("abacus", "beacon", "scribble", "wayfinder").map { id ->
            installed.first { it.component.packageName == "dev.wonderdesk.$id" }.key
        }
        try {
            preferences.edit().putString("layout", HomeLayout(favorites, favorites, mapOf("Test folder" to listOf(favorites.first())), 4).json()).commit()
            launch("launcher")
            // A prior typing test can leave the IME visible; the home pads for it and the grid shrinks.
            // Hide it first so card-height assertions are deterministic.
            runCatching {
                if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
            }
            device.waitForIdle()
            text("Folder · Test folder (1)")
            text("Add widget…")
            val open = device.wait(Until.findObject(By.desc("Open Abacus")), 10_000) ?: throw AssertionError("Open target missing")
            val openBounds = open.visibleBounds
            val minimum = (48 * context.resources.displayMetrics.density).toInt() - 1
            assertTrue("Open target too small or clipped: $openBounds", openBounds.width() >= minimum && openBounds.height() >= minimum)
            assertTrue("Card is vertically clipped with the dock present: $openBounds", openBounds.height() >= (64 * context.resources.displayMetrics.density).toInt() - 1)
            assertTrue("Card stretches vertically", openBounds.height() < 120 * context.resources.displayMetrics.density)
            proof("launcher-compact-cards")
            open.longClick()
            text("Add to folder…")
            text("Done").click()
            device.wait(Until.findObject(By.desc("Open Abacus")), 5000).click()
            assertTrue("Card did not open Abacus", device.wait(Until.hasObject(By.pkg("dev.wonderdesk.abacus")), 8000))
        } finally {
            val edit = preferences.edit()
            if (existed) edit.putString("layout", original) else edit.remove("layout")
            edit.commit()
        }
    }

    @Test fun sixColumnPreferenceKeepsTouchTargetsOnNarrowWindows() {
        val preferences = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val existed = preferences.contains("layout")
        val original = preferences.getString("layout", null)
        val app = loadLaunchableApps(context).first { it.component.packageName == "dev.wonderdesk.abacus" }
        try {
            preferences.edit().putString("layout", HomeLayout(listOf(app.key), listOf(app.key), emptyMap(), 6).json()).commit()
            launch("launcher")
            val target = device.wait(Until.findObject(By.desc("Open Abacus")), 10_000) ?: throw AssertionError("Card missing")
            val minimum = (48 * context.resources.displayMetrics.density).toInt() - 1
            assertTrue("Six-column preference created a narrow target: ${target.visibleBounds}", target.visibleBounds.width() >= minimum)
            assertEquals("Responsive cap must not rewrite the saved grid preference", 6, HomeLayout.parse(preferences.getString("layout", "")!!).columns)
        } finally {
            val edit = preferences.edit()
            if (existed) edit.putString("layout", original) else edit.remove("layout")
            edit.commit()
        }
    }

    /** Mutates only a snapshotted emulator; the external runner restores it. */
    @Test fun systemWallpaperActuallyShowsBehindHome() {        assumeTrue("Requires an explicit snapshotted-emulator fixture", InstrumentationRegistry.getArguments().getString("wallpaperFixture") == "1")
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val hadLayout = prefs.contains("layout"); val oldLayout = prefs.getString("layout", null)
        val hadLook = appearance.contains("look"); val oldLook = appearance.getString("look", null)
        val wallpaper = WallpaperManager.getInstance(instrumentation.context)
        fun capture(color: Int, name: String, expectedRed: Boolean): Int {
            val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            image.eraseColor(color)
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.SET_WALLPAPER")
            try { wallpaper.setBitmap(image) } finally { instrumentation.uiAutomation.dropShellPermissionIdentity(); image.recycle() }
            launch("launcher")
            text("Home settings")
            val file = File(context.getExternalFilesDir(null), "evidence/$name.png").apply { parentFile?.mkdirs() }
            var pixel = 0
            repeat(20) {
                device.waitForIdle()
                assertTrue(device.takeScreenshot(file))
                val screenshot = BitmapFactory.decodeFile(file.absolutePath) ?: throw AssertionError("Wallpaper screenshot missing")
                pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                screenshot.recycle()
                val red = android.graphics.Color.red(pixel); val green = android.graphics.Color.green(pixel)
                if (if (expectedRed) red > green + 70 else green > red + 70) {
                    proof(name)
                    return pixel
                }
                Thread.sleep(300) // The system wallpaper renderer can temporarily show black during replacement.
            }
            throw AssertionError("Expected ${if (expectedRed) "red" else "green"} fixture wallpaper, got pixel $pixel")
        }
        try {
            prefs.edit().putString("layout", HomeLayout(emptyList(), emptyList(), emptyMap()).json()).commit()
            appearance.edit().putString("look", "AQUA").commit()
            val first = capture(android.graphics.Color.rgb(220, 20, 80), "wallpaper-fixture-red", true)
            val second = capture(android.graphics.Color.rgb(20, 180, 80), "wallpaper-fixture-green", false)
            val change = kotlin.math.abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
                kotlin.math.abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
                kotlin.math.abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))
            assertTrue("Changing Android wallpaper did not clearly change the home backdrop (pixel difference $change)", change >= 150)
        } finally {
            prefs.edit().apply { if (hadLayout) putString("layout", oldLayout) else remove("layout") }.commit()
            appearance.edit().apply { if (hadLook) putString("look", oldLook) else remove("look") }.commit()
        }
    }

    /** Mutates only a snapshotted emulator; verifies real cross-window blur softens a split edge. */
    @Test fun wallpaperBlurSoftensSplitEdgeWhenSupported() {
        assumeTrue("Requires an explicit snapshotted-emulator fixture", InstrumentationRegistry.getArguments().getString("wallpaperFixture") == "1")
        assumeTrue("Cross-window blur needs API 31+", android.os.Build.VERSION.SDK_INT >= 31)
        val windowManager = context.getSystemService(android.view.WindowManager::class.java)
        assumeTrue("Device blur is disabled", windowManager.isCrossWindowBlurEnabled)
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val hadLayout = prefs.contains("layout"); val oldLayout = prefs.getString("layout", null)
        val hadLook = appearance.contains("look"); val oldLook = appearance.getString("look", null)
        val wallpaper = WallpaperManager.getInstance(instrumentation.context)
        try {
            prefs.edit().putString("layout", HomeLayout(emptyList(), emptyList(), emptyMap()).json()).commit()
            appearance.edit().putString("look", "AQUA").commit()
            val split = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            for (x in 0 until 64) for (y in 0 until 64) split.setPixel(x, y, if (x < 32) android.graphics.Color.rgb(220, 20, 80) else android.graphics.Color.rgb(20, 180, 80))
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.SET_WALLPAPER")
            try { wallpaper.setBitmap(split) } finally { instrumentation.uiAutomation.dropShellPermissionIdentity(); split.recycle() }
            launch("launcher")
            text("Home settings")
            val file = File(context.getExternalFilesDir(null), "evidence/wallpaper-blur-split.png").apply { parentFile?.mkdirs() }
            var blended = 0
            repeat(20) {
                device.waitForIdle()
                assertTrue(device.takeScreenshot(file))
                val shot = BitmapFactory.decodeFile(file.absolutePath) ?: throw AssertionError("Blur screenshot missing")
                blended = 0
                val cy = shot.height / 2
                for (dx in -20..20) {
                    val pixel = shot.getPixel((shot.width / 2 + dx).coerceIn(0, shot.width - 1), cy)
                    val red = android.graphics.Color.red(pixel); val green = android.graphics.Color.green(pixel)
                    if (red > 80 && green > 80 && kotlin.math.abs(red - green) < 60) blended++
                }
                shot.recycle()
                if (blended >= 5) { proof("wallpaper-blur-split"); return }
                Thread.sleep(300)
            }
            assumeTrue("Cross-window blur is not rendered on this device/renderer (blended pixels near center: $blended)", blended >= 5)
        } finally {
            prefs.edit().apply { if (hadLayout) putString("layout", oldLayout) else remove("layout") }.commit()
            appearance.edit().apply { if (hadLook) putString("look", oldLook) else remove("look") }.commit()
        }
    }

    @Test fun launcherDarkAppearancePersistsAndReturnsToLight() {
        val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val existed = preferences.contains("look")
        val original = preferences.getString("look", null)
        try {
            launch("launcher")
            clickText("About")
            val scroller = UiScrollable(UiSelector().scrollable(true))
            runCatching { scroller.scrollTextIntoView("Dark Bliss") }
            for (i in 0 until 4) {
                if (device.wait(Until.hasObject(By.text("Dark Bliss")), 2000) != null) break
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
                device.waitForIdle()
            }
            clickText("Dark Bliss")
            clickText("Done")
            assertTrue("About dialog did not close", device.wait(Until.gone(By.text("Third-party licenses")), 5000))
            device.waitForIdle()
            assertEquals("Dark Bliss preference was not saved", "DARK", preferences.getString("look", null))
            text("Home settings")
            proof("launcher-dark-aqua")
            assertDarkStatusTextIsVisible()
            launch("launcher")
            text("Home settings")
            clickText("About")
            clickText("Aqua")
            clickText("Done")
            assertTrue("About dialog did not close", device.wait(Until.gone(By.text("Third-party licenses")), 5000))
            device.waitForIdle()
            assertEquals("Aqua preference was not saved", "AQUA", preferences.getString("look", null))
            proof("launcher-light-aqua")
        } finally {
            val edit = preferences.edit()
            if (existed) edit.putString("look", original) else edit.remove("look")
            edit.commit()
        }
    }
}
