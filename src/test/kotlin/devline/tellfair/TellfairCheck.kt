package devline.tellfair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import devline.extensions.IdeExtensionContext
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
    val root = if (args.isEmpty()) Files.createTempDirectory("tellfair-check-") else Path.of(args[0]).also(Files::createDirectories)
    val project = root.resolve("project").also(Files::createDirectories)
    val audio = ByteArray(120000) { (it % 127).toByte() }
    val status = AtomicInteger(200); val calls = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v1/presets") { exchange ->
        val bytes = """{"presets":[{"id":"news-anchor","name":"News"}],"voices":[]}""".toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
    }
    server.createContext("/api/v1/tts") { exchange ->
        calls.incrementAndGet()
        check(exchange.requestMethod == "POST" && exchange.requestHeaders.getFirst("Authorization") == "Bearer tf_live_fixture_key")
        val request = JsonParser.parseString(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).asJsonObject
        check(request["text"].asString == "Привет" && !request.has("schemaVersion") && !request.has("key"))
        val bytes = if (status.get() == 200) audio else "{}".toByteArray()
        exchange.responseHeaders.set("Content-Type", if (status.get() == 200) "audio/wav" else "application/json")
        exchange.responseHeaders.set("X-Chars-Charged", "6")
        exchange.sendResponseHeaders(status.get(), bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
    }
    server.start()
    val context = object : IdeExtensionContext {
        override val profileDirectory = root.resolve("profile")
        override fun enabled() = true
        override fun projectRoot(projectFile: String): String { check(projectFile == "fixture"); return project.toString() }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject) = error("Unexpected project RPC")
    }
    val source = """{"schemaVersion":1,"kind":"tellfairSpeech","text":"Привет","presetId":"news-anchor","format":"wav","params":{"speed_factor":1}}"""
    val params = JsonObject().apply { addProperty("expectedProjectFile", "fixture"); addProperty("documentPath", "hello.tfspeech"); addProperty("source", source) }
    fun request(job: String) = params.deepCopy().apply { addProperty("id", job) }
    fun await(runtime: TellfairRuntime, id: String): JsonObject {
        repeat(300) {
            val value = runtime.request("tellfair.job", request(id)).asJsonObject
            if (value["state"].asString != "running") return value
            Thread.sleep(10)
        }
        error("Tellfair fixture timeout")
    }
    try {
        TellfairRuntime(context, URI("http://127.0.0.1:" + server.address.port), { check(it.toString() == "https://tellfair.com/account") }).use { runtime ->
            check(!runtime.request("tellfair.settings", JsonObject()).asJsonObject["configured"].asBoolean)
            val saved = runtime.request("tellfair.settings", JsonObject().apply { addProperty("key", "tf_live_fixture_key") })
            check(!saved.toString().contains("tf_live"))
            var catalog = runtime.request("tellfair.presets", JsonObject()).asJsonObject
            repeat(200) { if (catalog["state"]?.asString == "loading") { Thread.sleep(10); catalog = runtime.request("tellfair.presets", JsonObject()).asJsonObject } }
            check(catalog["presets"].asJsonArray.size() == 1)
            val id = runtime.request("tellfair.generate", params).asJsonObject["id"].asString
            val finished = await(runtime, id)
            check(finished["state"].asString == "ready" && finished["charged"].asString == "6")
            val first = Base64.getDecoder().decode(runtime.request("tellfair.audio", request(id)).asJsonObject["data"].asString)
            check(first.size == 48000 && first.contentEquals(audio.copyOfRange(0, 48000)))
            check(runCatching { runtime.request("tellfair.audio", request(id).apply { addProperty("documentPath", "other.tfspeech") }) }.isFailure)
            val exported = runtime.request("tellfair.exportAudio", request(id)).asJsonObject["path"].asString
            check(Files.readAllBytes(project.resolve(exported)).contentEquals(audio))
            check(runCatching { runtime.request("tellfair.exportAudio", request(id)) }.isFailure)
            runtime.request("tellfair.cabinet", JsonObject())
            for ((code, expected) in listOf(401 to "TELLFAIR_KEY", 402 to "TELLFAIR_BALANCE", 429 to "TELLFAIR_RATE", 500 to "TELLFAIR_SERVER")) {
                status.set(code)
                val next = runtime.request("tellfair.generate", params).asJsonObject["id"].asString
                check(await(runtime, next)["error"].asString == expected)
            }
            runtime.projectClosed()
            check(runCatching { runtime.request("tellfair.job", request(id)) }.isFailure)
            runtime.request("tellfair.settings", JsonObject().apply { addProperty("key", "") })
            check(runCatching { runtime.request("tellfair.generate", params) }.isFailure)
        }
        check(calls.get() == 5)
        check(SpeechDocument.request(source)["format"].asString == "wav")
        fun invalid(edit: (JsonObject) -> Unit) = JsonParser.parseString(source).asJsonObject.apply(edit).toString()
        for (bad in listOf(invalid { it.addProperty("format", "exe") }, invalid { it.addProperty("text", "a".repeat(5001)) }, invalid { it.getAsJsonObject("params").addProperty("speed_factor", 10) }, invalid { it.addProperty("key", "secret") })) check(runCatching { SpeechDocument.request(bad) }.isFailure)
        println("PASS Tellfair: request contract, private key, chunked audio, export/no overwrite, document ownership, HTTP errors, close, schema limits.")
    } finally { server.stop(0) }
}
