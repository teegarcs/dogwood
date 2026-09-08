# Rules for the minified release build of this SAMPLE. Nothing here is engine policy: the engine's
# own dependencies (Zipline, kotlinx-serialization, Compose) carry their own consumer rules, which
# is why this file contains no rule about any of them -- that absence is a verified finding
# (`plans/adoption-audit.md` A1), not an omission.

# The one rule, and it serves the drills rather than the app. The instrumented conformance suite
# attaches to THIS build, and `AndroidJUnitRunner.onCreate` calls `androidx.tracing.Trace` in the
# app's process. The class rides the app's dependency graph, so the Android Gradle Plugin excludes
# it from the test APK ("the app provides it") -- and R8 strips it from the app because no app code
# reaches it. Each half is correct and the composition is a hole. Keeping the class here is the
# documented workaround; it is one small class in a build whose whole purpose is being drilled.
-keep class androidx.tracing.Trace { *; }

# Shrink and optimize, do not rename.
#
# The two halves of R8 carry different risks here, and only one of them is what this build exists
# to test. SHRINKING is what eats a generated serializer or a Zipline service adapter nothing
# visibly references -- the failure `plans/adoption-audit.md` A1 is about -- and it stays on.
# RENAMING breaks the *instrumentation harness*, not the app: the test APK compiles against
# original class names and trusts the app to provide them (`kotlin.LazyKt` was the one that
# surfaced), so an obfuscated app under test fails in `AndroidJUnitRunner.onCreate` before a single
# test runs. A product obfuscating its release build tests it the same way: shrink in the tested
# build, rename in the shipped one.
-dontobfuscate

# The Kotlin standard library stays whole, and the reason is the harness, not the app.
#
# The test APK carries no stdlib of its own -- the Android Gradle Plugin excludes anything the
# app's dependency graph provides -- so the harness and the drills' own Kotlin resolve stdlib
# classes from the app at runtime, *by name*. R8 inlines the app's call sites and removes the
# facades behind them (`kotlin.LazyKt` first, `kotlin.io.TextStreamsKt` next, one per run), so
# this was a facade-per-round tail with no principled end. One rule ends it.
#
# What this deliberately does NOT mask: the risks this build exists to drill live in Zipline's
# service adapters, kotlinx-serialization's generated serializers and Dogwood's own code -- none
# of which is under `kotlin.**`. Those still shrink, and a stripped one still fails the drills.
-keep class kotlin.** { *; }
# The same tail, one library over: the network-policy drill's `runBlocking` resolves
# `kotlinx.coroutines.BuildersKt` from the app. Same argument, same shape, same ending.
-keep class kotlinx.coroutines.** { *; }

# One engine facade, kept NARROWLY and the narrowness is the point. The network-policy drill calls
# top-level functions on `dev.dogwood.host.PlatformServicesKt` that the app itself never does, so
# R8 rightly removes the facade from the app and the drill then cannot resolve it. Keeping all of
# `dev.dogwood.**` instead would quietly defeat this build's purpose: the drills exist to catch
# engine code being WRONGLY stripped, and a blanket keep means nothing is ever stripped to catch.
-keep class dev.dogwood.host.PlatformServicesKt { *; }
# Constructors only, engine-wide, and the restriction to constructors is what keeps this honest.
# The drills build engine types through default-argument synthetic constructors the app never
# emits a call to -- `OkHttpNetwork` first, `HttpRequest` next, one per run -- so R8 removes those
# overloads alone and the tail had no principled end. Keeping ONLY `<init>` ends it while leaving
# everything the drill exists to catch strippable: serializers, service adapters, methods and
# fields of engine classes all still shrink, and a wrongly stripped one still fails a claim.
-keepclassmembers class dev.dogwood.** { <init>(...); }

# The wire types stay whole, because the drill treats them as data. `NetworkPolicyConformanceTest`
# constructs an `HttpRequest` and reads an `HttpResponse` -- members the app itself never touches,
# which R8 rightly strips one accessor per run (`getBody()` was the next). Member-stripping of a
# plain data class the consumer does not read is R8 working correctly, not the defect class this
# build drills for: the reflective surfaces (serializers, Zipline adapters) are kept by their own
# libraries' consumer rules, and the engine's BEHAVIOURAL code -- bindings, tree, delivery, every
# line thirteen claims exercise -- remains fully strippable above.
-keep class dev.dogwood.protocol.** { *; }
