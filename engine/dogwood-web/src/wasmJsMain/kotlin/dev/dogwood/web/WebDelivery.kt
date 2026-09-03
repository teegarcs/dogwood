/*
 * Project Dogwood -- the web delivery path, and the gate that runs before it.
 *
 * `DogwoodDelivery` is mobile-only: it verifies an Ed25519-signed `ZiplineManifest` and reads the
 * segment-version vector out of its `metadata`, inside the signed body, before the loader will
 * run the payload. There is no signed manifest on the web, so
 * [Layer 5 ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md) rebuilds that one
 * gate as a **sidecar manifest**, fetched from the guest script's own origin and checked on the
 * main thread before the Worker is created.
 *
 * The ordering is the whole point, so it is worth saying plainly why. A guest built against a
 * dictionary version this client does not implement does not fail loudly: every widget it uses
 * from the unknown segment becomes a placeholder box, and the user gets a screen that is mostly
 * blank and entirely silent. Once the Worker exists the guest has already begun composing, and the
 * first batch is already on its way. So the check has to complete first, and the only way to
 * guarantee that in code rather than in a comment is for the Worker constructor to be unreachable
 * except through [WebDelivery.start].
 *
 * **What this does not give, stated rather than glossed.** HyperText Transfer Protocol Secure
 * (HTTPS) plus same-origin gives transport integrity and authenticity of the *server*. It does not
 * give the property Ed25519 manifest signing gives on mobile: that a compromised or substituted
 * server cannot make a client run code the signing key never approved. ADR-032 names the parity
 * path -- fetch the script, verify a hash carried in the sidecar, construct the Worker from a blob,
 * because `new Worker(url)` supports no Subresource Integrity attribute -- and leaves it unbuilt.
 * It is still unbuilt. [DogwoodWebManifest.guestScriptSha256] exists so that a deployment can
 * *carry* the hash today and a later change can start enforcing it without a manifest migration;
 * [WebDelivery] currently reads it only to report that it was ignored.
 */
package dev.dogwood.web

import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.browser.window
import org.w3c.dom.Worker
import org.w3c.fetch.Response

/**
 * The sidecar document, fetched from the guest script's origin.
 *
 * Deliberately small. Everything on it is something the host must decide *before* running any
 * guest code; anything the host could ask the guest for once it is running does not belong here.
 */
@Serializable
data class DogwoodWebManifest(
  /** The `postMessage` envelope revision the guest was built against. See [WorkerMessages]. */
  val envelopeRevision: Int = 0,
  /**
   * The guest script, relative to the manifest.
   *
   * Named by the manifest rather than by the caller so that a deployment can move or fingerprint
   * its guest bundle without a host release -- which is the same freedom the mobile manifest's
   * module list gives, for the same reason.
   */
  val guestScript: String = "",
  /**
   * The segment-version vector: what dictionary the payload was built against.
   *
   * The same map `DogwoodDictionary.segmentVersions` holds on the client -- `"androidx.layout"`,
   * `"dogwood.designsystem"`, the host-service segment -- and compared the same way.
   */
  val segmentVersions: Map<String, Int> = emptyMap(),
  /**
   * The guest script's SHA-256 digest, hexadecimal, if the deployment publishes one.
   *
   * **Read and reported, not enforced.** See this file's header.
   */
  val guestScriptSha256: String? = null,
)

/**
 * Why a guest was refused.
 *
 * Typed, because the three cases have genuinely different operational meanings: a fetch failure is
 * a deployment problem, a malformed manifest is a build problem, and a version refusal is skew --
 * the only one of the three that is *expected* to happen in a healthy fleet, when a payload rolls
 * out ahead of a client.
 */
sealed interface DeliveryRefusal {
  val message: String

  /** The sidecar could not be fetched, or the server answered with something that was not one. */
  data class ManifestUnavailable(override val message: String) : DeliveryRefusal

  /** The sidecar was fetched but could not be read as a manifest. */
  data class ManifestMalformed(override val message: String) : DeliveryRefusal

  /**
   * The payload names a dictionary this client does not implement.
   *
   * [unknownSegments] are segments the client has never heard of, and [tooNew] maps a segment to
   * the version the payload wants against the version the client has. Both are refusals; they are
   * separated because they point at different fixes -- the first says this client is missing a
   * whole subsystem, the second says it is behind on one.
   */
  data class DictionarySkew(
    val unknownSegments: List<String>,
    val tooNew: Map<String, Pair<Int, Int>>,
  ) : DeliveryRefusal {
    override val message: String get() = buildString {
      append("the payload was built against a dictionary this client does not implement")
      if (unknownSegments.isNotEmpty()) {
        append("; unknown segments: ")
        append(unknownSegments.joinToString(", "))
      }
      for ((segment, versions) in tooNew) {
        append("; segment '$segment' wants version ${versions.first}, this client has ")
        append(versions.second)
      }
    }
  }

