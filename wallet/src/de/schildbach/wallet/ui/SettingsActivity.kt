/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.content.Intent
import android.os.Bundle
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.preference.PreferenceActivity
import de.schildbach.wallet.ui.preference.SettingsFragment
import kotlinx.android.synthetic.main.activity_settings.*
import org.pepepow.wallet.R

class SettingsActivity : BaseMenuActivity() {

    override fun getLayoutId(): Int {
        return R.layout.activity_settings
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.settings_title)
        about.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        developer_options.setOnClickListener {
            val intent = Intent(this, PreferenceActivity::class.java)
            intent.putExtra(android.preference.PreferenceActivity.EXTRA_SHOW_FRAGMENT,
                SettingsFragment::class.java.name)
            intent.putExtra(android.preference.PreferenceActivity.EXTRA_SHOW_FRAGMENT_TITLE,
                getString(R.string.menu_developer_options))
            startActivity(intent)
        }
    }
}
