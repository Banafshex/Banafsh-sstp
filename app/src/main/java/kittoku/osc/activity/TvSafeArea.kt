package kittoku.osc.activity

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kittoku.osc.R

/** Keep controls inside the TV overscan area without insetting the full-bleed gradient. */
internal fun View.applyTvSafeArea() {
    val horizontal = resources.getDimensionPixelSize(R.dimen.tv_safe_horizontal)
    val vertical = resources.getDimensionPixelSize(R.dimen.tv_safe_vertical)
    setPadding(horizontal, vertical, horizontal, vertical)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(maxOf(horizontal, bars.left), maxOf(vertical, bars.top),
            maxOf(horizontal, bars.right), maxOf(vertical, bars.bottom))
        insets
    }
}
