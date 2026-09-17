/*
 * The fixture build for the guest classpath check.
 *
 * A whole Gradle build rather than a unit test with a hand-built graph, because the thing under
 * test is what Gradle's *resolution* produces -- a hand-built graph would be the author's belief
 * about resolution, which is the second thing that can be wrong. `GuestClasspathCheckTest` copies
 * this directory to a temporary location and runs it; it can also be run by hand, which is how the
 * failure was watched before it was believed. See that test's header.
 */
rootProject.name = "guest-classpath-fixture"
