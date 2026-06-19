package messina.ui.scan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.sensors.SensorEvents
import messina.ui.BackButton
import kotlinx.coroutines.flow.MutableStateFlow
import messina.app.generated.resources.Res
import messina.app.generated.resources.sensor
import org.jetbrains.compose.resources.painterResource


@Composable
expect fun NfcScan(errorMessage: MutableStateFlow<String?>, onClose: () -> Unit)

@Composable
fun NfcScreen(onBack: () -> Unit) {
    val errorMessageFlow = remember { MutableStateFlow<String?>(null) }
    val errorMessage by errorMessageFlow.collectAsState()

    val popped = remember { mutableStateOf(false) }
    val goBack = remember {
        {
            if (!popped.value) {
                popped.value = true
                onBack()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { popped.value = true }
    }

    // Leave the screen once the sensor is successfully added
    LaunchedEffect(Unit) {
        SensorEvents.sensorAdded.collect { goBack() }
    }

    NfcScan(errorMessageFlow, goBack)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            BackButton("Back", onClick = goBack)
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(Res.drawable.sensor),
                    contentDescription = null,
                    modifier = Modifier.size(80.dp).padding(bottom = 24.dp),
                    colorFilter = ColorFilter.tint(
                        if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                )
                Text(
                    text = errorMessage ?: "Hold the back of your phone over the sensor",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }
    }
}
