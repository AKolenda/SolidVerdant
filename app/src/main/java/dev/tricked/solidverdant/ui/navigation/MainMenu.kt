/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.ui.theme.Dimens

/** Opens the side menu from a destination's ☰ button. */
@Immutable
class MainMenuController(val open: () -> Unit)

/** The side menu for the current destination; null where there is none (previews, component tests). */
val LocalMainMenu = compositionLocalOf<MainMenuController?> { null }

/** Test tag of the organization switcher in the side-menu header. */
const val MAIN_MENU_ORGANIZATION_TAG: String = "main_menu_organization"

/** The ☰ button of a menu destination; empty without a menu (previews, component tests). */
@Composable
fun MainMenuButton() {
    val menu = LocalMainMenu.current ?: return
    IconButton(onClick = menu.open, modifier = Modifier.testTag(MAIN_MENU_BUTTON_TAG)) {
        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.main_menu_open))
    }
}

/** Header of a menu destination: ☰, the destination title, then [actions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = { MainMenuButton() },
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** The side menu: [header], then one item per destination with Settings set apart. */
@Composable
fun MainMenuSheet(
    selectedRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    ModalDrawerSheet(modifier = modifier, drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = Dimens.Space12)) {
            header()
            MenuDivider()
            menuScreens.forEach { screen ->
                if (screen == Screen.Settings) MenuDivider()
                NavigationDrawerItem(
                    label = { Text(stringResource(screen.labelRes)) },
                    icon = { Icon(screen.icon, contentDescription = null) },
                    selected = screen.route == selectedRoute,
                    onClick = { onNavigate(screen) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag(mainNavTag(screen.route)),
                )
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = Dimens.Space8),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * Side-menu header: the app name, the signed-in account, and the organization with a switcher when
 * the account belongs to more than one and switching is allowed.
 */
@Composable
fun MainMenuHeader(
    userName: String?,
    userEmail: String?,
    organizationName: String?,
    memberships: List<Membership>,
    currentMembershipId: String?,
    canSwitchOrganization: Boolean,
    onMembershipChange: (Membership) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Space16 + Dimens.Space12),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
            Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (userName != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                Box(
                    modifier = Modifier.size(Dimens.AvatarSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initialsOf(userName),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!userEmail.isNullOrBlank()) {
                        Text(
                            text = userEmail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (organizationName != null) {
            OrganizationSwitcher(
                organizationName = organizationName,
                memberships = memberships,
                currentMembershipId = currentMembershipId,
                canSwitch = canSwitchOrganization,
                onMembershipChange = onMembershipChange,
            )
        }
    }
}

@Composable
private fun OrganizationSwitcher(
    organizationName: String,
    memberships: List<Membership>,
    currentMembershipId: String?,
    canSwitch: Boolean,
    onMembershipChange: (Membership) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val switchDescription = stringResource(R.string.switch_organization)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .testTag(MAIN_MENU_ORGANIZATION_TAG)
                .then(
                    if (canSwitch) {
                        Modifier
                            .clickable { expanded = true }
                            .semantics {
                                role = Role.Button
                                contentDescription = switchDescription
                            }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = organizationName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canSwitch) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            memberships.forEach { membership ->
                DropdownMenuItem(
                    text = { Text(membership.organization.name) },
                    onClick = {
                        expanded = false
                        onMembershipChange(membership)
                    },
                    trailingIcon = if (membership.id == currentMembershipId) {
                        { Icon(imageVector = Icons.Default.Done, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Up to two initials for the avatar, e.g. "Ada Lovelace" -> "AL". */
internal fun initialsOf(name: String): String = name.trim()
    .split(Regex("\\s+"))
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { "?" }
