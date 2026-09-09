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
 * **What this gives and what it still does not, stated rather than glossed.** HyperText Transfer
 * Protocol Secure (HTTPS) plus same-origin gives transport integrity and authenticity of the
 * *server*. On its own it does not give the property Ed25519 manifest signing gives on mobile: that
 * a compromised or substituted server cannot make a client run code the signing key never approved.
 *
 * Half of that is now closed. **The sidecar itself is signed** -- a detached Ed25519 signature over
 * the manifest's exact bytes, verified against keys the host passes in, before the document is
 * parsed (ADR-062, `SidecarSignature.kt`). Everything the sidecar *decides* is therefore as
 * trustworthy as the signing key: the dictionary vector, the release identity, and the kill switch,
 * which until now was only as trustworthy as its origin and said so in its own comment.
 *
 * The other half is not. **The guest script is still fetched by the browser without an integrity
 * check.** A signed sidecar naming a script does not stop a server from serving different bytes at
 * that address. ADR-032 names the parity path -- fetch the script, verify a hash carried in the
 * sidecar, construct the Worker from a blob, because `new Worker(url)` supports no Subresource
 * Integrity attribute -- and it is still unbuilt.
 * [DogwoodWebManifest.guestScriptSha256] exists so that a deployment can *carry* the hash today and
 * a later change can start enforcing it without a manifest migration; [WebDelivery] currently reads
 * it only to report that it was ignored. What signing the sidecar buys is that the hash, once
 * enforced, will arrive on a document an attacker cannot rewrite -- which is the order these two
 * steps have to be taken in.
 */
package dev.dogwood.web

import dev.dogwood.host.checkDeclaredDictionary
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.browser.window
import org.w3c.dom.Worker
import org.w3c.fetch.Response
import dev.dogwood.host.ReleaseVerdict
import dev.dogwood.host.ReleaseGuard

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
   * What to call this release in the guard's memory.
   *
   * Optional, and it falls back to [guestScriptSha256] and then to [guestScript] -- a deployment
   * that fingerprints its bundle already has a perfectly good identity and should not be made to
   * repeat it. What the guard needs is only that the string **changes when the payload changes**
   * and is stable otherwise; a deployment serving one filename forever gets no crash-loop
   * protection, which is a property of that deployment rather than of this field.
   */
  val releaseVersion: String = "",
  /**
   * The publisher's stop switch, honoured before the Worker is created.
   *
   * **As strong as the mobile kill switch when the host passes trusted keys, and no stronger than
   * its origin when it does not.** On mobile the switch rides Zipline's *signed* manifest metadata,
   * so an attacker who cannot sign cannot set it. This sidecar used to be unsigned, and this
   * comment used to say the web switch was only as trustworthy as the origin serving it. Since
   * ADR-062 the sidecar carries a detached Ed25519 signature and `WebDelivery` refuses a document
   * that does not verify — so a host that passes `trustedPublicKeys` has the mobile guarantee here
   * too. A host that passes `emptyMap()` has made the older, weaker choice explicitly.
   */
  val disabled: Boolean = false,
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
   * The sidecar's detached Ed25519 signature was missing, unrecognised, or did not verify.
   *
   * Separate from [ManifestMalformed] because it means something entirely different
   * operationally. A malformed manifest is a build mistake somebody made; an unverifiable one is
   * either a deployment that forgot to publish its signature or a server serving bytes the signing
   * key never approved, and only one of those is a bad afternoon.
   */
  data class SignatureRefused(override val message: String) : DeliveryRefusal

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
  /**
   * The release guard said no: quarantined after repeated failures, or stopped by the publisher.
   *
   * A refusal, not a failure. The page shows its own screen -- there is no previous guest to fall
   * back to on a fresh load, and falling back on a *reload* would run the payload the guard just
   * quarantined.
   */
  data class ReleaseRefused(
    val version: String,
    val reason: String,
    val fallbackVersion: String?,
  ) : DeliveryRefusal {
    override val message: String
      get() = "release $version refused: $reason" +
        (fallbackVersion?.let { " (last good: $it)" } ?: "")
  }

  data class EnvelopeSkew(val guestRevision: Int) : DeliveryRefusal {
    override val message: String get() =
      "the payload's manifest declares envelope revision $guestRevision; this host speaks " +
        "${WorkerMessages.REVISION}"
  }
}

