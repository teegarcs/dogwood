/*
 * Project Dogwood -- the authoring check Layer 1 has always required.
 *
 * `specs/layer-1-authoring.md` carries two **hard rejection obligations**, and states why the first
 * one cannot wait for good intentions:
 *
 *   > The failure mode of *not* rejecting them is the dangerous one: the guest has a working frame
 *   > clock, so these APIs would compile and run -- silently ticking the boundary every frame,
 *   > which is exactly what the Layer 4 invariant forbids.
 *
 * That is the whole argument for a check rather than a rule in a document. Nothing about
 * `animateFloatAsState` in a guest fails: it compiles, it runs, it animates, and it crosses the
 * boundary sixty times a second for as long as it is on screen. The screen looks right. The bill
 * arrives as a battery complaint months later, from a user, about a screen nobody changed.
 *
 * ## What it can and cannot see
 *
 * **Best-effort by construction**, and Layer 1 says so: a call assembled at runtime, a function
 * aliased behind another name, or an API reached through reflection is invisible to a source scan.
 * It catches the common case -- a directly-named forbidden API -- and it must never be described as
 * a guarantee. What makes it worth having anyway is that the common case is *the* case: nobody
 * reaches for `rememberInfiniteTransition` by accident through an alias.
 *
 * ## Why comments and strings are stripped first
 *
 * The first thing a naive scanner does is fail on the comment explaining why the API is forbidden.
 * `AboutScreen.kt` contains the sentence "Compose's own `animateFloatAsState` does..." in a comment
 * about this very rule, and a checker that rejected the documentation of its own rule would be
 * uninstalled the same afternoon.
 *
 * ## The controlled text fields, and why one of them is conditional
 *
 * `specs/layer-5-host.md` excludes `BasicTextField`, `TextField` and `OutlinedTextField` **by
 * name**, and generator v2's classifier excludes that family and the search bars along with it: a
 * controlled text field asks the guest what the text should be after every keystroke, which is a
 * boundary crossing per character racing the next keystroke.
 * `adrs/layer-5/ADR-019-text-input.md` replaced them with a versioned `TextInput`
 * -- the host owns the buffer, the guest holds a version-stamped mirror -- so the rejection is
 * permanent rather than a gap waiting to be filled.
 *
 * These do not compile, unlike the animation APIs: nothing on a guest's classpath declares
 * `OutlinedTextField`. What a guest author gets today is "unresolved reference", which names no
 * replacement and reads like a missing dependency. That is what this half of the list is for --
 * not to stop a build that would otherwise succeed, but to answer the question the compiler
 * refuses to.
 *
 * **`TextField` is the exception, and it is not on the unconditional list.**
 * `dev.dogwood.compose.TextField(state = ...)` exists, is the supported spelling, wraps `TextInput`
 * and hides the version stamping -- `ExploreScreen.kt` and `AboutScreen.kt` both call it. Rejecting
 * the name outright would reject the engine's own guests for using the API the engine recommends,
 * which is the "cries wolf" failure ADR-050 warns about, in its most embarrassing form. So the
 * *controlled* form is what is rejected: a `TextField(...)` call whose argument list carries
 * `onValueChange`, which Compose's overloads all have and Dogwood's wrapper does not.
 */
package dev.dogwood.codegen.guest

/** One thing a guest may not call, and what to do instead. */
data class ForbiddenApi(
  val name: String,
  val because: String,
  val instead: String,
)

/** Where a forbidden call was found. */
data class GuestViolation(
  val file: String,
  val line: Int,
  val api: ForbiddenApi,
) {
  override fun toString(): String =
    "$file:$line  ${api.name} — ${api.because}\n      instead: ${api.instead}"
}

/**
 * The rejections Layer 1 obliges, and nothing beyond them.
 *
 * Deliberately a short, named list rather than a pattern over a package: a check that rejected
 * everything from `androidx.compose.animation` would also reject a guest that merely *named* one of
 * its enum values, and a check that cries wolf is one people learn to suppress.
 */
