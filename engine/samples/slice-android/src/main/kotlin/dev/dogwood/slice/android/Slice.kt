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
 * The public halves of the keys that sign the guest, compiled into the host.
 *
 * A public key is meant to be public; these are the anchors the whole delivery path trusts.
 * Changing the signing keys in `samples/slice-guest/build.gradle.kts` requires changing this with
 * them, and that coupling is what makes key rotation a deliberate operation rather than an
 * accident.
 *
 * **Two keys, because this host has been rolled forward.** That is the middle step of a rotation
 * and the state a real fleet spends weeks in: the manifest carries both signatures, clients built
 * before the change verify with the first, clients built after verify with either, and only once
 * enough of the fleet holds the second can the first be retired. `KeyRotationTest` rehearses the
 * whole sequence, including what retiring the old key does to a client that never updated.
 *
 * Zipline verifies against the **first key name it recognises** and skips the rest, so a client
 * holding both checks whichever the manifest lists first -- here, the old one. Trusting the new
 * key is not the same as using it.
 */
internal val TRUSTED_KEYS = dev.dogwood.protocol.DogwoodTrust.DEVELOPMENT_KEYS

/**
 * On the Android emulator, 10.0.2.2 is the development machine. Serve the guest with
 * `./gradlew :samples:slice-guest:serveProductionWebpackZipline`.
 */
internal const val DEV_SERVER = "http://10.0.2.2:8080"
internal const val MANIFEST_URL = "$DEV_SERVER/manifest.zipline.json"
