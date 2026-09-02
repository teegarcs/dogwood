/*
 * Project Dogwood -- what a Compose Multiplatform web page actually weighs.
 *
 * roadmap.md Phase 5 gates the Web host on this number and, until now, quoted a community figure
 * for it: "~8 MB uncompressed / ~3 MB compressed, page-weight viability is unproven". A figure
 * nobody here measured cannot gate anything, so this build measures it.
 *
 * Both modules render the same trivial screen. They differ only in what they link, so the
 * difference between their distributions is attributable to that and not to anything else.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.composeMultiplatform) apply false
}
