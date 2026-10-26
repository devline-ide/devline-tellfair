package devline.tellfair

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Portable request data only. Authentication never belongs to a project document. */
internal object SpeechDocument {
    fun request(text: String): JsonObject {
        require(text.length <= 32000) { "Speech document is too large." }
        val source = JsonParser.parseString(text).asJsonObject
        require(source["schemaVersion"]?.asInt == 1 && source["kind"]?.asString == "tellfairSpeech") { "Unsupported speech document." }
        require(source.keySet().all { it in setOf("schemaVersion", "kind", "text", "presetId", "voiceId", "customVoiceId", "params", "format", "seed") }) { "Unknown speech document field." }
        val body = source.deepCopy().apply { remove("schemaVersion"); remove("kind") }
        val speech = body["text"]
        require(speech != null && speech.isJsonPrimitive && speech.asJsonPrimitive.isString && speech.asString.isNotBlank() && speech.asString.codePointCount(0, speech.asString.length) <= 5000) { "Use 1 to 5000 characters." }
        for (key in listOf("presetId", "voiceId", "customVoiceId")) body[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length in 1..200) { "Invalid voice or preset." }
        }
        if (!body.has("format")) body.addProperty("format", "mp3")
        require(body["format"].asString in setOf("mp3", "wav", "opus")) { "Unsupported audio format." }
        body["seed"]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("-?[0-9]{1,10}")) && it.asLong in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid seed." } }
        body["params"]?.let { parameters ->
            val ranges = mapOf("exaggeration" to 0.25..2.0, "cfg_weight" to 0.0..1.0, "temperature" to 0.05..1.5, "speed_factor" to 0.5..2.0)
            require(parameters.isJsonObject && parameters.asJsonObject.keySet().all { it in ranges })
            parameters.asJsonObject.entrySet().forEach { (key, value) -> require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asDouble.isFinite() && value.asDouble in ranges.getValue(key)) { "Invalid voice parameter." } }
        }
        return body
    }
}
