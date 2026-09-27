/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.theme

import android.os.Build
import androidx.annotation.StringRes
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.local.AppThemeMode

@get:StringRes
val AppThemeMode.labelRes: Int
    get() = when (this) {
        AppThemeMode.SYSTEM -> R.string.theme_system
        AppThemeMode.LIGHT -> R.string.theme_light
        AppThemeMode.DARK -> R.string.theme_dark
        AppThemeMode.VERDANT -> R.string.theme_verdant
        AppThemeMode.NEO -> R.string.theme_neo
        AppThemeMode.DYNAMIC -> R.string.theme_dynamic
    }

/** Theme choices offered to the user; wallpaper colours need Android 12. */
fun selectableThemeModes(sdkInt: Int = Build.VERSION.SDK_INT): List<AppThemeMode> =
    AppThemeMode.entries.filter { it != AppThemeMode.DYNAMIC || sdkInt >= Build.VERSION_CODES.S }
