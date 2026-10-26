package devline.tellfair

import com.google.gson.Gson
import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionManifest
import devline.extensions.IdeExtensionProvider
import devline.extensions.IdeExtensionRuntime

class TellfairExtension : IdeExtensionProvider {
    override val manifest: IdeExtensionManifest = javaClass.getResourceAsStream("/tellfair-extension.json")!!.reader(Charsets.UTF_8).use {
        Gson().fromJson(Gson().fromJson(it, JsonObject::class.java).getAsJsonObject("extension"), IdeExtensionManifest::class.java)
    }
    override fun activate(context: IdeExtensionContext): IdeExtensionRuntime = TellfairRuntime(context)
}
