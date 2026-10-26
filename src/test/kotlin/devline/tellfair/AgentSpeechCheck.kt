package devline.tellfair

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import devline.ai.AiAssistantService
import devline.ai.AiHost
import devline.ai.AiSettings
import devline.ai.AiSettingsStore
import devline.extensions.IdeExtensionContext
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The real assistant loop, review, closed MCP bridge and Tellfair owner create two English voice assets. */
fun main() {
    val root = Files.createTempDirectory("tellfair-agent-"); val project = Files.createDirectory(root.resolve("project"))
    val profile = root.resolve("profile"); val gson = Gson(); val paid = AtomicInteger()
    val spoken = CopyOnWriteArrayList<String>(); val failures = CopyOnWriteArrayList<String>()
    val audio = byteArrayOf(82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86, 69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0, 1, 0, 64, 31, 0, 0, -128, 62, 0, 0, 2, 0, 16, 0, 100, 97, 116, 97, 0, 0, 0, 0)
    fun read(path: String) = JsonObject().apply {
        val text = Files.readString(project.resolve(path)); addProperty("path", path); addProperty("content", text)
        addProperty("version", MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) })
        addProperty("writable", true); addProperty("truncated", false)
    }
    val context = object : IdeExtensionContext {
        override val profileDirectory = profile
        override fun enabled() = true
        override fun projectRoot(projectFile: String) = project.toString().also { check(projectFile == "fixture") }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonElement {
            projectRoot(projectFile)
            return when (method) {
                "file.read" -> read(arguments["path"].asString)
                "project.entry.create" -> {
                    val path = arguments["parent"].asString.let { if (it.isEmpty()) "" else "$it/" } + arguments["name"].asString
                    Files.writeString(project.resolve(path), arguments["content"].asString, CREATE_NEW); read(path)
                }
                else -> error("Unexpected project method $method")
            }
        }
    }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v1/presets") { exchange ->
        val data = """{"presets":[],"voices":[{"id":"english-hero","name":"Hero","lang":"en"}]}""".toByteArray()
        exchange.sendResponseHeaders(200, data.size.toLong()); exchange.responseBody.use { it.write(data) }; exchange.close()
    }
    server.createContext("/api/v1/tts") { exchange ->
        check(exchange.requestHeaders.getFirst("Authorization") == "Bearer tf_live_fixture_key")
        val body = JsonParser.parseString(exchange.requestBody.reader(Charsets.UTF_8).readText()).asJsonObject
        check(body["voiceId"].asString == "english-hero")
        spoken += body["text"].asString; paid.incrementAndGet()
        exchange.responseHeaders.set("Content-Type", "audio/wav"); exchange.responseHeaders.set("X-Chars-Charged", body["text"].asString.length.toString())
        exchange.sendResponseHeaders(200, audio.size.toLong()); exchange.responseBody.use { it.write(audio) }; exchange.close()
    }
    var step = 0; var voice = ""; var version = ""; var generation = ""
    val exports = mutableListOf<String>()
    fun tool(name: String, args: Map<String, Any?>) = mapOf("content" to null, "tool_calls" to listOf(mapOf("id" to "call-$step", "type" to "function", "function" to mapOf("name" to name, "arguments" to gson.toJson(args)))))
    fun extension(action: String, values: Map<String, Any?> = emptyMap(), query: Boolean = false) = tool(if (query) "extension_query" else "extension_action", mapOf("extension" to "devline.tellfair", "action" to action, "argumentsJson" to gson.toJson(values)))
    server.createContext("/v1/chat/completions") { exchange ->
        try {
            val body = JsonParser.parseString(exchange.requestBody.reader(Charsets.UTF_8).readText()).asJsonObject
            check(!body.toString().contains("tf_live_"))
            val last = body.getAsJsonArray("messages").last().asJsonObject
            val result = if (last["role"].asString == "tool") JsonParser.parseString(last["content"].asString).asJsonObject.let { it.getAsJsonObject("structuredContent") ?: it } else JsonObject()
            check(!result.has("error")) { result.toString() }
            val message: Map<String, Any?> = when (step++) {
                0 -> tool("read_skill", mapOf("name" to "builtin/devline-tellfair"))
                1 -> tool("extension_capabilities", emptyMap())
                2 -> extension("status", query = true)
                3 -> { check(result["configured"].asBoolean); extension("presets", query = true) }
                4, 8 -> {
                    val hero = if (step == 5) "Knight" else "Mage"
                    if (step == 5) voice = result.getAsJsonArray("voices").single().asJsonObject["id"].asString
                    else exports += result["path"].asString
                    extension("documentCreate", mapOf("path" to "${hero.lowercase()}.tfspeech", "document" to mapOf("schemaVersion" to 1, "kind" to "tellfairSpeech", "text" to "$hero, stand your ground!", "voiceId" to voice, "format" to "wav")))
                }
                5, 9 -> { version = result["version"].asString; extension("generateSaved", mapOf("path" to result["path"].asString, "expectedVersion" to version)) }
                6, 10 -> { generation = result["id"].asString; extension("job", mapOf("id" to generation, "documentPath" to result["documentPath"].asString, "waitMs" to 1000), query = true) }
                7, 11 -> { check(result["state"].asString == "ready" && result["sourceVersion"].asString == version); extension("exportSaved", mapOf("id" to generation, "documentPath" to result["documentPath"].asString)) }
                12 -> {
                    exports += result["path"].asString
                    tool("create_file", mapOf("parent" to "", "name" to "voiceovers-en.json", "content" to gson.toJson(mapOf("language" to "en", "voice" to voice, "clips" to exports))))
                }
                else -> mapOf("content" to "Created and exported English voiceovers for Knight and Mage.")
            }
            val data = gson.toJson(mapOf("choices" to listOf(mapOf("message" to message, "finish_reason" to if (message.containsKey("tool_calls")) "tool_calls" else "stop")))).toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, data.size.toLong()); exchange.responseBody.use { it.write(data) }
        } catch (cause: Throwable) { failures += cause.toString(); exchange.sendResponseHeaders(500, -1) }
        finally { exchange.close() }
    }
    server.start()
    try {
        TellfairRuntime(context, URI("http://127.0.0.1:${server.address.port}"), {}).use { runtime ->
            runtime.request("tellfair.settings", JsonObject().apply { addProperty("key", "tf_live_fixture_key") })
            val host = object : AiHost {
                override fun root(projectFile: String) = context.projectRoot(projectFile)
                override fun connected(projectFile: String) = projectFile == "fixture"
                override fun call(projectFile: String, method: String, arguments: JsonObject): JsonElement = when {
                    method.startsWith("tellfair.") -> runtime.request(method, arguments.deepCopy().apply { addProperty("expectedProjectFile", projectFile) })
                    method == "ide.extensions.list" -> JsonObject().apply { add("extensions", JsonArray().apply { add(JsonObject().apply {
                        add("manifest", JsonObject().apply { addProperty("id", "devline.tellfair"); addProperty("version", "0.1.3") })
                        addProperty("installed", true); addProperty("enabled", true); addProperty("restartRequired", false)
                    }) }) }
                    method == "project.tree" -> JsonArray()
                    else -> context.callProject(projectFile, method, arguments)
                }
            }
            val settings = AiSettingsStore(profile.resolve("assistant"))
            settings.write(AiSettings(baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "owned-fixture", maxSteps = 18, stream = false, approvalMode = "full"), "fixture-model-key", false)
            AiAssistantService(settings, host).use { service ->
                val job = service.start("fixture", "Придумай и сделай озвучку Knight и Mage на английском", "", emptyList())
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (true) {
                    val view = service.read(job.id, "fixture")
                    check(view.state !in setOf("failed", "cancelled")) { view.toString() }
                    val review = view.review
                    if (review != null) { check(review.autoAllowed); service.decide(job.id, "fixture", review.id, true, emptyList()) }
                    if (view.state == "completed") {
                        check(view.events.none { it.status == "failed" })
                        check(view.changedFiles.count { it["binary"]?.asBoolean == true } == 2)
                        check(view.changedFiles.count { it["path"]?.asString?.endsWith(".tfspeech") == true } == 2)
                        check(!gson.toJson(view).contains("tf_live_")); break
                    }
                    check(System.nanoTime() < until) { "Agent fixture timed out: $view" }; Thread.sleep(10)
                }
            }
            check(failures.isEmpty()) { failures.toString() }
            check(paid.get() == 2 && spoken == listOf("Knight, stand your ground!", "Mage, stand your ground!"))
            check(exports.size == 2 && exports.all { Files.readAllBytes(project.resolve(it)).contentEquals(audio) })
            check(JsonParser.parseString(Files.readString(project.resolve("voiceovers-en.json"))).asJsonObject.getAsJsonArray("clips").size() == 2)
        }
        println("PASS assistant Tellfair: real model-tool loop, 13-step English cast workflow, review, native owner, two HTTP generations, two WAV assets, manifest, binary changed-file reporting and no credential exposure; no paid service used.")
    } finally { server.stop(0) }
}
