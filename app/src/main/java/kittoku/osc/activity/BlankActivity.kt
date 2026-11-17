package kittoku.osc.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import kittoku.osc.R
import kittoku.osc.databinding.ActivityBlankBinding
import kittoku.osc.fragment.ProfilesFragment
import kittoku.osc.fragment.SaveCertFragment
import kittoku.osc.fragment.SettingFragment

internal const val BLANK_ACTIVITY_TYPE_PROFILES = 0
internal const val BLANK_ACTIVITY_TYPE_SAVE_CERT = 2
internal const val BLANK_ACTIVITY_TYPE_SETTINGS = 3

internal const val EXTRA_KEY_TYPE = "TYPE"
internal const val EXTRA_KEY_CERT = "CERT"
internal const val EXTRA_KEY_FILENAME = "FILENAME"

class BlankActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(kittoku.osc.LocaleFix.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fragment: Fragment

        when (intent.extras!!.getInt(EXTRA_KEY_TYPE)) {
            BLANK_ACTIVITY_TYPE_PROFILES -> {
                title = getString(R.string.bs_title_profiles)
                fragment = ProfilesFragment()
            }

            BLANK_ACTIVITY_TYPE_SAVE_CERT -> {
                fragment = SaveCertFragment()
            }

            BLANK_ACTIVITY_TYPE_SETTINGS -> {
                title = getString(R.string.bs_menu_settings)
                fragment = SettingFragment()
            }

            else -> throw NotImplementedError(intent.toString())
        }

        val binding = ActivityBlankBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTvSafeArea()
        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.blank, fragment).commit()
        }
    }
}