  /** The guest speaks an envelope revision this host does not. */
  data class EnvelopeSkew(val guestRevision: Int) : DeliveryRefusal {
    override val message: String get() =
      "the payload's manifest declares envelope revision $guestRevision; this host speaks " +
        "${WorkerMessages.REVISION}"
  }
}

/** Either a live bridge to a running guest, or the reason there is not one. */
sealed interface DeliveryOutcome {
  data class Started(val bridge: WorkerBridge, val manifest: DogwoodWebManifest) : DeliveryOutcome
  data class Refused(val refusal: DeliveryRefusal, val manifest: DogwoodWebManifest?) :
    DeliveryOutcome
}

private val ManifestJson = Json { ignoreUnknownKeys = true }

/**
 * The web loader: check, then run.
 *
 * [clientSegmentVersions] is the client's own vector. It is a constructor parameter rather than a
 * reference to a global so that a host embedding a *different* dictionary -- which is the whole
 * premise of the registered-component tier -- does not have to fork this file.
 */
class WebDelivery(
  private val clientSegmentVersions: Map<String, Int>,
  /**
   * Where refusals are reported.
   *
   * Mandatory, with no default. ADR-032 says the check must "refuse to start the Worker on a
   * version this client does not implement, *and report it*", and a default no-op reporter would
   * make the second half optional in exactly the situation where nothing else on the page can tell
   * that anything happened.
   */
  private val report: (DeliveryRefusal) -> Unit,
) {
  /**
   * Fetches the sidecar, checks it, and only then constructs the Worker.
   *
   * [manifestUrl] is the sidecar's address; the guest script is resolved relative to it, so that
   * "the same origin as the guest script" is a property of the code rather than of the caller
   * remembering to pass two matching URLs.
   */
  suspend fun start(manifestUrl: String, listener: WorkerBridgeListener): DeliveryOutcome {
    val text = try {
      val response: Response = window.fetch(manifestUrl).await<Response>()
      if (!response.ok) {
        return refuse(
          DeliveryRefusal.ManifestUnavailable(
            "$manifestUrl answered ${response.status} ${response.statusText}",
          ),
          null,
        )
      }
      response.text().await<JsString>().toString()
    } catch (failure: Throwable) {
      return refuse(
        DeliveryRefusal.ManifestUnavailable("$manifestUrl could not be fetched: ${failure.message}"),
        null,
      )
    }

    val manifest = try {
      ManifestJson.decodeFromString<DogwoodWebManifest>(text)
    } catch (failure: Throwable) {
      return refuse(
        DeliveryRefusal.ManifestMalformed("$manifestUrl is not a Dogwood manifest: ${failure.message}"),
        null,
      )
    }

    if (manifest.guestScript.isEmpty()) {
      return refuse(DeliveryRefusal.ManifestMalformed("the manifest names no guest script"), manifest)
    }
    if (manifest.envelopeRevision != WorkerMessages.REVISION) {
      return refuse(DeliveryRefusal.EnvelopeSkew(manifest.envelopeRevision), manifest)
    }

    val skew = checkDictionary(manifest.segmentVersions)
    if (skew != null) return refuse(skew, manifest)

    // Everything above ran before this line, which is the requirement.
    val worker = Worker(resolveRelative(manifestUrl, manifest.guestScript))
    return DeliveryOutcome.Started(WorkerBridge(worker, listener), manifest)
  }

  /**
   * The comparison itself, extracted so it can be exercised without a network.
   *
   * A payload may legitimately name *fewer* segments than the client implements -- a guest that
   * uses no design-system component says nothing about that segment -- so absence from the payload
   * is never a refusal. The refusals are the other direction: a segment the client has never heard
   * of, and a segment the client is behind on.
   */
  fun checkDictionary(payloadSegments: Map<String, Int>): DeliveryRefusal.DictionarySkew? {
    val unknown = mutableListOf<String>()
    val tooNew = mutableMapOf<String, Pair<Int, Int>>()
    for ((segment, wanted) in payloadSegments) {
      val have = clientSegmentVersions[segment]
      when {
        have == null -> unknown += segment
        wanted > have -> tooNew[segment] = wanted to have
      }
    }
    return if (unknown.isEmpty() && tooNew.isEmpty()) {
      null
    } else {
      DeliveryRefusal.DictionarySkew(unknown, tooNew)
    }
  }

  private fun refuse(refusal: DeliveryRefusal, manifest: DogwoodWebManifest?): DeliveryOutcome {
    report(refusal)
    return DeliveryOutcome.Refused(refusal, manifest)
  }
}

/**
 * Resolves the guest script against the manifest's own address.
 *
 * `URL` does this correctly for absolute paths, protocol-relative addresses and `..` segments,
 * where string concatenation does not. It is reached through a `js(...)` body because the guest
 * script name is the one part of the manifest a deployment controls freely, and a hand-rolled join
 * would be the place a path escape hid.
 */
private fun resolveRelative(base: String, relative: String): String =
  js("new URL(relative, new URL(base, self.location.href)).href")
