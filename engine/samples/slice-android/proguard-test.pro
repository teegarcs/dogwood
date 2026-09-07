# Rules for the INSTRUMENTED TEST APK only (testProguardFiles), not for anything that ships.
#
# The drills run against the minified release build (see the build file), so the test APK is
# minified alongside it -- and androidx.test references ErrorProne's annotations, which are
# compile-only and absent at runtime by design. R8 treats the dangling reference as an error
# without these. Annotation-only, so -dontwarn is a statement of fact rather than a suppression.
-dontwarn com.google.errorprone.annotations.**
