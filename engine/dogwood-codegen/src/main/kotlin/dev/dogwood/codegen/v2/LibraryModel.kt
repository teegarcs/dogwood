/*
 * Project Dogwood -- generator v2's parsed model of a library surface.
 *
 * Generator v1 reads a *surface*: a file an adopter writes, with signatures curated to cross the
 * boundary. Generator v2 reads a *library*: the Compose Multiplatform sources the host is compiled
 * against, which nobody curated for this purpose. The model therefore records more than v1's --
 * which file a function came from and what that file imports, because the host binding is emitted
 * with those imports; every opt-in marker, because the binding must opt in to call; and the
 * default expression's exact text, because the binding passes it verbatim when the guest sends
 * nothing (plans/generator-v2.md, D-D).
 */
package dev.dogwood.codegen.v2

import kotlinx.serialization.Serializable

/** One parameter as the library declared it. Text, not resolved types; see the plan's §0. */
@Serializable
data class LibraryParameter(
  val name: String,
  /** Declared type text with whitespace normalised, e.g. `@Composable RowScope.() -> Unit`. */
  val type: String,
  /** The default's source text, verbatim, or null when the parameter is required. */
  val defaultText: String?,
  val isVararg: Boolean = false,
)

/** One public, uppercase `@Composable` function from one library file. */
@Serializable
data class LibraryComposable(
  val name: String,
  /** Path of the source file relative to the module's `commonMain/`, for deterministic ordering. */
  val file: String,
  val packageName: String,
  /** The file's import directives, verbatim (`import a.b.C`, `import a.b.C as D`, `import a.b.*`). */
  val imports: List<String>,
  val parameters: List<LibraryParameter>,
  val deprecated: Boolean,
  /** Opt-in markers this function needs, by simple name: from `@OptIn(...)`, `@file:OptIn(...)` and direct `@Experimental*` annotations. */
  val optIns: Set<String>,
  val isExpect: Boolean,
  val isInline: Boolean,
  val typeParameters: List<String>,
  /** The extension receiver's type text, or null for a plain function. */
  val receiver: String?,
)

/** One module's surface: its composables, in file order then declaration order. */
@Serializable
data class LibrarySurface(
  val module: String,
  val composables: List<LibraryComposable>,
  /**
   * Uppercase top-level names the module declares `internal`: objects, classes, constants. A default
   * expression that names one cannot be copied into a binding outside the module, and knowing the
   * set at parse time turns "generate, compile, refuse" into a refusal with the name in it.
   * Lowercase internals are left out on purpose: `value` and `padding` would flag half the library.
   */
  val internalNames: Set<String> = emptySet(),
  /** Opt-in markers the module declares publicly; a binding file opts in to all of them. */
  val publicMarkers: Set<String> = emptySet(),
  /** Opt-in markers the module declares `internal`; a function requiring one cannot be called from outside. */
  val internalMarkers: Set<String> = emptySet(),
)
