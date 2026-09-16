/*
 * Project Dogwood -- generator v2's bindability rule, applied to a library.
 *
 * The rule is v1's (specs/layer-5-host.md, "Bindability: The Real Rule") with one change that is
 * the whole difference between reading a surface and reading a library. On a surface, a parameter
 * the rule cannot cross is an error the author fixes. In a library nobody can edit, it is one of
 * two things: a parameter with a default, which the guest simply cannot *set* -- the stub omits it
 * and the host passes the library's own default -- or a parameter without one, which makes the
 * component unbindable and is reported with its reason. Coverage of functions overstates coverage
 * of parameters, and the report says both numbers (plans/generator-v2.md, D-D and D-E).
 */
package dev.dogwood.codegen.v2

import java.security.MessageDigest

/** How a settable parameter crosses. Each kind is one row of the plan's mapping table. */
enum class Kind {
  PRIMITIVE, DP, TEXT_UNIT, COLOR, SHAPE, PADDING_VALUES,
  ARRANGEMENT_H, ARRANGEMENT_V, ARRANGEMENT_HV,
  ALIGNMENT_H, ALIGNMENT_V, ALIGNMENT_2D,
  FONT_WEIGHT, TEXT_ALIGN, TEXT_OVERFLOW, TEXT_DECORATION,
  MODIFIER, SLOT, EVENT,
}

sealed class Verdict {
  /** Crosses the boundary; the guest may set it. */
  data class Settable(
    val kind: Kind,
    /** The library's own type text, kept so a `Boolean` and an `Int` primitive stay apart. */
    val libraryType: String,
    val nullable: Boolean,
    val hasDefault: Boolean,
    val defaultText: String?,
    /** For [Kind.EVENT]: the argument types in order. */
    val eventArguments: List<String> = emptyList(),
    /** For [Kind.SLOT]: the lambda's receiver type text, or null. */
    val slotReceiver: String? = null,
    /** For [Kind.SLOT]: the slot takes a `PaddingValues` argument the host applies around the children. */
    val slotTakesPadding: Boolean = false,
    /** `enabled`, `checked`, `selected`, `readOnly`: absence-unreadable skew withholds the widget. */
    val affordance: Boolean = false,
  ) : Verdict()

  /** Cannot cross, has a default: omitted from the stub, the default passed always. */
  data class HostDefaultOnly(val libraryType: String, val defaultText: String) : Verdict()

  /** Cannot cross and is required: the component cannot be bound. */
  data class Unbindable(val libraryType: String, val reason: String) : Verdict()
}

data class ClassifiedParameter(val parameter: LibraryParameter, val verdict: Verdict)

data class ClassifiedComposable(
  val source: LibraryComposable,
  /** `Name`, or `Name~<hash>` for the second and later overloads in a file (plan D-F). */
  val dictionaryName: String,
  val parameters: List<ClassifiedParameter>,
  /** Why this component is not bound, or null when it is. Deprecated components carry a reason too. */
  val unbindableReason: String?,
) {
  val isBindable: Boolean get() = unbindableReason == null
  val settable: List<ClassifiedParameter> get() = parameters.filter { it.verdict is Verdict.Settable }
  val hostDefaultOnly: List<ClassifiedParameter> get() = parameters.filter { it.verdict is Verdict.HostDefaultOnly }
}

object Classifier {

  private val PRIMITIVES = setOf("String", "Boolean", "Int", "Long", "Float", "Double")
  private val EVENT_ARGUMENTS = PRIMITIVES
  private val ASSETS = setOf("Painter", "ImageBitmap", "ImageVector", "Brush")
  private val AFFORDANCE_NAMES = setOf("enabled", "checked", "selected", "readOnly")
  private val CONTROLLED_TEXT_INPUT = setOf(
    "TextField", "OutlinedTextField", "BasicTextField", "SecureTextField", "BasicSecureTextField",
    "SearchBar", "DockedSearchBar", "ExpandedFullScreenSearchBar", "ExpandedDockedSearchBar",
    "SearchBarInputField", "OutlinedSecureTextField", "CoreTextField",
  )

