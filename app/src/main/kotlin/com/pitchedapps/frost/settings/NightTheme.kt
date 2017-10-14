package com.pitchedapps.frost.settings

import ca.allanwang.kau.kpref.activity.KPrefAdapterBuilder
import ca.allanwang.kau.kpref.activity.items.KPrefItemBase
import ca.allanwang.kau.utils.string
import com.pitchedapps.frost.R
import com.pitchedapps.frost.activities.SettingsActivity
import com.pitchedapps.frost.enums.Theme
import com.pitchedapps.frost.utils.Prefs
import com.pitchedapps.frost.utils.frostAnswersCustom
import com.pitchedapps.frost.utils.materialDialogThemed
import com.pitchedapps.frost.utils.setFrostTheme

/**
 * Created by Allan Wang on 2017-10-13.
 */
fun SettingsActivity.getNightThemePrefs(): KPrefAdapterBuilder.() -> Unit = {

    checkbox(R.string.enable_night_theme, { Prefs.enableNightTheme }, { Prefs.enableNightTheme = it }) {
        descRes = R.string.enable_night_theme_desc
    }

    fun KPrefItemBase.BaseContract<*>.dependsOnNight() {
        enabler = { Prefs.enableNightTheme }
    }

    timePicker(R.string.night_theme_start, { Prefs.nightThemeStart }, { Prefs.nightThemeStart = it }) {
        dependsOnNight()
    }

    timePicker(R.string.night_theme_end, { Prefs.nightThemeEnd }, { Prefs.nightThemeEnd = it }) {
        dependsOnNight()
    }

    text(R.string.theme, { Prefs.nightTheme }, { Prefs.nightTheme = it }) {
        dependsOnNight()
        onClick = { _, _, item ->
            val themes = arrayOf(Theme.DARK, Theme.AMOLED, Theme.GLASS)
            materialDialogThemed {
                title(R.string.theme)
                items(themes.map { string(it.textRes) })
                itemsCallbackSingleChoice(themes.indexOf(Theme(item.pref))) { _, _, which, _ ->
                    val index = themes[which].ordinal
                    if (item.pref == index) return@itemsCallbackSingleChoice true
                    item.pref = index
                    shouldRestartMain()
                    reload()
                    setFrostTheme(true)
                    themeExterior()
                    invalidateOptionsMenu()
                    frostAnswersCustom("NightTheme", "Count" to Theme(index).name)
                    true
                }
            }
            true
        }
        textGetter = {
            string(Theme(it).textRes)
        }
    }
}