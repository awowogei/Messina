package messina.http

data class Response(
    val code: Int,
    val body: String?
)

// Simple http to avoid dependencies
expect object Http {
    suspend fun post(
        url: String,
        data: Any? = null,
        headers: Map<String, String>? = null,
        timeout: Int = 10_000
    ): Response

    suspend fun get(
        url: String,
        headers: Map<String, String>? = null,
        timeout: Int = 10_000
    ): Response
}
