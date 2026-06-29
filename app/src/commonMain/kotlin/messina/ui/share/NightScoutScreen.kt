package messina.ui.share

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.share.NightScout
import messina.ui.BackButton
import messina.ui.SwitchRow
import messina.ui.TextInput
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Composable
fun NightScoutScreen(onBack: () -> Unit) {
    DisposableEffect(Unit) {
        onDispose { NightScout.status = null }
    }
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Nightscout",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton("Share", onClick = onBack)
                if (NightScout.connected) {
                    TextButton(onClick = { NightScout.disconnect() }) {
                        Text(
                            "Disconnect",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Spacer(Modifier)
                }
            }
        }

        if (NightScout.connected) ConnectedBody() else ConnectBody()
    }
}

@Composable
private fun ConnectedBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        SwitchRow(
            label = "Sync data",
            checked = NightScout.syncEnabled,
            onCheckedChange = { NightScout.syncEnabled = it },
        )
    }
}

@Composable
private fun ConnectBody() {
    Text(
        text = "URL",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, start = 16.dp, bottom = 6.dp)
    )
    TextInput(
        value = NightScout.url,
        onValueChange = { NightScout.url = it.trim() },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )

    Text(
        text = "API secret",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, start = 16.dp, bottom = 6.dp)
    )
    TextInput(
        value = NightScout.secret,
        onValueChange = { NightScout.secret = it },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )

    val connecting = NightScout.connectJob?.isActive == true
    Button(
        onClick = {
            if (connecting) return@Button
            NightScout.status = null
            NightScout.connectJob = GlobalScope.launch {
                try {
                    NightScout.connect()
                } catch (e: Throwable) {
                    NightScout.status = e.message ?: "Connection failed"
                } finally {
                    NightScout.connectJob = null
                }
            }
        },
        enabled = !connecting,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 16.dp)
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(if (connecting) "Connecting…" else "Connect", fontSize = 17.sp)
    }

    NightScout.status?.let {
        Text(
            text = it,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
    }
}
