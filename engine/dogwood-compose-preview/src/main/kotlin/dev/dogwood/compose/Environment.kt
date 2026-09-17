/*
 * Project Dogwood -- what the host tells the guest, for the preview path.
 *
 * `LocalHostEnvironment`, `LocalSegmentVersions`, `LocalLaunchParams` and `services()` are the four
 * places a payload reads something it could not have worked out for itself. On a device each is a
 * fact about the client that sent it. In a preview there is no client, and this file is the third
 * of the module's declared stand-ins.
 *
 * **What the preview answers with, and why it is visible rather than silent.** The window installs
 * a [PreviewHostServices] whose clock is this machine's clock, whose navigation prints what a real
 * host would have been asked to do, and whose feature flags carry a single entry named `preview`.
 * A screen that reads any of it therefore says *preview* on screen. The alternative -- an empty
 * `HostServices.None` -- is also honest, and is what a payload sees if the window is not the thing
 * hosting it; both are correct and neither is the device's answer.
 *
 * `LocalSegmentVersions` is the most important of the four to be careful about. On a device it is
 * the client's dictionary and it is what a payload branches on to survive skew. The preview has no
 * client, so it reports the versions the *preview back end* implements, which is always "the
 * newest". **A preview can therefore never show a skew failure**, and that is not a gap this
 * module could close: one Compose runtime has one dictionary by construction.
 */
@file:Suppress("unused")

package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogwood.protocol.DogwoodAnalytics
import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.DogwoodLog
import dev.dogwood.protocol.DogwoodNavigation
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.HttpResponse
import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.SERVICES_SEGMENT
import dev.dogwood.protocol.SERVICES_VERSION
import dev.dogwood.protocol.ServiceNames
import dev.dogwood.protocol.language
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** The same class the deployment path has, with the Zipline resolution step left out. */
class HostServices(
  val version: Int,
  val available: Set<String>,
  val log: DogwoodLog?,
  val clock: DogwoodClock?,
  val analytics: DogwoodAnalytics?,
  val network: DogwoodNetwork?,
  val navigation: DogwoodNavigation?,
  val routes: Set<String>,
  val flags: Map<String, String>,
) {
  fun debug(tag: String, message: String) = log?.log(LogLevel.Debug, tag, message)
  fun info(tag: String, message: String) = log?.log(LogLevel.Info, tag, message)
  fun warn(tag: String, message: String) = log?.log(LogLevel.Warn, tag, message)
  fun error(tag: String, message: String) = log?.log(LogLevel.Error, tag, message)

  fun track(name: String, properties: Map<String, String> = emptyMap()) {
    analytics?.track(name, properties)
  }

  fun flag(name: String, default: String = ""): String = flags[name] ?: default

  fun flagEnabled(name: String, default: Boolean = false): Boolean =
    flags[name]?.equals("true", ignoreCase = true) ?: default

  fun nowEpochMillis(): Long? = clock?.nowEpochMillis()

  fun canNavigate(route: String): Boolean =
    navigation != null && (routes.isEmpty() || route in routes)

  fun navigate(route: String, params: JsonObject = JsonObject(emptyMap())): Boolean {
    if (!canNavigate(route)) return false
    navigation?.navigate(route, params) ?: return false
    return true
  }

  suspend fun fetch(request: HttpRequest): HttpResponse {
    val service = network ?: return HttpResponse(code = 0, failure = "this client offers no network service")
    return service.fetch(request)
  }

  companion object {
    val None = HostServices(0, emptySet(), null, null, null, null, null, emptySet(), emptyMap())
  }
}

/**
 * The stand-in a preview window installs: obvious answers, said out loud.
 *
 * Nothing here pretends to be a device. The clock is this machine's; navigation refuses nothing and
 * accomplishes nothing except a line on standard output naming the route a real host would have
 * been handed; analytics and logging go to standard output for the same reason; the network offers
 * no answer at all, because inventing an HTTP response is exactly the kind of quiet fiction a
 * preview must not commit.
 */
object PreviewHostServices {
  fun create(): HostServices = HostServices(
    version = SERVICES_VERSION,
    available = setOf(
      ServiceNames.LOG,
      ServiceNames.CLOCK,
      ServiceNames.ANALYTICS,
      ServiceNames.FEATURE_FLAGS,
      ServiceNames.NAVIGATION,
    ),
    log = PreviewLog,
    clock = PreviewClock,
    analytics = PreviewAnalytics,
    // Deliberately absent. `HostServices.fetch` answers "this client offers no network service",
    // which is a true sentence about a preview and a legible one on screen.
    network = null,
    navigation = PreviewNavigation,
    routes = setOf("experience/main", "experience/about"),
    flags = mapOf("preview" to "true"),
  )
}

private object PreviewClock : DogwoodClock {
  override fun nowEpochMillis(): Long = System.currentTimeMillis()
  override fun timeZoneId(): String = ZoneId.systemDefault().id
}

private object PreviewLog : DogwoodLog {
  override fun log(level: LogLevel, tag: String, message: String) {
    println("[preview] $level/$tag: $message")
  }
}

private object PreviewAnalytics : DogwoodAnalytics {
  override fun track(name: String, properties: Map<String, String>) {
    println("[preview] track $name $properties")
  }
}

private object PreviewNavigation : DogwoodNavigation {
  override fun routes(): Set<String> = setOf("experience/main", "experience/about")
  override fun navigate(route: String, params: JsonObject) {
    println("[preview] the host would navigate to $route with $params")
  }
}

val LocalHostServices = staticCompositionLocalOf { HostServices.None }
val LocalLaunchParams = staticCompositionLocalOf<JsonElement> { JsonNull }

@Composable
@ReadOnlyComposable
fun services(): HostServices = LocalHostServices.current

const val ServicesSegment = SERVICES_SEGMENT

val LocalHostEnvironment = compositionLocalOf { HostEnvironment() }

/**
 * The client's dictionary.
 *
 * A preview reports the newest of everything, because the preview back end *is* the newest of
 * everything. A payload that branches on this local will always take its most modern branch here,
 * and the branch a two-year-old client would take is untestable in a preview by construction.
 */
val LocalSegmentVersions = staticCompositionLocalOf { emptyMap<String, Int>() }

@Composable
fun isSystemInDarkTheme(): Boolean = LocalHostEnvironment.current.darkMode

class StringTable(
  private val byLanguage: Map<String, Map<String, String>>,
  private val fallbackLanguage: String = "en",
) {
  val languages: Set<String> get() = byLanguage.keys

  fun get(language: String, key: String): String =
    byLanguage[language]?.get(key)
      ?: byLanguage[fallbackLanguage]?.get(key)
      ?: key

  fun has(language: String, key: String): Boolean = byLanguage[language]?.containsKey(key) == true
}

val LocalStringTable = staticCompositionLocalOf { StringTable(emptyMap()) }

@Composable
@ReadOnlyComposable
fun strings(key: String): String =
  LocalStringTable.current.get(LocalHostEnvironment.current.language, key)
