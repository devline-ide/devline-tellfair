package devline.tellfair

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
    val directory = Path.of(args[0]).also(Files::createDirectories)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val calls = AtomicInteger()
    server.createContext("/api/v1/") { exchange ->
        calls.incrementAndGet()
        val public = exchange.requestURI.path.endsWith("presets")
        check(exchange.requestMethod == "GET")
        check(public || exchange.requestHeaders.getFirst("Authorization") == "Bearer tf_live_fixture_key")
        val value = when (exchange.requestURI.path) {
            "/api/v1/presets" -> """{"presets":[],"voices":[],"piperVoices":[{"id":"chat:mabo","name":"Mabo"}]}"""
            "/api/v1/voices" -> """{"voices":[{"id":"vc_fixture","name":"Мой голос","created_at":123,"token":"must_not_escape"}]}"""
            "/api/v1/balance" -> """{"plan":{"id":"pro","name":"Про","until":123},"chars":{"plan":120,"pack":4,"total":124},"token":"must_not_escape"}"""
            "/api/v1/usage" -> { check(exchange.requestURI.rawQuery == "limit=2"); """{"items":[{"delta":-4,"reason":"tts","ref":"job1","at":123}]}""" }
            "/api/v1/jobs" -> { check(exchange.requestURI.rawQuery == "limit=2&kind=audiobook"); """{"jobs":[{"id":"job1","status":"queued","ahead":2,"text":"private text excluded"}]}""" }
            "/api/v1/jobs/job1" -> """{"job":{"id":"job1","status":"done","url":"private_url_excluded"}}"""
            else -> """{"code":"not_found","error":"absent"}"""
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(if (exchange.requestURI.path.endsWith("missing")) 404 else 200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }; exchange.close()
    }
    server.start()
    val context = object : IdeExtensionContext {
        override val profileDirectory = directory.resolve("profile")
        override fun enabled() = true
        override fun projectRoot(projectFile: String) = directory.toString()
        override fun callProject(projectFile: String, method: String, arguments: JsonObject) = error("Unexpected project call")
    }
    try { TellfairRuntime(context, URI("http://127.0.0.1:" + server.address.port), {}).use { runtime ->
        fun call(method: String, params: JsonObject = JsonObject()) = runtime.request("tellfair.$method", params).asJsonObject
        fun read(method: String, params: JsonObject = JsonObject()): JsonObject {
            repeat(500) { val result = call(method, params); if (result["state"].asString == "ready") return result; Thread.sleep(5) }; error("Read timeout")
        }
        check(runCatching { call("balance") }.exceptionOrNull().let { it is IdeExtensionException && it.code == "TELLFAIR_KEY" })
        call("settings", JsonObject().apply { addProperty("key", "tf_live_fixture_key") })
        check(read("presets").getAsJsonArray("piperVoices")[0].asJsonObject["id"].asString == "chat:mabo")
        check(read("balance").getAsJsonObject("chars")["total"].asInt == 124)
        check(!read("balance").toString().contains("must_not_escape"))
        check(read("voices").getAsJsonArray("voices")[0].asJsonObject["name"].asString == "Мой голос")
        check(!read("voices").toString().contains("token"))
        check(read("usage", JsonObject().apply { addProperty("limit", 2) }).getAsJsonArray("items")[0].asJsonObject["delta"].asInt == -4)
        check(!read("remoteJobs", JsonObject().apply { addProperty("limit", 2); addProperty("kind", "audiobook") }).toString().contains("private text"))
        check(!read("remoteJob", JsonObject().apply { addProperty("id", "job1") }).toString().contains("private_url"))
        check(runCatching { read("remoteJob", JsonObject().apply { addProperty("id", "missing") }) }.exceptionOrNull().let { it is IdeExtensionException && it.code == "TELLFAIR_NOT_FOUND" })
        check(runCatching { call("remoteJob", JsonObject().apply { addProperty("id", "../escape") }) }.isFailure)
        check(runCatching { call("usage", JsonObject().apply { addProperty("limit", 101) }) }.isFailure)
        val before = calls.get(); read("balance"); check(calls.get() == before)
        val mini = "https://tellfair.com/mini/fixture-private-link"
        val settings = call("settings", JsonObject().apply { addProperty("miniCabinetUrl", mini) })
        check(settings["miniConfigured"].asBoolean && !settings.toString().contains("fixture-private-link"))
        check(call("miniCabinet")["url"].asString == mini)
        for (invalid in listOf("https://example.com/mini/abc", "http://tellfair.com/mini/abc", "https://tellfair.com/account", "https://tellfair.com@evil.com/mini/abc")) check(runCatching { call("settings", JsonObject().apply { addProperty("miniCabinetUrl", invalid) }) }.isFailure)
        check(call("miniCabinet")["url"].asString == mini)
        call("settings", JsonObject().apply { addProperty("miniCabinetUrl", "") })
        check(!call("settings")["miniConfigured"].asBoolean)
        check(TellfairApi.errorCode(402, """{"code":"plan_upgrade"}""".toByteArray()) == "TELLFAIR_PLAN")
        println("PASS Tellfair account API: authenticated GET, Piper/custom voices, balance, usage, remote jobs, bounded arguments, cache, errors, private mini link and host-only URL.")
    } } finally { server.stop(0) }
}
