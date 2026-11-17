package kittoku.osc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.sameInstance
import kittoku.osc.activity.AppPickerActivity
import kittoku.osc.activity.BlankActivity
import kittoku.osc.activity.MainActivity
import kittoku.osc.preference.OscPrefKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TvOnlyFixtureActivity : Activity()

@RunWith(AndroidJUnit4::class)
class TvSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun key(code: Int) {
        onView(isRoot()).perform(pressKey(code))
        instrumentation.waitForIdleSync()
    }

    @Test fun launchFromTvAndNavigateToSettingsWithRemote() {
        context.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_done", true).putBoolean("hide_welcome", true)
            .putString("last_seen_version", BuildConfig.VERSION_NAME).commit()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS
        )
        instrumentation.setInTouchMode(false)
        val launch = requireNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            var rtl = false
            scenario.onActivity {
                val config = it.resources.configuration
                assertEquals(Configuration.ORIENTATION_LANDSCAPE, config.orientation)
                assertEquals(Configuration.UI_MODE_NIGHT_YES, config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                rtl = config.layoutDirection == View.LAYOUT_DIRECTION_RTL
            }
            onView(withId(R.id.btnConnect)).check(matches(isFocused()))
            key(if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            onView(withId(R.id.cardServer)).check(matches(isFocused()))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withId(R.id.cardProtocol)).check(matches(isFocused()))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withId(R.id.cardSettings)).check(matches(isFocused()))
            val monitor = instrumentation.addMonitor(BlankActivity::class.java.name, null, false)
            try {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val settings = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000))
                onView(withText(R.string.tv_split_enable)).inRoot(withDecorView(sameInstance(settings.window.decorView)))
                    .check(matches(isDisplayed()))
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            pressBack()
            onView(withId(R.id.cardSettings)).check(matches(isFocused()))
        }
    }

    @Test fun tvOnlyAppCanBeSelectedAndDeselectedWithOk() {
        val pkg = instrumentation.context.packageName
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pref = OscPrefKey.ROUTE_SELECTED_APPS.name
        prefs.edit().remove(pref).commit()
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch(AppPickerActivity::class.java).use { scenario ->
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.etSearch).apply { setText(pkg); requestFocus() }
                val adapter = activity.findViewById<RecyclerView>(R.id.rvApps).adapter!!
                if (adapter.itemCount == 1) loaded.countDown() else adapter.registerAdapterDataObserver(
                    object : RecyclerView.AdapterDataObserver() {
                        override fun onChanged() { if (adapter.itemCount == 1) loaded.countDown() }
                    }
                )
            }
            assertTrue("TV-only package not found", loaded.await(10, TimeUnit.SECONDS))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withText("TV picker fixture")).check(matches(isFocused()))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            onView(withText("TV picker fixture")).check(matches(isChecked()))
            assertEquals(setOf(pkg), prefs.getStringSet(pref, emptySet()))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(prefs.getStringSet(pref, emptySet())!!.isEmpty())
        }
    }
}
