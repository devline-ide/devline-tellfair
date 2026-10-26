package devline.tellfair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import java.nio.file.Files
import java.nio.file.Path

/** Saved speech is validated and versioned through the project owner; credentials stay in the profile. */
internal class SpeechDocuments(private val context: IdeExtensionContext) {
    fun source(project: String, path: String, expectedVersion: String? = null): JsonObject {
        scoped(project, path)
        val file = context.callProject(project, "file.read", JsonObject().apply { addProperty("path", path) }).asJsonObject
        require(file["truncated"]?.asBoolean != true) { "Speech document is too large." }
        SpeechDocument.request(file["content"].asString)
        if (expectedVersion != null && file["version"].asString != expectedVersion)
            throw IdeExtensionException("FILE_CONFLICT", "Read the current speech document before editing or generating it.")
        return file
    }

    fun request(action: String, params: JsonObject): JsonObject {
        val project = params["expectedProjectFile"].asString
        val path = params["path"].asString
        scoped(project, path)
        if (action == "documentRead") return receipt(source(project, path))
        require(action in setOf("documentCreate", "documentEdit"))
        val document = params["document"]?.takeIf { it.isJsonObject } ?: error("Provide a speech document.")
        SpeechDocument.request(document.toString())
        val content = document.toString() + "\n"
        if (action == "documentCreate") {
            context.callProject(project, "project.entry.create", JsonObject().apply {
                addProperty("kind", "file"); addProperty("parent", path.substringBeforeLast('/', ""))
                addProperty("name", path.substringAfterLast('/')); addProperty("content", content)
            })
        } else {
            val file = source(project, path, params["expectedVersion"].asString)
            require(file["writable"]?.asBoolean == true) { "Speech document is read only." }
            context.callProject(project, "file.save", JsonObject().apply {
                addProperty("path", path); addProperty("expectedVersion", file["version"].asString); addProperty("content", content)
            })
        }
        return receipt(source(project, path)).apply { addProperty("created", action == "documentCreate") }
    }

    private fun receipt(file: JsonObject) = JsonObject().apply {
        addProperty("path", file["path"].asString); addProperty("version", file["version"].asString)
        addProperty("writable", file["writable"]?.asBoolean == true)
        add("document", JsonParser.parseString(file["content"].asString))
        val text = getAsJsonObject("document")["text"].asString
        addProperty("characters", text.codePointCount(0, text.length))
    }

    private fun scoped(project: String, path: String) {
        require(path.endsWith(".tfspeech") && path.length <= 1024 && !path.contains('\\') && !path.contains(':') &&
            path.none(Char::isISOControl) && path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") }) { "Choose a project-relative .tfspeech path." }
        val root = Path.of(context.projectRoot(project)).toRealPath()
        val file = root.resolve(path).normalize()
        require(file.startsWith(root))
        var current: Path? = file
        while (current != null && current.startsWith(root)) {
            require(!Files.isSymbolicLink(current)) { "Linked speech paths are not supported." }
            if (Files.exists(current)) require(current.toRealPath().startsWith(root))
            current = current.parent
        }
    }
}
