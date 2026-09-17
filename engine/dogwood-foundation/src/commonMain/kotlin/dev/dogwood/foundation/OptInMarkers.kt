/*
 * Project Dogwood -- three names the generated bindings in this module use and cannot resolve, and
 * why they are declared here rather than fixed in the generator.
 *
 * A generated host file opts in file-wide to every public opt-in marker the library declares, and
 * it writes the marker by its *simple* name: `@file:OptIn(ExperimentalFoundationApi::class)`. The
 * only thing that makes such a name resolve is the emitter's `import <the library file's package>.*`.
 * For Material 3 that is enough, because Material 3 declares its markers in the same package as the
 * components that carry them. **The three libraries in this module do not.**
 * `ExperimentalFoundationApi` lives in `androidx.compose.foundation` while `BasicText` lives in
 * `androidx.compose.foundation.text`; `ExperimentalVelocityTrackerApi` lives four packages away from
 * `Dialog`. The first compile of these tiers failed on eight unresolved references, every one of
 * them an annotation marker and none of them a component.
 *
 * The real fix is in the emitter -- it should import the markers it names -- and it is reported
 * rather than made here, because `Generate.kt` is being changed for M5 at the same time.
 *
 * So: markers of this module's own, with the simple names the generated files use. A declaration in
 * the bindings' own package beats a star import, so the generated `@file:OptIn` resolves to these
 * and opts into these, which is a no-op. **The real opt-in comes from the module's build file**,
 * which passes the three libraries' actual markers to the compiler with `optIn` -- so the bindings
 * are opted in to the same API surface, by a different route.
 *
 * A type alias was tried first and Kotlin refuses it: "this class can only be used as an annotation
 * or as an argument to '@OptIn'". That refusal is why these are declarations rather than aliases.
 *
 * **Delete this file, and the `optIn` block in the build file, when the emitter imports its own
 * markers.** Nothing will complain if they outlive the fix, so this sentence is the only notice.
 */
package dev.dogwood.foundation

@RequiresOptIn("A stand-in for androidx.compose.foundation.ExperimentalFoundationApi; see this file.")
internal annotation class ExperimentalFoundationApi

@RequiresOptIn("A stand-in for androidx.compose.foundation.gestures.ExperimentalTapGestureDetectorBehaviorApi.")
internal annotation class ExperimentalTapGestureDetectorBehaviorApi

@RequiresOptIn("A stand-in for androidx.compose.ui.input.pointer.util.ExperimentalVelocityTrackerApi.")
internal annotation class ExperimentalVelocityTrackerApi
