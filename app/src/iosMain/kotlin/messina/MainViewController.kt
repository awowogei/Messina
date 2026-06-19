package messina

import androidx.compose.ui.window.ComposeUIViewController
import com.juul.kable.CentralManager

fun MainViewController() = ComposeUIViewController { App() }

fun applicationDidLaunch() {
    CentralManager.configure { stateRestoration = true }
    GlobalState.initialize()
}
