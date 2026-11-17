package kittoku.osc.preference

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue

/** One immutable selection per connection, shared by both VPN engines. */
internal data class AppRouting(val mode: Mode, val packages: Set<String> = emptySet()) {
    enum class Mode { ALL, ONLY_SELECTED, BYPASS_SELECTED }

    companion object {
        fun read(prefs: SharedPreferences): AppRouting {
            if (!getBooleanPrefValue(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs)) return AppRouting(Mode.ALL)
            val mode = when (getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, prefs)) {
                LIST_TYPE_ALLOWED -> Mode.ONLY_SELECTED
                LIST_TYPE_DISALLOWED -> Mode.BYPASS_SELECTED
                else -> return AppRouting(Mode.ALL)
            }
            return AppRouting(mode, getSetPrefValue(OscPrefKey.ROUTE_SELECTED_APPS, prefs).toSet())
        }
    }

    /** Android interprets zero allowed apps as *all* apps. Never establish that tunnel in only-selected mode. */
    fun applyPackages(allow: (String) -> Boolean, bypass: (String) -> Boolean): Boolean {
        if (mode == Mode.ALL) return true
        val installed = packages.count(if (mode == Mode.ONLY_SELECTED) allow else bypass)
        return mode != Mode.ONLY_SELECTED || installed > 0
    }

    fun applyTo(builder: VpnService.Builder): Boolean = applyPackages(
        { pkg -> ifInstalled { builder.addAllowedApplication(pkg) } },
        { pkg -> ifInstalled { builder.addDisallowedApplication(pkg) } }
    )
}

// A selected app may have been uninstalled since the picker was opened.
internal inline fun ifInstalled(action: () -> Unit): Boolean = try {
    action()
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
