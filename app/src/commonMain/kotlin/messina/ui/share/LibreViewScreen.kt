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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.backup.LibreView
import messina.ui.BackButton
import messina.ui.SwitchRow
import messina.ui.TextInput
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Composable
fun LibreViewScreen(onBack: () -> Unit) {
    DisposableEffect(Unit) {
        onDispose { LibreView.status = null }
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
                "LibreView",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton("Share", onClick = onBack)
                if (LibreView.loggedIn) {
                    TextButton(onClick = { LibreView.logout() }) {
                        Text("Log out", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(Modifier)
                }
            }
        }

        if (LibreView.loggedIn) LoggedInBody() else LoginBody()
    }
}

@Composable
private fun LoggedInBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        SwitchRow(
            label = "Sync data",
            checked = LibreView.syncEnabled,
            onCheckedChange = { LibreView.syncEnabled = it },
        )
    }
}

@Composable
private fun LoginBody() {
    val loggingIn = LibreView.loginJob?.isActive == true
    var emailMissing by remember { mutableStateOf(false) }
    var passwordMissing by remember { mutableStateOf(false) }

    Text(
        text = "Email",
        fontSize = 13.sp,
        color = if (emailMissing) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, start = 16.dp, bottom = 6.dp)
    )
    TextInput(
        value = LibreView.email,
        onValueChange = {
            LibreView.email = it
            if (it.isNotEmpty()) emailMissing = false
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    Text(
        text = "Password",
        fontSize = 13.sp,
        color = if (passwordMissing) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, start = 16.dp, bottom = 6.dp)
    )
    TextInput(
        value = LibreView.password,
        onValueChange = {
            LibreView.password = it
            if (it.isNotEmpty()) passwordMissing = false
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )

    Button(
        onClick = {
            if (loggingIn) return@Button
            emailMissing = LibreView.email.isEmpty()
            passwordMissing = LibreView.password.isEmpty()
            if (emailMissing || passwordMissing) return@Button
            LibreView.status = null
            LibreView.loginJob = GlobalScope.launch {
                try {
                    LibreView.login()
                } catch (e: Throwable) {
                    LibreView.status = e.message ?: "Login failed"
                } finally {
                    LibreView.loginJob = null
                }
            }
        },
        enabled = !loggingIn,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 16.dp)
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(if (loggingIn) "Logging in…" else "Login", fontSize = 17.sp)
    }

    LibreView.status?.let {
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