  /** Lambdas the rendering engine invokes inside a frame; no wrapper makes them crossable. */
  private val IN_FRAME_SCOPES = setOf(
    "DrawScope", "ContentDrawScope", "LazyListScope", "LazyGridScope", "LazyStaggeredGridScope",
    "LazyItemScope", "PointerInputScope", "AwaitPointerEventScope", "MeasureScope",
    "SubcomposeMeasureScope", "CacheDrawScope", "GraphicsLayerScope", "PagerScope",
  )

  private val SIMPLE_KINDS = mapOf(
    "Dp" to Kind.DP,
    "TextUnit" to Kind.TEXT_UNIT,
    "Color" to Kind.COLOR,
    "Shape" to Kind.SHAPE,
    "PaddingValues" to Kind.PADDING_VALUES,
    "Arrangement.Horizontal" to Kind.ARRANGEMENT_H,
    "Arrangement.Vertical" to Kind.ARRANGEMENT_V,
    "Arrangement.HorizontalOrVertical" to Kind.ARRANGEMENT_HV,
    "Alignment.Horizontal" to Kind.ALIGNMENT_H,
    "Alignment.Vertical" to Kind.ALIGNMENT_V,
    "Alignment" to Kind.ALIGNMENT_2D,
    "FontWeight" to Kind.FONT_WEIGHT,
    "TextAlign" to Kind.TEXT_ALIGN,
    "TextOverflow" to Kind.TEXT_OVERFLOW,
    "TextDecoration" to Kind.TEXT_DECORATION,
  )

  fun classify(surface: LibrarySurface): List<ClassifiedComposable> {
    // Overload keys are per file: the first declaration of a name keeps the bare name, and each
    // later one carries a hash of its parameter names, which survives a library version that
    // reorders declarations and differs for one that adds a parameter (plan D-F).
    val seen = mutableMapOf<Pair<String, String>, Int>()
    return surface.composables.map { composable ->
      val key = composable.file to composable.name
      val index = seen.getOrDefault(key, 0).also { seen[key] = it + 1 }
      // Names AND types in the hash. The first run hashed names alone, and material3's two
      // `TextField(value, onValueChange, …)` overloads -- one `String`, one `TextFieldValue` --
      // collided into one dictionary entry, which the lock would then have treated as one
      // component with two encodings. A type in the hash keeps a library version that renames a
      // parameter from moving the tag too, which is the same trade the lock's retype check makes.
      val dictionaryName = if (index == 0) composable.name else {
        composable.name + "~" + sha8(composable.parameters.joinToString(",") { "${it.name}:${stripAnnotations(it.type)}" })
      }
      classifyOne(composable, dictionaryName, surface)
    }.dedupeErasedOverloads()
  }

  /**
   * Two bound overloads whose *guest* signatures are identical cannot both be emitted: Kotlin
   * refuses conflicting overloads, and it is right to. It happens when overloads differ only in
   * parameters the guest cannot set -- `TopAppBar` with and without a `subtitle` slot is the
   * first case the compiler found. The first declaration in the file wins; the later ones are
   * reported as excluded with the winner named, so the coverage report shows the choice.
   *
   * **The key ignores parameter order**, and that is the correction plans/material3-proof.md
   * section 1.5 predicted. Material 3's two `Slider` overloads take the same guest-visible
   * parameters with `steps` and `onValueChangeFinished` swapped. Different Kotlin signatures, so
   * both compiled -- and every call that supplied only the two required parameters was an
   * overload-resolution ambiguity, which made `Slider` uncallable from any payload. No test
   * caught it because no payload had called it. Sorting the descriptors is the whole fix: two
   * overloads a guest cannot tell apart are one component, whatever order the library declared
   * them in.
   */
  private fun List<ClassifiedComposable>.dedupeErasedOverloads(): List<ClassifiedComposable> {
    val winners = mutableMapOf<String, String>()
    return map { c ->
      if (!c.isBindable) return@map c
      val key = c.source.file + "/" + c.source.name + "(" + c.settable.map { (p, v) ->
        v as Verdict.Settable
        "${p.name}:${v.kind}:${v.eventArguments.joinToString("|")}:${v.hasDefault || v.nullable}"
      }.sorted().joinToString(",") + ")"
      val winner = winners[key]
      if (winner == null) {
        winners[key] = c.dictionaryName
        c
      } else {
        c.copy(unbindableReason = "guest signature identical to $winner after erasing host-default-only parameters")
      }
    }
  }

