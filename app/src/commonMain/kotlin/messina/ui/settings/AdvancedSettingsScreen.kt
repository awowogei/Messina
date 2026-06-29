package messina.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import messina.share.LibreView
import messina.ui.BackButton
import messina.ui.SwitchRow

@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            BackButton("Settings", onClick = onBack)
        }

        SwitchRow(
            label = "Require LibreView account",
            checked = LibreView.requireAccount,
            onCheckedChange = { LibreView.requireAccount = it },
            modifier = Modifier
                .padding(top = 24.dp)
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}
