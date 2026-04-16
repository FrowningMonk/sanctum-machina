package app.sanctum.machina.ui.chat

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafeUriHandlerTest {

    private lateinit var application: Application
    private lateinit var handler: SafeUriHandler

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        handler = SafeUriHandler(application)
        shadowOf(application).clearNextStartedActivities()
    }

    @Test
    fun http_allowed() {
        handler.openUri("http://example.com")

        val intent = shadowOf(application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("http://example.com", intent.data.toString())
    }

    @Test
    fun https_allowed() {
        handler.openUri("https://example.com/path?q=1")

        val intent = shadowOf(application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://example.com/path?q=1", intent.data.toString())
    }

    @Test
    fun intent_blocked() {
        handler.openUri("intent://anything")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun sms_blocked() {
        handler.openUri("sms:+1234567890")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun tel_blocked() {
        handler.openUri("tel:911")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun javascript_blocked() {
        handler.openUri("javascript:alert(1)")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun file_blocked() {
        handler.openUri("file:///etc/passwd")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun content_blocked() {
        handler.openUri("content://foo/bar")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun data_blocked() {
        handler.openUri("data:text/html,<script>alert(1)</script>")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun market_blocked() {
        handler.openUri("market://details?id=com.example")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun malformed_blocked() {
        handler.openUri("not a uri at all")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun empty_blocked() {
        handler.openUri("")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun http_uppercase_allowed() {
        handler.openUri("HTTP://Example.COM")

        val intent = shadowOf(application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("HTTP://Example.COM", intent.data.toString())
    }

    @Test
    fun https_mixedcase_allowed() {
        handler.openUri("HttpS://example.com")

        val intent = shadowOf(application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
    }
}
