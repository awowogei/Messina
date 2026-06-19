package messina.http

import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection

actual object Http {
    actual suspend fun post(
        url: String,
        data: Map<String, Any>?,
        headers: Map<String, String>?,
        timeout: Int
    ): Response = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeout
            readTimeout = timeout
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            data?.let {
                doOutput = true
                if (getRequestProperty("Content-Type") == null) {
                    setRequestProperty("Content-Type", "application/json")
                }
                val json = JSONObject(it).toString()
                outputStream.writer().use { writer -> writer.write(json) }
            }
        }
        readResponse(conn)
    }

    actual suspend fun get(
        url: String,
        headers: Map<String, String>?,
        timeout: Int
    ): Response = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): Response = Response(
        code = conn.responseCode,
        body = if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
    )
}