  private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

  private fun classifyOne(composable: LibraryComposable, dictionaryName: String, surface: LibrarySurface): ClassifiedComposable {
    var modifierSeen = false
    val parameters = composable.parameters.map { parameter ->
      val verdict = classifyParameter(parameter, modifierSeen)
      if (verdict is Verdict.Settable && verdict.kind == Kind.MODIFIER) modifierSeen = true
      ClassifiedParameter(parameter, verdict)
    }
    // A default the binding would have to copy but cannot: it names something the library keeps
    // to itself. Refused here, with the name, rather than by the host compiler with a path.
    val internalDefault = parameters.firstNotNullOfOrNull { (p, v) ->
      val default = when (v) {
        is Verdict.HostDefaultOnly -> v.defaultText
        is Verdict.Settable -> if (v.hasDefault) v.defaultText else null
        else -> null
      } ?: return@firstNotNullOfOrNull null
      IDENTIFIER.findAll(default).map { it.value }.firstOrNull { it in surface.internalNames }?.let { "${p.name}: default names internal `$it`" }
    }
    val internalMarker = composable.optIns.firstOrNull { it in surface.internalMarkers }
    val reason = when {
      internalMarker != null -> "requires an opt-in the library keeps internal ($internalMarker)"
      internalDefault != null -> internalDefault
      // Policy, not a rule: binding a deprecated function ships a client that cannot follow the
      // library's own deprecation. Reported as its own bucket so the number is visible.
      composable.deprecated -> "deprecated in the library"
      // Controlled text input is excluded BY NAME, as specs/layer-5-host.md's table does: the
      // `String value` + `onValueChange` overloads pass the rule -- a string and an event -- and are
      // exactly the round trip across a latent boundary that ADR-019 replaced with a versioned
      // `TextInput`. Binding them would ship a field that drops keystrokes under load.
      composable.name in CONTROLLED_TEXT_INPUT -> "controlled text input; use TextInput (ADR-019)"
      composable.receiver != null -> "extension on ${composable.receiver}; a binding calls by name"
      composable.typeParameters.isNotEmpty() -> "generic: ${composable.typeParameters.joinToString()}"
      else -> parameters.firstNotNullOfOrNull { (p, v) ->
        (v as? Verdict.Unbindable)?.let { "${p.name}: ${it.reason}" }
      }
    }
    return ClassifiedComposable(composable, dictionaryName, parameters, reason)
  }

