/*
 * Project Dogwood -- loading a compiled guest.
 *
 * **This is not Layer 3.** roadmap.md Phase 1 step 9 requires delivery through Zipline
 * properly: a signed manifest, Ed25519 verification, and a disk cache, via `ZiplineLoader`.
 * None of that is here. This loads bytes the caller already has, so the vertical slice can be
 * driven from a payload before the delivery layer exists, and it is deliberately the shortest
 * path that is honest about being one.
 */
package dev.dogwood.host

import app.cash.zipline.Zipline
import app.cash.zipline.loader.ZiplineFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString.Companion.toByteString

@Serializable
data class BundleManifest(
  val modules: Map<String, BundleModule> = emptyMap(),
  val mainModuleId: String = "./guest.js",
  val mainFunction: String? = null,
)

@Serializable
data class BundleModule(
  val url: String,
  val dependsOnIds: List<String> = emptyList(),
)

private val ManifestJson = Json { ignoreUnknownKeys = true }

/**
 * A compiled guest: its manifest, and its `.zipline` files by name.
 *
 * A `.zipline` file is a container -- magic prefix, version, then sections -- so the QuickJS
 * bytecode is unwrapped here exactly as `ZiplineLoadReceiver` does.
 */
class GuestBundle(
  manifestText: String,
  private val files: Map<String, ByteArray>,
) {
  private val manifest = ManifestJson.decodeFromString(BundleManifest.serializer(), manifestText)

  private fun loadOrder(): List<String> {
    val ordered = LinkedHashSet<String>()
    fun visit(id: String) {
      if (id in ordered) return
      val module = manifest.modules[id] ?: error("manifest names module '$id' but does not define it")
      for (dependency in module.dependsOnIds) visit(dependency)
      ordered += id
    }
    for (id in manifest.modules.keys) visit(id)
    return ordered.toList()
  }

  /** Loads every module in dependency order and runs the manifest's named entry point. */
  @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
  fun loadInto(zipline: Zipline) {
    for (id in loadOrder()) {
      val url = manifest.modules.getValue(id).url
      val name = url.substringAfterLast('/')
      val bytes = files[name] ?: error("manifest references '$name', which is not in the bundle")
      val container = ZiplineFile.read(Buffer().write(bytes.toByteString()))
      zipline.loadJsModule(container.quickjsBytecode.toByteArray(), id)
    }
    val mainFunction = manifest.mainFunction
      ?: error("manifest declares no mainFunction; set `zipline { mainFunction }` on the guest")
    zipline.quickJs.evaluate(
      "require('${manifest.mainModuleId}').$mainFunction()",
      "RunApplication.kt",
    )
  }
}
