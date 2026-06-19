package messina.settings

import messina.ContextStore.Companion.ApplicationContext

actual fun getApplicationPath(): String {
    return ApplicationContext.filesDir.absolutePath
}