val FORBIDDEN_GUEST_APIS: List<ForbiddenApi> = listOf(
  // Animation state. Permanent, not temporary -- ADR-020 is what makes that acceptable.
  ForbiddenApi(
    "animateFloatAsState",
    "per-frame state in the guest: it ticks the boundary every frame it animates",
    "Modifier.alpha(animate(target, spec)) — declare a target, the host runs the frames",
  ),
  ForbiddenApi("animateDpAsState", "per-frame state in the guest", "Modifier.height(animateDp(target, spec))"),
  ForbiddenApi("animateColorAsState", "per-frame state in the guest", "Color.animate(spec)"),
  ForbiddenApi("animateIntAsState", "per-frame state in the guest", "declare a target; see ADR-020"),
  ForbiddenApi("animateValueAsState", "per-frame state in the guest", "declare a target; see ADR-020"),
  ForbiddenApi(
    "rememberInfiniteTransition",
    "per-frame state in the guest, forever, whether or not anything is watching",
    "oscillate(from, to, spec) — one crossing, and the host repeats it",
  ),
  ForbiddenApi("updateTransition", "per-frame state in the guest", "declare a target; see ADR-020"),
  ForbiddenApi("Animatable", "per-frame state in the guest", "declare a target; see ADR-020"),
  ForbiddenApi(
    "withFrameNanos",
    "a guest frame loop is per-frame state by definition",
    "declare what should change and let the host run the frames",
  ),
  ForbiddenApi(
    "withFrameMillis",
    "a guest frame loop is per-frame state by definition",
    "declare what should change and let the host run the frames",
  ),

  // Resource loaders. There are no resources in the sandbox: no file system, no stable host
  // resource identifiers, and a payload that ships months apart from the host that renders it.
  ForbiddenApi("painterResource", "the sandbox has no resources", "AsyncImage(url) — see ADR-017"),
  ForbiddenApi("imageResource", "the sandbox has no resources", "AsyncImage(url) — see ADR-017"),
  ForbiddenApi("stringResource", "the sandbox has no resources", "payload-carried string tables — see ADR-017"),
  ForbiddenApi("vectorResource", "the sandbox has no resources", "Icon(name) — see ADR-017"),

  // Controlled text fields. Excluded by name in `specs/layer-5-host.md` and in generator v2's
  // classifier, and replaced by `TextInput` in ADR-019. `TextField` is deliberately absent from
  // this list: `dev.dogwood.compose.TextField(state)` is the supported spelling. Its controlled
  // overload is caught by `findControlledTextFieldCalls` below instead.
  ForbiddenApi(
    "OutlinedTextField",
    "a controlled text field round-trips every keystroke across a latent boundary",
    "TextInput, or the TextField(state = rememberTextFieldState()) wrapper over it — the host owns\n" +
      "      the buffer and stamps every edit with its count; see ADR-019",
  ),
  ForbiddenApi(
    "BasicTextField",
    "a controlled text field round-trips every keystroke across a latent boundary",
    "TextInput, or the TextField(state = rememberTextFieldState()) wrapper over it — the host owns\n" +
      "      the buffer and stamps every edit with its count; see ADR-019",
  ),
  ForbiddenApi(
    "SecureTextField",
    "a controlled text field round-trips every keystroke across a latent boundary",
    "TextInput, via TextField(state = rememberTextFieldState(sensitive = true), keyboard =\n" +
      "      Keyboards.PASSWORD); see ADR-019",
  ),
  ForbiddenApi(
    "BasicSecureTextField",
    "a controlled text field round-trips every keystroke across a latent boundary",
    "TextInput, via TextField(state = rememberTextFieldState(sensitive = true), keyboard =\n" +
      "      Keyboards.PASSWORD); see ADR-019",
  ),
  ForbiddenApi(
    "OutlinedSecureTextField",
    "a controlled text field round-trips every keystroke across a latent boundary",
    "TextInput, via TextField(state = rememberTextFieldState(sensitive = true), keyboard =\n" +
      "      Keyboards.PASSWORD); see ADR-019",
  ),
  ForbiddenApi(
    "CoreTextField",
    "a controlled text field round-trips every keystroke across a latent boundary",
    "TextInput, or the TextField(state = rememberTextFieldState()) wrapper over it — the host owns\n" +
      "      the buffer and stamps every edit with its count; see ADR-019",
  ),
  // The search bars are the same component wearing a hat: each one contains a controlled input
  // field, which is why the classifier excludes them in the same set.
  ForbiddenApi(
    "SearchBar",
    "its input field is a controlled text field, and its text would cross per keystroke",
    "TextInput inside your own layout, via TextField(state = rememberTextFieldState());\n" +
      "      see ADR-019",
  ),
  ForbiddenApi(
    "DockedSearchBar",
    "its input field is a controlled text field, and its text would cross per keystroke",
    "TextInput inside your own layout, via TextField(state = rememberTextFieldState());\n" +
      "      see ADR-019",
  ),
  ForbiddenApi(
    "ExpandedFullScreenSearchBar",
    "its input field is a controlled text field, and its text would cross per keystroke",
    "TextInput inside your own layout, via TextField(state = rememberTextFieldState());\n" +
      "      see ADR-019",
  ),
  ForbiddenApi(
    "ExpandedDockedSearchBar",
    "its input field is a controlled text field, and its text would cross per keystroke",
    "TextInput inside your own layout, via TextField(state = rememberTextFieldState());\n" +
      "      see ADR-019",
  ),
  ForbiddenApi(
    "SearchBarInputField",
    "its input field is a controlled text field, and its text would cross per keystroke",
    "TextInput inside your own layout, via TextField(state = rememberTextFieldState());\n" +
      "      see ADR-019",
  ),
)