/** Either a live bridge to a running guest, or the reason there is not one. */
/**
 * The identity the guard remembers a release by.
 *
 * Named, rather than inlined, because the fallback order is a decision: an explicit version if the
 * deployment states one, then the content digest if it publishes one, then the script's own name.
 */
internal fun DogwoodWebManifest.releaseIdentity(): String = when {
  releaseVersion.isNotEmpty() -> releaseVersion
  !guestScriptSha256.isNullOrEmpty() -> guestScriptSha256!!
  else -> guestScript
}

sealed interface DeliveryOutcome {
  data class Started(
    val bridge: WorkerBridge,
    val manifest: DogwoodWebManifest,
    /** What the guard knows this release as; hand it back on [ReleaseGuard.succeeded]. */
    val version: String = "",
  ) : DeliveryOutcome
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
  /**
   * Surviving a bad publish, on the fourth client.
   *
   * No default -- the argument ADR-058 made when it removed `DogwoodShell`'s: a payload is
   * replaceable over the air without review, so protection that must be asked for is protection
   * most hosts do not have. `null` is accepted and is a decision somebody wrote. This was the last
   * unguarded client (`plans/adoption-audit.md` A3's recorded remainder).
   */
  private val releaseGuard: ReleaseGuard?,
  /**
   * Key name to Ed25519 public key, hexadecimal — what this client will accept a sidecar from.
   *
   * **No default, and `emptyMap()` is the written way to say "unsigned".** The ADR-058 pattern: a
   * protection that must be asked for is a protection most hosts do not have, and this one was
   * missing on the web for the whole life of the profile. Passing an empty map is a decision
   * somebody made, visible at the call site, and it means exactly what it says — the sidecar is
   * believed on the strength of its origin alone, which is what every web host did before
   * ADR-062. Passing keys means a sidecar that does not verify does not run.
   *
   * More than one entry is how rotation works; see `SidecarSignature.kt` for the rule, which is
   * Zipline's.
   */
  private val trustedPublicKeys: Map<String, String>,
  /**
   * Where the detached signature lives, given the manifest's address.
   *
   * A function rather than a fixed suffix because a deployment that fingerprints its manifest
   * (`manifest.a1b2c3.json`) has to be able to say where the matching signature went, and because a
   * host serving from a content-delivery network may want the two on different paths. The default
   * is the obvious one and covers every deployment that has not thought about it.
   */
  private val signatureUrl: (String) -> String = { "$it.sig" },
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

    /*
     * The signature, before the manifest is believed -- which means before it is parsed, not merely
     * before the Worker is created.
     *
     * Parsing first and verifying after would run this client's JSON parser over bytes of unproven
     * origin, and every field read out of them -- the guest script's address above all -- would be
     * a value an unverified document chose. The whole point of a signature is that nothing
     * downstream of it has to be careful.
     *
     * Verified over `text`, the same string that is parsed below. A verifier that fetched the
     * document a second time would verify one copy and use another, and a server that answered
     * differently the second time would defeat it completely.
     */
    if (trustedPublicKeys.isNotEmpty()) {
      val refusal = verifySidecar(manifestUrl, text)
      if (refusal != null) return refuse(refusal, null)
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

    /*
     * The release verdict, and it sits here for the same reason it sits before `start` on mobile:
     * fetching a payload is not the dangerous part, running it is, and everything above this line
     * has fetched without executing a byte of guest code.
     */
    val version = manifest.releaseIdentity()
    val guard = releaseGuard
    if (guard != null) {
      when (val verdict = guard.verdict(version, manifest.disabled)) {
        is ReleaseVerdict.Refused ->
          return refuse(
            DeliveryRefusal.ReleaseRefused(version, verdict.reason, verdict.fallbackVersion),
            manifest,
          )
        ReleaseVerdict.Allowed -> Unit
      }
      // Persisted BEFORE the Worker is created. An attempt counted in memory is erased by the
      // crash counting it -- and on this platform "crash" includes the tab being closed on a page
      // that hangs, which is exactly the loop a user reloads their way into.
      guard.starting(version)
    }

    // Everything above ran before this line, which is the requirement.
    val worker = Worker(resolveRelative(manifestUrl, manifest.guestScript))
    return DeliveryOutcome.Started(WorkerBridge(worker, listener), manifest, version)
  }

