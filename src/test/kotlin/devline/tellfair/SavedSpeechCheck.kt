package devline.tellfair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import devline.extensions.IdeExtensionContext
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Real saved documents and actual HTTP/audio bytes, using an owned local provider without billing. */
fun main() {
    val root = Files.createTempDirectory("tellfair-saved-")
    val project = Files.createDirectory(root.resolve("project"))
    val calls = AtomicInteger(); val responseCode = AtomicInteger(200)
    val texts = mutableListOf<String>()
    val audio = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(44); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(8); repeat(4) { putShort((it * 100).toShort()) }
    }.array()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v1/presets") { exchange ->
        val data = """{"presets":[],"voices":[{"id":"knight-en","lang":"en"},{"id":"mage-en","lang":"en"}]}""".toByteArray()
        exchange.sendResponseHeaders(200, data.size.toLong()); exchange.responseBody.use { it.write(data) }; exchange.close()
    }
    server.createContext("/api/v1/tts") { exchange ->
        calls.incrementAndGet()
        check(exchange.requestHeaders.getFirst("Authorization") == "Bearer tf_live_fixture_key")
        val request = JsonParser.parseString(exchange.requestBody.reader(Charsets.UTF_8).readText()).asJsonObject
        check(!request.has("key") && !request.has("schemaVersion") && request["voiceId"].asString.endsWith("-en"))
        synchronized(texts) { texts += request["text"].asString }
        Thread.sleep(80)
        val code = responseCode.get(); val data = if (code == 200) audio else "{}".toByteArray()
        exchange.responseHeaders.set("Content-Type", if (code == 200) "audio/wav" else "application/json")
        exchange.responseHeaders.set("X-Chars-Charged", request["text"].asString.length.toString())
        exchange.sendResponseHeaders(code, data.size.toLong()); exchange.responseBody.use { it.write(data) }; exchange.close()
    }
    server.start()
    var writable = true
    fun file(path: String) = JsonObject().apply {
        val text = Files.readString(project.resolve(path))
        addProperty("path", path); addProperty("content", text); addProperty("writable", writable); addProperty("truncated", false)
        addProperty("version", MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) })
    }
    val context = object : IdeExtensionContext {
        override val profileDirectory = root.resolve("profile")
        override fun enabled() = true
        override fun projectRoot(projectFile: String) = project.toString().also { check(projectFile == "fixture") }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonObject {
            projectRoot(projectFile)
            val path = arguments["path"]?.asString
            return when (method) {
                "file.read" -> file(path!!)
                "project.entry.create" -> {
                    check(writable && arguments["kind"].asString == "file")
                    val target = project.resolve(arguments["parent"].asString).resolve(arguments["name"].asString)
                    Files.writeString(target, arguments["content"].asString, CREATE_NEW); JsonObject()
                }
                "file.save" -> {
                    check(writable && file(path!!)["version"] == arguments["expectedVersion"])
                    Files.writeString(project.resolve(path), arguments["content"].asString); file(path)
                }
                else -> error("Unexpected project call: $method")
            }
        }
    }
    fun args(path: String) = JsonObject().apply { addProperty("expectedProjectFile", "fixture"); addProperty("path", path) }
    fun speech(hero: String) = JsonParser.parseString("""{"schemaVersion":1,"kind":"tellfairSpeech","text":"$hero stands with you.","voiceId":"${hero.lowercase()}-en","format":"wav"}""").asJsonObject
    fun jobArgs(id: String, path: String) = JsonObject().apply { addProperty("expectedProjectFile", "fixture"); addProperty("documentPath", path); addProperty("id", id); addProperty("waitMs", 1000) }
    try {
        TellfairRuntime(context, URI("http://127.0.0.1:${server.address.port}"), {}).use { runtime ->
            fun rpc(action: String, params: JsonObject): JsonObject {
                var value = runtime.request("tellfair.$action", params).asJsonObject
                if (action == "job") {
                    val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                    while (value["state"].asString == "running" && System.nanoTime() < until) { Thread.sleep(10); value = runtime.request("tellfair.job", params).asJsonObject }
                }
                return value
            }
            check(!rpc("status", args(""))["configured"].asBoolean)
            rpc("settings", JsonObject().apply { addProperty("key", "tf_live_fixture_key") })
            check(!rpc("status", args("")).toString().contains("tf_live_"))
            check(rpc("presets", JsonObject()).getAsJsonArray("voices").size() == 2)
            for (hero in listOf("Knight", "Mage")) {
                val path = "${hero.lowercase()}.tfspeech"
                val create = args(path).apply { add("document", speech(hero)) }
                val receipt = rpc("documentCreate", create)
                check(runCatching { rpc("documentCreate", create) }.isFailure)
                val generation = args(path).apply { addProperty("expectedVersion", receipt["version"].asString) }
                val initial = rpc("generateSaved", generation); val id = initial["id"].asString
                check(rpc("generateSaved", generation)["id"].asString == id)
                runtime.documentClosed("fixture", path)
                val finished = rpc("job", jobArgs(id, path))
                check(finished["state"].asString == "ready" && finished["sourceVersion"] == receipt["version"])
                check(runCatching { rpc("job", jobArgs(id, "other.tfspeech")) }.isFailure)
                val changed = args(path).apply { addProperty("expectedVersion", receipt["version"].asString); add("document", speech(hero).apply { addProperty("text", "Different line") }) }
                val edited = rpc("documentEdit", changed)
                check(runCatching { rpc("exportSaved", jobArgs(id, path)) }.isFailure)
                check(runCatching { rpc("documentEdit", changed) }.isFailure)
                rpc("documentEdit", args(path).apply { addProperty("expectedVersion", edited["version"].asString); add("document", speech(hero)) })
                val exported = rpc("exportSaved", jobArgs(id, path))
                check(exported["created"].asBoolean && Files.readAllBytes(project.resolve(exported["path"].asString)).contentEquals(audio))
                check(!rpc("exportSaved", jobArgs(id, path))["created"].asBoolean)
                Files.write(project.resolve(exported["path"].asString), byteArrayOf(9))
                check(runCatching { rpc("exportSaved", jobArgs(id, path)) }.isFailure)
                check(rpc("generateSaved", generation)["id"].asString == id)
            }
            check(calls.get() == 2 && texts == listOf("Knight stands with you.", "Mage stands with you."))
            val path = "retry.tfspeech"
            val receipt = rpc("documentCreate", args(path).apply { add("document", speech("Knight")) })
            val generation = args(path).apply { addProperty("expectedVersion", receipt["version"].asString) }
            responseCode.set(402)
            val failed = rpc("generateSaved", generation)["id"].asString
            check(rpc("job", jobArgs(failed, path))["error"].asString == "TELLFAIR_BALANCE")
            check(rpc("generateSaved", generation)["id"].asString == failed && calls.get() == 3)
            responseCode.set(200)
            val retried = rpc("generateSaved", generation.deepCopy().apply { addProperty("retry", true) })["id"].asString
            check(retried != failed && rpc("job", jobArgs(retried, path))["state"].asString == "ready" && calls.get() == 4)
            check(runCatching { rpc("generateSaved", args("knight.tfspeech").apply { addProperty("expectedVersion", file("knight.tfspeech")["version"].asString) }) }.isFailure)
            writable = false
            check(runCatching { rpc("exportSaved", jobArgs(retried, path)) }.isFailure)
            writable = true
            rpc("exportSaved", jobArgs(retried, path))
            for (bad in listOf("../outside.tfspeech", "src/../../outside.tfspeech", "C:/outside.tfspeech", "src\\escape.tfspeech"))
                check(runCatching { rpc("documentCreate", args(bad).apply { add("document", speech("Knight")) }) }.isFailure)
            check(runCatching { rpc("documentCreate", args("secret.tfspeech").apply { add("document", speech("Knight").apply { addProperty("key", "secret") }) }) }.isFailure)
            runtime.projectClosed()
            check(runCatching { rpc("job", jobArgs(retried, path)) }.isFailure)
        }
        println("PASS saved speech: two English characters, actual HTTP and WAV export, no duplicate paid requests, idempotent export/no overwrite, version and draft ownership, explicit retry, read-only/path/key guards and lifecycle.")
    } finally { server.stop(0) }
}
