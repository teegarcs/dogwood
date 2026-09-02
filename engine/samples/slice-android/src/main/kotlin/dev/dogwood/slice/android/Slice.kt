/*
 * Project Dogwood -- what every host in this sample agrees on.
 *
 * Two activities load the same payload: `SliceActivity`, the diagnostic harness, and
 * `TabsActivity`, the Path B reference. The signing key and the payload's address belong to
 * neither of them.
 *
 * The trust anchor especially. Duplicating it would mean rotating the signing key required
 * remembering to edit two files, and the failure mode of forgetting is a host that silently keeps
 * trusting a retired key -- exactly the coupling the key's own documentation says must stay
 * deliberate.
 */
package dev.dogwood.slice.android

/**
 * The public half of the key that signs the guest, compiled into the host.
 *
 * A public key is meant to be public; this is the anchor the whole delivery path trusts.
 * Changing the signing key in `samples/slice-guest/build.gradle.kts` requires changing this with
 * it, and that coupling is what makes key rotation a deliberate operation rather than an
 * accident.
 */
internal val TRUSTED_KEYS = mapOf(
  "dogwood-development" to "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102",
)

/**
 * On the Android emulator, 10.0.2.2 is the development machine. Serve the guest with
 * `./gradlew :samples:slice-guest:serveProductionWebpackZipline`.
 */
internal const val DEV_SERVER = "http://10.0.2.2:8080"
internal const val MANIFEST_URL = "$DEV_SERVER/manifest.zipline.json"
