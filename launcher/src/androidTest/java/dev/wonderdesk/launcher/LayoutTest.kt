package dev.wonderdesk.launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayoutTest {
    @Test fun launcherUsesOwnerBrandIconResource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(R.drawable.wonderdesk_icon, context.applicationInfo.icon)
        assertNotNull(context.packageManager.getApplicationIcon(context.packageName))
    }
    @Test fun layoutRoundTripAndValidation() {
        val value = HomeLayout(listOf("dev.wonderdesk.melody/.MainActivity"), listOf("dev.wonderdesk.wayfinder/.MainActivity"), mapOf("Media" to listOf("dev.wonderdesk.cinema/.MainActivity")), 4, "blue")
        assertEquals(value, HomeLayout.parse(value.json()))
        try { HomeLayout.parse(value.json().replace("\"columns\": 4", "\"columns\": 99")); fail("Invalid grid accepted") } catch (_: IllegalArgumentException) { }
        try { HomeLayout.parse(value.json().replace("\"schema\": 1", "\"schema\": 9")); fail("Invalid schema accepted") } catch (_: IllegalArgumentException) { }
    }
}
