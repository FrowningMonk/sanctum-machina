package app.sanctum.machina.crash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sanctum.machina.R

/**
 * Stateless restart-crash banner rendered on `ModelManagerScreen` when the
 * previous run crashed and the user has not hidden the banner yet
 * (Flow B / US-B).
 *
 * The hosting screen owns all state: whether the banner is visible
 * (`CrashState.hasUnresolvedCrash`), whether a save is in-flight
 * ([launching]), and the SAF launcher. The banner is a pure view —
 * no `remember`, no side effects — so its behaviour is entirely driven
 * by the caller.
 *
 * [launching] only disables the "save log" [TextButton]. The dismiss
 * [IconButton] stays clickable: it is a separate action (mark-dismissed
 * via `CrashState`), not a second SAF launch, so it does not conflict
 * with the in-flight save.
 */
@Composable
fun RestartCrashBanner(
    launching: Boolean,
    onSaveClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.crash_banner_body),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onSaveClick,
                enabled = !launching,
            ) {
                Text(stringResource(R.string.log_export_save_button))
            }
            IconButton(onClick = onDismissClick) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.crash_banner_dismiss_description),
                )
            }
        }
    }
}
