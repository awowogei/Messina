package messina.settings

actual fun getApplicationPath(): String {
    val fm = platform.Foundation.NSFileManager.defaultManager
    val urls = fm.URLsForDirectory(
        platform.Foundation.NSApplicationSupportDirectory,
        platform.Foundation.NSUserDomainMask
    )
    return (urls.last() as platform.Foundation.NSURL).path + "/messina"
}