/**
 * The controlled `TextField` overload, which shares its name with the one a guest should call.
 *
 * Reported separately from [FORBIDDEN_GUEST_APIS] because it is not a name: it is a name plus an
 * argument. `TextField(state = ...)` is `dev.dogwood.compose.TextField`, the supported wrapper
 * around `TextInput`; `TextField(value = ..., onValueChange = ...)` is Compose's controlled
 * overload, which no guest classpath declares and which ADR-019 forbids on purpose.
 */
val CONTROLLED_TEXT_FIELD = ForbiddenApi(
  "TextField(onValueChange = ...)",
  "the controlled overload: the guest would be asked for the text after every keystroke",
  "TextInput, or the TextField(state = rememberTextFieldState()) wrapper over it — the host owns\n" +
    "      the buffer and stamps every edit with its count; see ADR-019",
)

/**
 * Removes comments and string literals, preserving line numbers.
 *
 * Every character it removes becomes a space rather than disappearing, so a violation's reported
 * line is the line it is actually on. A scanner that collapsed the text would report the right
 * problem at the wrong place, which is worse than not reporting the line at all.
 */
internal fun stripCommentsAndStrings(source: String): String {
  val out = StringBuilder(source.length)
  var i = 0
  fun blankTo(end: Int) {
    while (i < end && i < source.length) {
      out.append(if (source[i] == '\n') '\n' else ' ')
      i++
    }
  }
  while (i < source.length) {
    val rest = source.length - i
    when {
      rest >= 2 && source.startsWith("//", i) -> {
        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
        blankTo(end)
      }
      rest >= 2 && source.startsWith("/*", i) -> {
        val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
        blankTo(end)
      }
      rest >= 3 && source.startsWith("\"\"\"", i) -> {
        val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
        blankTo(end)
      }
      source[i] == '"' -> {
        var j = i + 1
        while (j < source.length && source[j] != '"') {
          if (source[j] == '\\') j++
          j++
        }
        blankTo(minOf(j + 1, source.length))
      }
      else -> {
        out.append(source[i])
        i++
      }
    }
  }
  return out.toString()
}

/**
 * Finds the *controlled* `TextField` overload, which a name alone cannot distinguish.
 *
 * `TextField(state = ...)` is `dev.dogwood.compose.TextField` and is what a guest should write.
 * Compose's overloads take `value` and `onValueChange`; the wrapper has neither parameter. So the
 * marker is `onValueChange` **anywhere in the call's own argument list**, which means balancing
 * parentheses rather than reading a line: a real call spans five or six lines, and the line that
 * says `TextField(` is never the line that says `onValueChange`.
 *
 * Runs on the already-stripped text, so every parenthesis it counts is a parenthesis in code.
 */
