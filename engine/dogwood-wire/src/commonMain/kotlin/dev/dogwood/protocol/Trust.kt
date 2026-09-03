/*
 * Project Dogwood -- the development trust anchor, declared once.
 *
 * These are the public halves of the keys that sign the sample payload, and they belong in one
 * place for the same reason every other shared constant in this module does: three hand-typed
 * copies of a security-relevant map is three chances to rotate two of them.
 *
 * They were byte-identical across Android, desktop and iOS when this was written, so nothing was
 * broken yet -- which is exactly when to fix it. `KeyRotationTest` rehearses a rotation as three
 * steps (ship both signatures, roll clients forward, retire the old key), and the middle step is
 * the one that goes wrong quietly: a fleet updated on two platforms and forgotten on the third
 * keeps verifying until the old key is retired, and then one platform stops accepting updates with
 * no error anyone sees.
 *
 * **These are throwaway development keys**, committed on purpose so the samples build for anyone
 * who clones this. They sign nothing anyone should trust. A real signing key never lives in a
 * repository: pass `-PdogwoodSigningKey` and `-PdogwoodRotationKey`, and replace the public halves
 * here to match.
 */
package dev.dogwood.protocol

object DogwoodTrust {
  /**
   * The key the samples were originally signed with.
   *
   * Still first in the manifest, and therefore still the one a client holding both actually
   * verifies against -- Zipline stops at the first key name it recognises, so trusting the new key
   * is not the same as using it.
   */
  const val DEVELOPMENT = "dogwood-development"

  /** The key being rotated to. */
  const val DEVELOPMENT_2 = "dogwood-development-2"

  /** What a sample host compiles in: both, because the samples sit mid-rotation deliberately. */
  val DEVELOPMENT_KEYS: Map<String, String> = mapOf(
    DEVELOPMENT to "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102",
    DEVELOPMENT_2 to "64fcb07226f6b538ec7d09510f9a5073aeb43a50916cfc625761cc9b9a99b097",
  )
}
