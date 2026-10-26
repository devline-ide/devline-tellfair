package devline.tellfair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Bounded lazy account reads. RPC never waits on the network or returns credentials. */
internal class TellfairApi(private val http: HttpClient, private val endpoint: URI) : AutoCloseable {
    private class Read { @Volatile var data: JsonObject? = null; @Volatile var error: String? = null; var task: Future<*>? = null; val created = System.currentTimeMillis() }
    private val reads = linkedMapOf<String, Read>()
    private val worker = Executors.newFixedThreadPool(2) { Thread(it, "devline-tellfair-account").apply { isDaemon = true } }
    @Synchronized fun read(method: String, params: JsonObject, token: String): JsonObject {
        if (method != "presets" && token.isEmpty()) throw IdeExtensionException("TELLFAIR_KEY", "TELLFAIR_KEY")
        val limit = params["limit"]?.asInt ?: if (method == "usage") 50 else 20
        require(limit in 1..100)
        val path = when (method) {
            "presets" -> "/api/v1/presets"
            "voices" -> "/api/v1/voices"
            "balance" -> "/api/v1/balance"
            "usage" -> "/api/v1/usage?limit=$limit"
            "remoteJobs" -> {
                val kind = params["kind"]?.asString.orEmpty()
                require(kind in setOf("", "tts", "audiobook", "dialog", "video"))
                "/api/v1/jobs?limit=$limit" + if (kind.isEmpty()) "" else "&kind=$kind"
            }
            "remoteJob" -> { val id = params["id"]?.asString.orEmpty(); require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))); "/api/v1/jobs/$id" }
            else -> error("Unknown account read.")
        }
        val old = reads[path]
        val cacheMillis = if (method == "remoteJob") 1000L else 30000L
        if (old != null && old.task?.isDone != false && System.currentTimeMillis() - old.created > cacheMillis) reads.remove(path)
        val read = reads[path] ?: Read().also { next ->
            if (reads.size >= 8) reads.entries.firstOrNull { it.value.task?.isDone != false }?.let { reads.remove(it.key) }
            if (reads.size >= 8) throw IdeExtensionException("TELLFAIR_BUSY", "TELLFAIR_BUSY")
            reads[path] = next
            next.task = worker.submit {
                try {
                    val request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(15)).GET().apply {
                        if (method != "presets") header("Authorization", "Bearer $token")
                    }.build()
                    val pending = http.sendAsync(request, HttpResponse.BodyHandler { BoundedBody() })
                    val response = try { pending.get(16, java.util.concurrent.TimeUnit.SECONDS) } finally { if (!pending.isDone) pending.cancel(true) }
                    val bytes = response.body()
                    if (response.statusCode() !in 200..299) throw IllegalStateException(errorCode(response.statusCode(), bytes))
                    require(bytes.size <= 256 * 1024) { "TELLFAIR_RESPONSE" }
                    val safe = filter(method, JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
                    require(safe.toString().length <= 60000) { "TELLFAIR_RESPONSE" }
                    next.data = safe
                } catch (cause: Exception) { next.error = generateSequence<Throwable>(cause) { it.cause }.mapNotNull { it.message }.firstOrNull { it.matches(Regex("TELLFAIR_[A-Z_]+")) } ?: "TELLFAIR_NETWORK" }
            }
        }
        read.error?.let { reads.remove(path); throw IdeExtensionException(it, it) }
        return read.data?.deepCopy()?.apply { addProperty("state", "ready") } ?: JsonObject().apply { addProperty("state", "loading") }
    }
    private fun filter(method: String, value: JsonObject): JsonObject {
        fun pick(source: JsonObject, names: Set<String>) = JsonObject().apply { for (name in names) source[name]?.let { add(name, it.deepCopy()) } }
        val jobKeys = setOf("id", "status", "kind", "title", "ahead", "created_at", "createdAt", "finished_at", "chars", "charsCharged", "error", "code")
        fun items(name: String, fields: Set<String>) = com.google.gson.JsonArray().apply { value[name]?.takeIf { it.isJsonArray }?.asJsonArray?.take(100)?.forEach { if (it.isJsonObject) add(pick(it.asJsonObject, fields)) } }
        return when (method) {
            "presets" -> pick(value, setOf("presets", "voices", "piperVoices"))
            "balance" -> JsonObject().apply { value["plan"]?.takeIf { it.isJsonObject }?.let { add("plan", pick(it.asJsonObject, setOf("id", "name", "until"))) }; value["chars"]?.takeIf { it.isJsonObject }?.let { add("chars", pick(it.asJsonObject, setOf("plan", "pack", "total"))) } }
            "voices" -> JsonObject().apply { add("voices", items("voices", setOf("id", "name", "created_at"))) }
            "usage" -> JsonObject().apply { add("items", items("items", setOf("delta", "reason", "ref", "at"))) }
            "remoteJobs" -> JsonObject().apply { add("jobs", items("jobs", jobKeys)) }
            "remoteJob" -> JsonObject().apply { value["job"]?.takeIf { it.isJsonObject }?.let { add("job", pick(it.asJsonObject, jobKeys)) } }
            else -> JsonObject()
        }
    }
    @Synchronized fun clear() { reads.values.forEach { it.task?.cancel(true) }; reads.clear() }
    override fun close() { clear(); worker.shutdownNow() }
    private class BoundedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val result = java.util.concurrent.CompletableFuture<ByteArray>()
        private val output = java.io.ByteArrayOutputStream()
        private var subscription: java.util.concurrent.Flow.Subscription? = null
        override fun getBody(): java.util.concurrent.CompletionStage<ByteArray> = result
        override fun onSubscribe(value: java.util.concurrent.Flow.Subscription) { subscription = value; value.request(1) }
        override fun onNext(buffers: List<java.nio.ByteBuffer>) {
            for (buffer in buffers) {
                if (output.size().toLong() + buffer.remaining() > 256 * 1024) { subscription?.cancel(); result.completeExceptionally(IllegalStateException("TELLFAIR_RESPONSE")); return }
                val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); output.write(bytes)
            }
            subscription?.request(1)
        }
        override fun onError(cause: Throwable) { result.completeExceptionally(cause) }
        override fun onComplete() { result.complete(output.toByteArray()) }
    }
    companion object {
        fun errorCode(status: Int, body: ByteArray = byteArrayOf()): String {
            val code = runCatching { JsonParser.parseString(body.take(4096).toByteArray().toString(Charsets.UTF_8)).asJsonObject["code"]?.asString }.getOrNull()
            return when { status == 401 -> "TELLFAIR_KEY"; status == 402 && code == "plan_upgrade" -> "TELLFAIR_PLAN"; status == 402 -> "TELLFAIR_BALANCE"; status == 404 -> "TELLFAIR_NOT_FOUND"; status == 429 -> "TELLFAIR_RATE"; else -> "TELLFAIR_SERVER" }
        }
    }
}