  /**
   * The comparison itself, exposed so it can be exercised without a network.
   *
   * **The rule lives in `dev.dogwood.host.checkDeclaredDictionary` and this only adapts it.** It
   * used to be nine lines here and nine identical lines on the mobile path, which is the shape a
   * divergence hides in: a payload accepted on one client and refused on another, found by a user
   * rather than a test. What remains here is the mapping into this profile's refusal type, because
   * the Web host reports refusals through `DeliveryRefusal` and a mobile host through
   * `GuardedRelease` — different surfaces for the same finding.
   *
   * The rule, restated once so a reader here need not go and look: a payload may legitimately name
   * *fewer* segments than the client implements — a guest that uses no design-system component says
   * nothing about that segment — so absence from the payload is never a refusal. The refusals are
   * the other direction: a segment the client has never heard of, and a segment the client is
   * behind on.
   */
  fun checkDictionary(payloadSegments: Map<String, Int>): DeliveryRefusal.DictionarySkew? {
    val skew = checkDeclaredDictionary(payloadSegments, clientSegmentVersions) ?: return null
    return DeliveryRefusal.DictionarySkew(skew.unknownSegments, skew.outdatedSegments)
  }

  /**
   * Fetches the detached signature and checks it, returning null when the sidecar may be believed.
   *
   * A missing signature document is a refusal rather than a pass. That is the only reading that
   * makes the check worth having: if absence meant "unsigned, carry on", an attacker who can
   * replace the manifest can also delete the signature beside it, and the protection evaporates at
   * exactly the moment it is needed. A host that genuinely wants unsigned sidecars says so by
   * passing no keys.
   */
  private suspend fun verifySidecar(manifestUrl: String, text: String): DeliveryRefusal? {
    val url = signatureUrl(manifestUrl)
    val document = try {
      val response: Response = window.fetch(url).await<Response>()
      if (!response.ok) {
        return DeliveryRefusal.SignatureRefused(
          "$url answered ${response.status} ${response.statusText}; this client requires a signed " +
            "sidecar because it was given trusted keys",
        )
      }
      response.text().await<JsString>().toString()
    } catch (failure: Throwable) {
      return DeliveryRefusal.SignatureRefused("$url could not be fetched: ${failure.message}")
    }

    val signatures = parseDetachedSignatures(document)
    if (signatures.isEmpty()) {
      return DeliveryRefusal.SignatureRefused("$url carries no `keyName hexSignature` line")
    }

    // `encodeToByteArray` is UTF-8, which is what the signer signed: the manifest is a JSON
    // document served as UTF-8, and `Response.text()` has already decoded it. Re-encoding gives back
    // the same bytes for any document that was valid UTF-8 to begin with, and one that was not
    // could not have been parsed as a manifest either.
    return when (val verdict = verifyDetachedSignature(trustedPublicKeys, signatures, text.encodeToByteArray())) {
      is SignatureVerdict.Verified -> null
      is SignatureVerdict.Invalid -> DeliveryRefusal.SignatureRefused(
        "the sidecar's signature from `${verdict.keyName}` does not verify over these bytes",
      )
      is SignatureVerdict.NoRecognisedKey -> DeliveryRefusal.SignatureRefused(
        "the sidecar is signed by ${verdict.offered} and this client trusts ${verdict.trusted}",
      )
      is SignatureVerdict.Unavailable -> DeliveryRefusal.SignatureRefused(
        "the signature could not be checked: ${verdict.reason}",
      )
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
