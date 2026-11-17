package kittoku.osc.preference

import org.junit.Assert.*
import org.junit.Test

class AppRoutingTest {
    @Test fun disabledRulesNeverTouchTheBuilder() {
        val rule = AppRouting(AppRouting.Mode.ALL, setOf("old.selection"))
        assertTrue(rule.applyPackages({ error("unexpected allow") }, { error("unexpected bypass") }))
    }

    @Test fun bypassRegistersOnlyExplicitSelectionsAndSkipsUninstalledApps() {
        val picks = setOf("tv.player", "removed.app")
        val attempted = mutableSetOf<String>()
        assertTrue(AppRouting(AppRouting.Mode.BYPASS_SELECTED, picks).applyPackages(
            { error("unexpected allow") }, { attempted.add(it); it != "removed.app" }
        ))
        assertEquals(picks, attempted)
        assertTrue(AppRouting(AppRouting.Mode.BYPASS_SELECTED).applyPackages({ false }, { false }))
    }

    @Test fun onlySelectedRegistersEveryPickEvenAfterTheFirstSuccess() {
        val picks = setOf("first.app", "second.app", "removed.app")
        val attempted = mutableSetOf<String>()
        assertTrue(AppRouting(AppRouting.Mode.ONLY_SELECTED, picks).applyPackages(
            { attempted.add(it); it != "removed.app" }, { error("unexpected bypass") }
        ))
        assertEquals(picks, attempted)
    }

    @Test fun emptyOrUninstalledAllowlistCannotBecomeAFullDeviceTunnel() {
        for (picks in listOf(emptySet(), setOf("removed.app"))) {
            assertFalse(AppRouting(AppRouting.Mode.ONLY_SELECTED, picks).applyPackages({ false }, { false }))
        }
    }
}
