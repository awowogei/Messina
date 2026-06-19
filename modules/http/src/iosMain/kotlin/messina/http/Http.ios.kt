package messina.http

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import kotlin.coroutines.*

actual object Http {
    actual suspend fun post(
        url: String,
        data: Map<String, Any>?,
        headers: Map<String, String>?,
        timeout: Int
    ): Response = request("POST", url, data, headers, timeout)

    actual suspend fun get(
        url: String,
        headers: Map<String, String>?,
        timeout: Int
    ): Response = request("GET", url, null, headers, timeout)

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun request(
        method: String,
        url: String,
        data: Map<String, Any>?,
        headers: Map<String, String>?,
        timeout: Int
    ): Response = suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)!!
        val nsRequest = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setAllowsCellularAccess(true)
            setTimeoutInterval(timeout.toFloat() / 1000.0)
            HTTPMethod = method
            headers?.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
            data?.let {
                if (valueForHTTPHeaderField("Content-Type") == null) {
                    setValue("application/json", forHTTPHeaderField = "Content-Type")
                }
                HTTPBody = NSJSONSerialization.dataWithJSONObject(
                    obj = it,
                    options = 0u,
                    error = null
                )
            }
        }
        NSURLSession.sharedSession.dataTaskWithRequest(nsRequest) { body, response, error ->
            if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription))
            else cont.resume(
                Response(
                    code = (response as NSHTTPURLResponse).statusCode.toInt(),
                    body = body?.let { NSString.create(it, NSUTF8StringEncoding).toString() }
                ))
        }.resume()
    }
}
