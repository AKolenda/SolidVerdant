/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.screenshots

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import dev.tricked.solidverdant.data.local.AppThemeMode
import dev.tricked.solidverdant.ui.navigation.LocalFloatingBarInset
import dev.tricked.solidverdant.ui.navigation.MainNavigationBar
import dev.tricked.solidverdant.ui.navigation.Screen
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.SolidVerdantTheme
import dev.tricked.solidverdant.ui.tracking.TimerTopBar
import java.io.File
import java.util.Locale

/**
 * Pure-JVM (Robolectric + Roborazzi) screenshot host.
 *
 * The whole style matrix is expressed as two axis enums plus [ScreenshotMatrix]; that object is the
 * single place to edit. To add a locale (nl/ja) or another device (foldable) later, append a new
 * entry to the relevant axis and, for locale, wrap [render]'s content in a locale override — no test
 * code changes required elsewhere.
 */
enum class ThemeAxis(val id: String, val mode: AppThemeMode) {
    /** The default light scheme. */
    LIGHT("light", AppThemeMode.LIGHT),

    /** The default dark scheme — the cohesive README hero style. */
    DARK("dark", AppThemeMode.DARK),
}

enum class DeviceAxis(val id: String, val widthDp: Int, val heightDp: Int) {
    PHONE("phone", 411, 891),
    TABLET("tablet", 800, 1280),
}

enum class LocaleAxis(val id: String, val locale: Locale) {
    ENGLISH("en", Locale.ENGLISH),
    JAPANESE("ja", Locale.JAPANESE),
}

/** The single list to edit. Cartesian product of these axes is rendered for every screen. */
object ScreenshotMatrix {
    val themes: List<ThemeAxis> = listOf(ThemeAxis.LIGHT, ThemeAxis.DARK)
    val devices: List<DeviceAxis> = listOf(DeviceAxis.PHONE, DeviceAxis.TABLET)
    val locales: List<LocaleAxis> = listOf(LocaleAxis.ENGLISH, LocaleAxis.JAPANESE)

    /** The cohesive README hero style: dark + phone. */
    val readmeTheme: ThemeAxis = ThemeAxis.DARK
    val readmeDevice: DeviceAxis = DeviceAxis.PHONE
}

object ScreenshotHost {

    /** Deterministic Pixel-style system chrome surrounding the app content. */
    @Composable
    private fun AndroidShell(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "09:41",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val glyph = MaterialTheme.colorScheme.onBackground
                    Canvas(
                        modifier = Modifier.size(16.dp),
                    ) {
                        val strokeWidth = 2.dp.toPx()
                        val dotRadius = 1.25.dp.toPx()
                        val inset = 1.dp.toPx()
                        val centerX = size.width / 2f
                        val bottom = size.height - 2.dp.toPx()
                        drawArc(
                            color = glyph,
                            startAngle = 225f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = glyph,
                            startAngle = 225f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(inset + 3.dp.toPx(), inset + 3.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(
                                size.width - (inset + 3.dp.toPx()) * 2,
                                size.height - (inset + 3.dp.toPx()) * 2,
                            ),
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )
                        drawCircle(glyph, dotRadius, Offset(centerX, bottom))
                    }
                    Text(
                        text = "87%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            Box(
                modifier = Modifier.fillMaxWidth().height(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.28f)
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.onBackground),
                )
            }
        }
    }

    /**
     * Hosts feature content inside the production floating tab bar. Tab roots draw their own
     * headers (the Timer tab uses the real [TimerTopBar]); pushed destinations get a back-arrow
     * title bar from [titleRes].
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppShell(tab: Screen, titleRes: Int? = null, content: @Composable () -> Unit) {
        val barInset = Dimens.TabBarHeight + Dimens.TabBarBottomGap
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalFloatingBarInset provides barInset) {
                Column(Modifier.fillMaxSize()) {
                    when {
                        titleRes != null -> TopAppBar(
                            title = { Text(stringResource(titleRes)) },
                            navigationIcon = {
                                IconButton(onClick = {}) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                                }
                            },
                        )
                        tab == Screen.Track -> TimerTopBar(
                            organizationName = "Acme Studio",
                            canSwitchOrganization = true,
                            memberships = emptyList(),
                            currentMembershipId = null,
                            onMembershipChange = {},
                            onOpenCalendar = {},
                            onAddEntry = {},
                            reviewBadgeCount = 4,
                            onOpenReview = {},
                            syncing = false,
                            onRefresh = {},
                            onRequestNotifications = null,
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = if (titleRes != null) barInset else 0.dp),
                    ) { content() }
                }
            }
            MainNavigationBar(
                selectedRoute = tab.route,
                onNavigate = {},
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.TabBarBottomGap),
            )
        }
    }

    /** Repository root (the folder that owns settings.gradle.kts), regardless of Gradle's cwd. */
    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            val parent = dir.parentFile ?: break
            dir = parent
        }
        dir
    }

    /** Absolute path under the repo root, e.g. outputPath(".github", "screenshots", "readme", "track.png"). */
    fun outputPath(vararg parts: String): String = File(repoRoot, parts.joinToString(File.separator)).absolutePath

    /**
     * Render [content] wrapped in the app theme at the given device size and capture it to
     * [filePath]. Each call spins up (and tears down) its own composition, so it is safe to call
     * many times inside a single test method.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    fun capture(
        theme: ThemeAxis,
        device: DeviceAxis,
        filePath: String,
        locale: LocaleAxis = LocaleAxis.ENGLISH,
        content: @Composable () -> Unit,
    ) {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocale(locale.locale)
        }
        val localizedContext = baseContext.createConfigurationContext(configuration)
        val previousLocale = Locale.getDefault()
        Locale.setDefault(locale.locale)
        try {
            captureRoboImage(
                filePath = filePath,
                roborazziComposeOptions = RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                },
            ) {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides configuration,
                ) {
                    SolidVerdantTheme(themeMode = theme.mode) {
                        AndroidShell(content)
                    }
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