internal fun findControlledTextFieldCalls(fileName: String, cleaned: String): List<GuestViolation> {
  val violations = mutableListOf<GuestViolation>()
  // Not preceded by an identifier character or a dot: `OutlinedTextField(` is a different entry on
  // the unconditional list, and `acme.TextField(` is somebody else's component.
  val call = Regex("(^|[^A-Za-z0-9_.])TextField\\s*\\(")
  for (match in call.findAll(cleaned)) {
    // The match starts one character *before* the name when the name is not at the start of the
    // file, so the name's own index is what the line is counted to -- otherwise a call on the line
    // after a blank one is reported a line late.
    val nameStart = cleaned.indexOf("TextField", match.range.first)
    val open = cleaned.indexOf('(', nameStart)
    var depth = 0
    var end = cleaned.length
    var i = open
    while (i < cleaned.length) {
      if (cleaned[i] == '(') depth++
      if (cleaned[i] == ')') {
        depth--
        if (depth == 0) {
          end = i
          break
        }
      }
      i++
    }
    val arguments = cleaned.substring(open, end)
    if (Regex("\\bonValueChange\\b").containsMatchIn(arguments)) {
      val line = cleaned.substring(0, nameStart).count { it == '\n' } + 1
      violations += GuestViolation(fileName, line, CONTROLLED_TEXT_FIELD)
    }
  }
  return violations
}

/**
 * Finds forbidden calls in one file's source.
 *
 * Matched as `name(`, `name {` or `name<` with a word boundary, so `myAnimateFloatAsStateHelper(`
 * does not trip it and a *reference* without a call -- `::animateFloatAsState` -- does. The brace
 * form matters: `withFrameNanos { … }` is a trailing-lambda call and is how that API is actually
 * written. The reference form is deliberate too: passing one of these as a function reference is
 * the same per-frame state arriving by a longer road.
 */
fun checkGuestSource(
  fileName: String,
  source: String,
  forbidden: List<ForbiddenApi> = FORBIDDEN_GUEST_APIS,
): List<GuestViolation> {
  val cleaned = stripCommentsAndStrings(source)
  val violations = mutableListOf<GuestViolation>()
  cleaned.lineSequence().forEachIndexed { index, line ->
    for (api in forbidden) {
      // `(`, `<` or `{`. The brace is not decoration: `withFrameNanos { … }` is a trailing-lambda
      // call and is how that API is idiomatically written, so a pattern matching only parentheses
      // missed the most important entry on this list in its most common form.
      val pattern = Regex("(^|[^A-Za-z0-9_.])${Regex.escape(api.name)}\\s*[({<]")
      val reference = Regex("::${Regex.escape(api.name)}\\b")
      if (pattern.containsMatchIn(line) || reference.containsMatchIn(line)) {
        violations += GuestViolation(fileName, index + 1, api)
      }
    }
  }
  // The one rejection that is a name *plus an argument*, and therefore cannot be a list entry.
  violations += findControlledTextFieldCalls(fileName, cleaned)
  return violations.sortedBy { it.line }
}

/** The message a build fails with. Names the API, the reason, and the replacement. */
fun guestCheckFailure(violations: List<GuestViolation>): String = buildString {
  appendLine("Dogwood: ${violations.size} forbidden call(s) in guest code.")
  appendLine()
  appendLine("The animation and frame-clock calls below compile and run, and that is the problem:")
  appendLine("the guest has a working frame clock, so they would tick the boundary every frame and")
  appendLine("the screen would look correct. The text-field calls do not compile at all — this")
  appendLine("check exists to name the replacement, which \"unresolved reference\" does not.")
  appendLine()
  for (violation in violations) appendLine("  $violation")
  appendLine()
  appendLine("See specs/layer-1-authoring.md, adrs/layer-5/ADR-020-animation.md and")
  appendLine("adrs/layer-5/ADR-019-text-input.md.")
}

/**
 * The command-line entry point, for builds that cannot apply the plugin.
 *
 * The engine's own guest modules are in the build that *defines* the plugin, so applying it by
 * identifier there would need a composite build or a prior publish — the same reason the generator
 * is invoked directly here. A product applies `io.github.teegarcs.dogwood.guest` and never sees this.
 *
 * Arguments are source directories. Exits non-zero with the report on any violation.
 */
fun main(args: Array<String>) {
  val roots = args.map { java.io.File(it) }.filter { it.isDirectory }
  require(roots.isNotEmpty()) { "usage: guest-check <source directory>..." }

  val files = roots.flatMap { root ->
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { root to it }
  }
  val violations = files.flatMap { (root, file) ->
    checkGuestSource(file.relativeTo(root.parentFile ?: root).path, file.readText())
  }

  if (violations.isNotEmpty()) {
    System.err.println(guestCheckFailure(violations))
    kotlin.system.exitProcess(1)
  }
  println("dogwood: ${files.size} guest files checked, nothing forbidden")
}