  private fun classifyParameter(parameter: LibraryParameter, modifierSeen: Boolean): Verdict {
    val raw = parameter.type
    val hasDefault = parameter.defaultText != null
    fun cannot(reason: String): Verdict =
      if (hasDefault) Verdict.HostDefaultOnly(raw, parameter.defaultText!!) else Verdict.Unbindable(raw, reason)

    if (parameter.isVararg) return cannot("vararg")

    val stripped = stripAnnotations(raw)
    val nullable = stripped.endsWith("?")
    val type = unwrapNullable(stripped)
    val composable = raw.contains("@Composable")

    fun settable(kind: Kind, extra: (Verdict.Settable) -> Verdict.Settable = { it }): Verdict =
      extra(
        Verdict.Settable(
          kind = kind, libraryType = raw, nullable = nullable, hasDefault = hasDefault,
          defaultText = parameter.defaultText,
          affordance = parameter.name in AFFORDANCE_NAMES && type == "Boolean",
        ),
      )

    // Lambdas first: a `@Composable` one is a slot, a Unit-returning plain one is an event.
    if (type.contains("->")) {
      val (receiver, arguments, returns) = splitLambda(type)
      if (receiver != null && receiver.substringAfterLast('.') in IN_FRAME_SCOPES) {
        return cannot("$receiver is invoked inside a frame")
      }
      if (composable) {
        // A composable lambda that RETURNS something -- `@Composable () -> WindowInsets` -- is not
        // a slot; the host would call it for a value. Its default is the only thing that can fill it.
        if (returns != "Unit") return cannot("composable lambda returning $returns")
        // A slot whose one argument is a `PaddingValues` -- `Scaffold`'s content -- is a slot the
        // host can still fill: it applies the padding around the children itself.
        val takesPadding = arguments.size == 1 && arguments.single() == "PaddingValues"
        if (arguments.isNotEmpty() && !takesPadding) {
          return cannot("slot receives ${arguments.joinToString()} the guest cannot read")
        }
        return settable(Kind.SLOT) { it.copy(slotReceiver = receiver, slotTakesPadding = takesPadding) }
      }
      if (returns != "Unit") return cannot("host-invoked lambda returning $returns")
      if (receiver != null) return cannot("lambda with receiver $receiver")
      if (arguments.any { it !in EVENT_ARGUMENTS }) {
        return cannot("callback carries ${arguments.first { it !in EVENT_ARGUMENTS }}")
      }
      return settable(Kind.EVENT) { it.copy(eventArguments = arguments) }
    }

    if (type == "Modifier") {
      return if (modifierSeen) cannot("a second Modifier parameter") else settable(Kind.MODIFIER)
    }
    if (type in PRIMITIVES) return settable(Kind.PRIMITIVE)
    SIMPLE_KINDS[type]?.let { return settable(it) }
    if (type in ASSETS) return cannot("asset-backed type $type")
    if (type.endsWith("State") || type.endsWith("StateHolder") || type == "MutableInteractionSource") {
      return cannot("live-state holder $type")
    }
    return cannot("type $type cannot cross the boundary")
  }

  /** `@Composable RowScope.() -> Unit` → (RowScope, [], Unit); `(Boolean) -> Unit` → (null, [Boolean], Unit). */
  private fun splitLambda(type: String): Triple<String?, List<String>, String> {
    var t = type.trim()
    // A nullable lambda is written `(( ... ) -> R)?`; the outer parentheses are the wrapper.
    if (t.startsWith("(") && t.endsWith(")") && closesAtEnd(t)) t = t.substring(1, t.length - 1).trim()
    val arrow = t.lastIndexOf("->")
    val returns = t.substring(arrow + 2).trim()
    val head = t.substring(0, arrow).trim()
    val receiver = if (head.contains(".(")) head.substringBefore(".(").trim() else null
    val argumentList = head.substringAfter("(").substringBeforeLast(")")
    val arguments = splitTop(argumentList).map { it.substringAfter(":").trim() }.filter { it.isNotEmpty() }
    return Triple(receiver, arguments, returns)
  }

  private fun closesAtEnd(s: String): Boolean {
    var depth = 0
    for ((i, c) in s.withIndex()) {
      if (c == '(') depth++
      if (c == ')') depth--
      if (depth == 0 && i < s.length - 1) return false
    }
    return true
  }

  private fun splitTop(s: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    val current = StringBuilder()
    for (c in s) {
      when (c) {
        '(', '<' -> { depth++; current.append(c) }
        ')', '>' -> { depth--; current.append(c) }
        ',' -> if (depth == 0) { out += current.toString().trim(); current.clear() } else current.append(c)
        else -> current.append(c)
      }
    }
    if (current.isNotBlank()) out += current.toString().trim()
    return out
  }

  /** Drops leading annotations other than `@Composable`, whose presence is read separately. */
  internal fun stripAnnotations(type: String): String {
    var t = type.trim()
    while (t.startsWith("@")) {
      // `@FloatRange(from = 0.0, to = 1.0) Float` -- the annotation may carry arguments.
      var i = 1
      while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '.' || t[i] == '_')) i++
      if (i < t.length && t[i] == '(') {
        var depth = 0
        while (i < t.length) { if (t[i] == '(') depth++; if (t[i] == ')') { depth--; if (depth == 0) { i++; break } }; i++ }
      }
      t = t.substring(i).trim()
    }
    return t
  }

  private fun unwrapNullable(type: String): String {
    var t = type
    if (t.endsWith("?")) t = t.dropLast(1).trim()
    return t
  }

  private fun sha8(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return digest.take(4).joinToString("") { "%02x".format(it) }
  }
}
