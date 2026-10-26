package devline.tellfair

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionRuntime
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class TellfairRuntime(private val context: IdeExtensionContext,
    private val endpoint: URI = URI("https://tellfair.com"),
    private val browse: (URI) -> Unit = { java.awt.Desktop.getDesktop().browse(it) }) : IdeExtensionRuntime {
    private val keyFile = context.profileDirectory.resolve("tellfair/api-key")
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "devline-tellfair").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    private class Job(val id: String, val project: String, val document: String, val format: String, val sourceVersion: String? = null) {
        @Volatile var state = "running"
        @Volatile var error = ""
        @Volatile var charged = ""
        @Volatile var bytes: ByteArray? = null
        @Volatile var exportedPath: String? = null
        var task: Future<*>? = null
    }
    private var current: Job? = null
    private val account = TellfairApi(http, endpoint)
    private val miniFile = context.profileDirectory.resolve("tellfair/mini-cabinet")
    private val documents = SpeechDocuments(context)
    @Synchronized override fun request(method: String, params: JsonObject): JsonElement = dispatch(method, params)
    private fun dispatch(method: String, params: JsonObject): JsonElement = when (method.substringAfter('.')) {
        "settings" -> settings(params)
        "status" -> JsonObject().apply {
            val project = params["expectedProjectFile"].asString; context.projectRoot(project)
            addProperty("configured", key().isNotEmpty())
            current?.takeIf { it.project == project }?.let { add("job", jobView(it)) }
        }
        "documentRead", "documentCreate", "documentEdit" -> documents.request(method.substringAfter('.'), params)
        "cabinet" -> { browse(URI("https://tellfair.com/account")); JsonObject() }
        "presets", "balance", "voices", "usage", "remoteJobs", "remoteJob" -> account.read(method.substringAfter('.'), params, key())
        "miniCabinet" -> JsonObject().apply { addProperty("url", miniUrl().ifEmpty { "https://tellfair.com/account" }) }
        "generate" -> generate(params)
        "generateSaved" -> generateSaved(params)
        "job" -> jobView(owned(params))
        "audio" -> audio(params)
        "exportAudio" -> export(params)
        "exportSaved" -> {
            val job = owned(params)
            require(job.sourceVersion != null) { "This request was not generated from a saved document." }
            require(documents.source(job.project, job.document, job.sourceVersion)["writable"]?.asBoolean == true) { "Speech document is read only." }
            export(params, reuse = true)
        }
        "cancel" -> { val job = owned(params); cancel(job); jobView(job) }
        else -> throw IdeExtensionException("UNKNOWN_METHOD", "Unknown Tellfair method.")
    }
    private fun key(): String = if (Files.isRegularFile(keyFile, NOFOLLOW_LINKS)) {
        require(!Files.isSymbolicLink(keyFile.parent) && Files.size(keyFile) <= 512)
        Files.readString(keyFile, Charsets.UTF_8).trim()
    } else ""
    private fun settings(params: JsonObject): JsonObject {
        params["key"]?.let {
            val token = it.asString.trim()
            require(token.isEmpty() || token.matches(Regex("tf_live_[A-Za-z0-9_-]{8,480}"))) { "Invalid Tellfair API key." }
            account.clear()
            Files.createDirectories(keyFile.parent)
            require(!Files.isSymbolicLink(keyFile.parent) && !Files.isSymbolicLink(keyFile))
            val temporary = Files.createTempFile(keyFile.parent, "key-", ".tmp")
            try {
                Files.getFileAttributeView(temporary, java.nio.file.attribute.PosixFileAttributeView::class.java)?.setPermissions(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
                Files.writeString(temporary, token, Charsets.UTF_8)
                try { Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
        params["miniCabinetUrl"]?.let {
            val url = it.asString.trim()
            require(url.isEmpty() || validMiniUrl(url)) { "TELLFAIR_MINI_URL" }
            Files.createDirectories(miniFile.parent)
            require(!Files.isSymbolicLink(miniFile.parent) && !Files.isSymbolicLink(miniFile))
            devline.extensions.IdeExtensionFiles.writeAtomic(miniFile, url)
            Files.getFileAttributeView(miniFile, java.nio.file.attribute.PosixFileAttributeView::class.java)?.setPermissions(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
        }
        return JsonObject().apply { addProperty("configured", key().isNotEmpty()); addProperty("miniConfigured", miniUrl().isNotEmpty()) }
    }
    private fun miniUrl(): String = if (Files.isRegularFile(miniFile, NOFOLLOW_LINKS)) {
        require(!Files.isSymbolicLink(miniFile.parent) && Files.size(miniFile) <= 2048)
        Files.readString(miniFile, Charsets.UTF_8).trim().also { require(it.isEmpty() || validMiniUrl(it)) }
    } else ""
    private fun validMiniUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length <= 2048 && uri.scheme == "https" && uri.host == "tellfair.com" && uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null && uri.path !in setOf("", "/", "/login", "/account")
    }.getOrDefault(false)
    private fun generateSaved(params: JsonObject): JsonObject {
        val project = params["expectedProjectFile"].asString
        val path = params["path"].asString
        val file = documents.source(project, path, params["expectedVersion"].asString)
        require(file["writable"]?.asBoolean == true) { "Speech document is read only." }
        val version = file["version"].asString
        current?.takeIf { it.project == project && it.document == path && it.sourceVersion == version }?.let {
            if (it.state in setOf("running", "ready") || params["retry"]?.asBoolean != true) return jobView(it)
        }
        if (current?.let { it.state == "running" || it.state == "ready" && it.exportedPath == null } == true)
            throw IdeExtensionException("TELLFAIR_BUSY", "Finish or export the current speech request before starting another.")
        return generate(JsonObject().apply {
            addProperty("expectedProjectFile", project); addProperty("documentPath", path); addProperty("source", file["content"].asString)
        }, version)
    }
    private fun generate(params: JsonObject, sourceVersion: String? = null): JsonObject {
        require(current?.state != "running") { "A Tellfair request is already running." }
        if (current?.let { it.sourceVersion != null && it.state == "ready" && it.exportedPath == null } == true)
            throw IdeExtensionException("TELLFAIR_BUSY", "Export the saved-document speech request before starting another.")
        val project = params["expectedProjectFile"]?.asString ?: error("Open a project first.")
        context.projectRoot(project)
        val document = params["documentPath"]?.asString ?: error("Open a .tfspeech document first.")
        require(document.endsWith(".tfspeech"))
        val body = SpeechDocument.request(params["source"].asString)
        val token = key(); if (token.isEmpty()) throw IdeExtensionException("TELLFAIR_KEY", "Configure the API key in Tellfair settings first.")
        val job = Job(UUID.randomUUID().toString(), project, document, body["format"].asString, sourceVersion)
        current?.bytes = null; current = job
        job.task = worker.submit {
            try {
                val request = HttpRequest.newBuilder(endpoint.resolve("/api/v1/tts")).timeout(Duration.ofMinutes(3))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), Charsets.UTF_8)).build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { stream ->
                    if (response.statusCode() !in 200..299) {
                        throw IllegalStateException(TellfairApi.errorCode(response.statusCode(), stream.readNBytes(4096)))
                    }
                    val mime = response.headers().firstValue("Content-Type").orElse("").substringBefore(';').lowercase()
                    require(mime.startsWith("audio/") || mime == "application/octet-stream") { "TELLFAIR_AUDIO" }
                    val bytes = stream.readNBytes(32 * 1024 * 1024 + 1)
                    require(bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024) { "TELLFAIR_AUDIO" }
                    synchronized(job) { if (!Thread.currentThread().isInterrupted && job.state == "running") {
                        job.charged = response.headers().firstValue("X-Chars-Charged").orElse("").take(20)
                        job.bytes = bytes; job.state = "ready"
                    } }
                }
            } catch (cause: Exception) {
                synchronized(job) { if (job.state == "running") { job.error = cause.message?.takeIf { it.startsWith("TELLFAIR_") } ?: "TELLFAIR_NETWORK"; job.state = "failed" } }
            }
        }
        return jobView(job)
    }
    private fun owned(params: JsonObject): Job = current?.takeIf {
        it.id == params["id"]?.asString && it.project == params["expectedProjectFile"]?.asString && it.document == params["documentPath"]?.asString
    } ?: throw IdeExtensionException("FILE_CONFLICT", "The Tellfair request belongs to another document.")
    private fun jobView(job: Job) = JsonObject().apply {
        addProperty("id", job.id); addProperty("state", job.state); addProperty("error", job.error)
        addProperty("charged", job.charged); addProperty("size", job.bytes?.size ?: 0); addProperty("format", job.format)
        addProperty("documentPath", job.document); job.sourceVersion?.let { addProperty("sourceVersion", it) }
        job.exportedPath?.let { addProperty("outputPath", it) }
    }
    private fun audio(params: JsonObject): JsonObject {
        val job = owned(params); val bytes = job.bytes ?: error("Audio is not ready.")
        val offset = params["offset"]?.asInt ?: 0
        require(offset in 0..bytes.size)
        return JsonObject().apply { addProperty("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, minOf(offset + 48000, bytes.size)))) }
    }
    private fun export(params: JsonObject, reuse: Boolean = false): JsonObject {
        val job = owned(params); val bytes = job.bytes ?: error("Audio is not ready.")
        require(job.state == "ready") { "Audio is not ready." }
        val root = Path.of(context.projectRoot(job.project)).toRealPath()
        val document = root.resolve(job.document).normalize()
        require(document.startsWith(root))
        var parent: Path? = document.parent
        while (parent != null && parent.startsWith(root)) { require(!Files.isSymbolicLink(parent)); parent = parent.parent }
        require(document.parent.toRealPath().startsWith(root))
        val filename = document.fileName.toString().removeSuffix(".tfspeech") + "-" + job.id.take(8) + "." + job.format
        val target = document.resolveSibling(filename)
        val existing = Files.exists(target, NOFOLLOW_LINKS)
        if (reuse && existing) {
            require(Files.isRegularFile(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target) && Files.size(target) == bytes.size.toLong() && Files.readAllBytes(target).contentEquals(bytes)) { "The exported audio changed; it will not be overwritten." }
        } else Files.write(target, bytes, CREATE_NEW)
        job.exportedPath = root.relativize(target).toString().replace('\\', '/')
        return JsonObject().apply { addProperty("path", job.exportedPath); addProperty("created", !existing); addProperty("size", bytes.size); addProperty("format", job.format); job.sourceVersion?.let { addProperty("sourceVersion", it) } }
    }
    private fun cancel(job: Job) = synchronized(job) { job.state = "cancelled"; job.task?.cancel(true); job.bytes = null }
    @Synchronized override fun documentClosed(projectFile: String, path: String) { current?.takeIf { it.project == projectFile && it.document == path && it.sourceVersion == null }?.let { cancel(it); current = null } }
    @Synchronized override fun projectClosed() { current?.let(::cancel); current = null }
    @Synchronized override fun close() { projectClosed(); account.close(); worker.shutdownNow(); http.close() }
}
