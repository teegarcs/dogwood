/*
 * Project Dogwood -- the surface parser.
 *
 * Reads Kotlin source with the compiler's own frontend and produces the parsed model. It reads
 * *source*, not a metalava dump, and Layer 5 ADR-002 says why: dumps carry no default
 * expressions, and 73.9% of parameters are optional. A generator that cannot see a default cannot
 * decide whether the host must resolve it, which is most of what generation has to get right.
 *
 * This is a parse, not a full frontend analysis: it reads the declaration tree, not resolved
 * types. That is enough for generator v1, whose target is first-party sources with curated
 * signatures, and is not enough for the full Compose surface -- where a type alias or a star
 * import would need resolution to classify. That gap is Phase 3's, and it is real.
 */
package dev.dogwood.codegen

import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

// The compiler's parser entry points are marked as K1 API. Parsing declarations is exactly what
// this needs and the K2 analysis API would be a much larger dependency for no gain here; when the
// generator needs resolved types rather than declaration text, that trade changes.
class SurfaceParser(
  /**
   * The holder shapes this parser will accept behind `@Holder`.
   *
   * Injectable so the generator's own tests can exercise a shape without adding one to the
   * shipping table -- a table entry whose host mirror does not exist would generate a binding that
   * compiles, renders, and does nothing.
   */
  private val holderShapes: List<HolderShape> = DEFAULT_HOLDER_SHAPES,
) {

  private val environment: KotlinCoreEnvironment = KotlinCoreEnvironment.createForProduction(
    Disposer.newDisposable("dogwood-codegen"),
    CompilerConfiguration(),
    EnvironmentConfigFiles.JVM_CONFIG_FILES,
  )

  private val factory = org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory.getInstance(environment.project)

  fun parse(source: String, fileName: String = "Surface.kt"): List<ParsedComponent> {
    val file = factory.createFileFromText(
      fileName,
      org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE,
      source,
    ) as KtFile
    return file.collectDescendantsOfType<KtNamedFunction>()
      .filter { it.isComposable() && !it.isPrivateOrInternal() }
      .map { it.toComponent() }
  }

  fun parseFiles(files: List<File>): List<ParsedComponent> =
    files.flatMap { parse(it.readText(), it.name) }

  private fun KtNamedFunction.isPrivateOrInternal(): Boolean =
    modifierList?.text?.let { it.contains("private") || it.contains("internal") } ?: false

  private fun KtNamedFunction.isComposable(): Boolean =
    annotationEntries.any { it.shortName?.asString() == "Composable" }

  private fun KtNamedFunction.toComponent() = ParsedComponent(
    name = name ?: error("a component must be named"),
    parameters = valueParameters.map { it.classify() },
  )

  /**
   * The bindability rule, applied.
   *
   * [Layer 5](../../specs/layer-5-host.md): a composable is generable if and only if every lambda
   * parameter is a composition-time slot or a discrete event, and no parameter is a live object
   * the guest must read or call.
   */
  private fun KtParameter.classify(): ParsedParameter {
    val name = name ?: error("a parameter must be named")
    val type = typeReference?.text?.replace(Regex("\\s+"), " ")?.trim() ?: "Unit"
    val default = defaultValue?.text
    // Read from the surface rather than inferred from the parameter's name. A rule that guessed
    // from `enabled` would silently miss `interactive`, `locked` or `isEditable`, and the failure
    // of a guess here is a control that lies about what it will do.
    val affordance = annotationEntries.any { it.shortName?.asString() == "Affordance" }

    // `@Range(min = …, max = …)`. Read positionally as well as by name, because a surface author
    // writing `@Range(0.0, 5.0)` means the same thing as one writing it out, and a parser that
    // silently ignored the shorter form would drop a clamp without a word.
    val range = annotationEntries
      .firstOrNull { it.shortName?.asString() == "Range" }
      ?.let { entry ->
        val arguments = entry.valueArguments
        fun argument(named: String, position: Int): Double? {
          val byName = arguments.firstOrNull {
            it.getArgumentName()?.asName?.asString() == named
          }
          val chosen = byName
            ?: arguments.filter { it.getArgumentName() == null }.getOrNull(position)
          val text = chosen?.getArgumentExpression()?.text ?: return null
          return when (text) {
            "Double.MAX_VALUE" -> Double.MAX_VALUE
            "Double.MIN_VALUE" -> Double.MIN_VALUE
            else -> text.removeSuffix("f").toDoubleOrNull()
          }
        }
        val min = argument("min", 0)
          ?: error("@Range on '$name' has no readable min")
        ParsedRange(min, argument("max", 1) ?: Double.MAX_VALUE)
      }

    fun of(kind: ParameterKind, rejection: String? = null) =
      ParsedParameter(
        name, type, kind,
        hasDefault = default != null,
        defaultExpression = default,
        rejection = rejection,
        affordance = affordance,
        range = range,
      )

    // `@Holder` on a live-state parameter: the surface is asserting that this holder has a shape
    // and a host-side mirror, and the shape table decides whether that is true. Read before the
    // live-state rejection below, which is the *unmarked* case.
    val declaredHolder = annotationEntries.any { it.shortName?.asString() == "Holder" }
    val shape = holderShapes.firstOrNull { it.type == type.removeSuffix("?") }

    return when {
      type == "Modifier" -> of(ParameterKind.MODIFIER)

      // Marked, and the shape is known: plumbed. The generator emits the properties the shape
      // declares and calls the mirror; what the holder *does* stays hand-written.
      declaredHolder && shape != null ->
        ParsedParameter(
          name, type, ParameterKind.HOLDER,
          hasDefault = default != null,
          defaultExpression = default,
          affordance = affordance,
          holderShape = shape,
        )

      // Marked and unknown. Rejected rather than plumbed by inference: a guessed shape emits
      // properties no host reads, which is not a failure anyone sees -- the widget renders and the
      // holder is inert.
      declaredHolder ->
        of(ParameterKind.UNSUPPORTED, "no holder shape is registered for $type")

      // A live-state holder: the guest would have to read or drive host-owned state per frame,
      // which the Layer 4 invariant forbids outright. Marking it `@Holder` is how a surface says
      // the mirrored-state protocol covers this one.
      LIVE_STATE.any { type.contains(it) } ->
        of(ParameterKind.UNSUPPORTED, "live-state holder: $type")

      // Host-resolved: the guest names an intent -- a token, a shape, a formatting recipe -- and
      // the host resolves it against the environment it is drawing in. `Shape` and `Color` used to
      // be rejected here as asset-gated, and that was true before the recipe grammar existed. It
      // is not true now, and leaving them rejected would have kept a product's own components from
      // accepting a themed colour.

      // Asset-gated: the guest has no way to name a Painter, and no way to send one.
      ASSET_TYPES.any { type.contains(it) } ->
        of(ParameterKind.UNSUPPORTED, "asset-gated type: $type")

      type.startsWith("@Composable") || type.contains("-> Unit") && type.contains("Scope") && !type.contains("(") ->
        of(ParameterKind.SLOT)

      type.contains("@Composable") -> of(ParameterKind.SLOT)

      type.contains("->") -> classifyLambda(name, type, default)

      HOST_RESOLVED.any { type.removeSuffix("?") == it } -> of(ParameterKind.HOST_RESOLVED)

      SERIALIZABLE.any { type.removeSuffix("?") == it } || type.removeSuffix("?").first().isUpperCase() &&
        type.removeSuffix("?").all { it.isLetterOrDigit() || it == '?' } -> of(ParameterKind.VALUE)

      else -> of(ParameterKind.UNSUPPORTED, "unclassifiable type: $type")
    }
  }

  private fun classifyLambda(name: String, type: String, default: String?): ParsedParameter {
    val returns = normalizeLambda(type).substringAfterLast("->").trim()
    return when {
      // A lambda that returns a value is one the host would call and await an answer from, during
      // composition. Found in the wild in the Backpack audit; it cannot cross.
      returns != "Unit" -> ParsedParameter(
        name, type, ParameterKind.UNSUPPORTED, default != null, default,
        "host-invoked lambda returning $returns; the host cannot block on the guest mid-frame",
      )
      // "Give me the content for index n" is the lazy-layout subsystem, not an event.
      type.contains("(Int)") || type.contains("Int)") && type.contains("Scope") -> ParsedParameter(
        name, type, ParameterKind.UNSUPPORTED, default != null, default,
        "indexed content lambda; this is the lazy-layout subsystem, not a slot",
      )
      else -> ParsedParameter(name, type, ParameterKind.EVENT, default != null, default)
    }
  }

  /**
   * Strips the wrapper a nullable lambda type carries.
   *
   * `(() -> Unit)?` is one lambda type, not a parenthesised something-else, and reading its return
   * type naively yields `Unit)?` -- which matches nothing, so the parameter was classified as a
   * host-invoked lambda and **the whole component was rejected as unbindable**. Silently, with a
   * message about blocking the guest mid-frame that had nothing to do with the real problem.
   *
   * Found by declaring `onExited: (() -> Unit)? = null`, which is an ordinary shape for an optional
   * callback and one every surface author will reach for eventually.
   */
  private fun normalizeLambda(type: String): String {
    var bare = type.trim()
    if (bare.endsWith("?")) bare = bare.dropLast(1).trim()
    // Only unwrap when the leading parenthesis is the one the trailing parenthesis closes.
    // `(Boolean) -> Unit` opens with one too, and its parameter list must survive.
    if (bare.startsWith("(") && bare.endsWith(")")) {
      var depth = 0
      var closesAtEnd = true
      for ((index, character) in bare.withIndex()) {
        if (character == '(') depth++
        if (character == ')') depth--
        if (depth == 0 && index < bare.length - 1) {
          closesAtEnd = false
          break
        }
      }
      if (closesAtEnd) bare = bare.substring(1, bare.length - 1).trim()
    }
    return bare
  }

  companion object {
    /**
     * The holder shapes the shipping surface may use.
     *
     * One entry per holder type whose host-side mirror exists. Deliberately short: the corrected
     * coverage measurement enumerates roughly thirty holder types, and this table grows by one
     * every time a mirror is written, never in anticipation of one.
     *
     * See `adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md`.
     */
    val DEFAULT_HOLDER_SHAPES = listOf(
      /*
       * Focus: a target with nothing to report.
       *
       * The guest asks for focus and the host takes it, or gives it up. There is deliberately no
       * report back, and that is a decision rather than an omission -- "is this field focused?" is
       * answerable only per frame, and a guest that branched on it would be holding exactly the
       * per-frame state Layer 4 forbids. A guest that needs to know a field was left has an
       * ordinary event for it.
       */
      HolderShape(
        type = "FocusRequester",
        mirror = "rememberFocusMirror",
        properties = listOf(
          // Which way the request went. `false` is a real request -- give the focus up -- and not
          // the absence of one; absence is the sequence still being zero.
          HolderProperty(suffix = "Requested", type = "Boolean", field = "requested", absent = "false"),
          // A counter, not a flag, for the same reason `LazyListState`'s is: asking twice for the
          // same thing is two requests. A user who dismissed the keyboard and tapped the same
          // "edit" control again expects the field back.
          HolderProperty(suffix = "Sequence", type = "Int", field = "sequence", absent = "0"),
        ),
      ),

      /*
       * Scroll: a position mirror over a continuous quantity.
       *
       * The first shape here that reports. A list reports per item because a list has items; a
       * scrolling container has a length that changes every frame, so the guest declares the
       * quantum instead and it crosses as a property like everything else. See ADR-044.
       */
      HolderShape(
        type = "ScrollState",
        mirror = "rememberScrollMirror",
        properties = listOf(
          HolderProperty(suffix = "TargetDp", type = "Int", field = "targetDp", absent = "0"),
          HolderProperty(suffix = "Sequence", type = "Int", field = "targetSequence", absent = "0"),
          HolderProperty(suffix = "Animated", type = "Boolean", field = "targetAnimated", absent = "false"),
          // Presence, for the same reason a list carries it: the host cannot see guest closures, so
          // it cannot know whether reporting would be observed by anyone.
          HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
          HolderProperty(suffix = "QuantumDp", type = "Int", field = "reportEveryDp", absent = "48"),
        ),
        report = HolderReport(
          method = "report",
          arguments = listOf(
            HolderArgument("offsetDp", "Int", "0"),
            HolderArgument("maxOffsetDp", "Int", "-1"),
            HolderArgument("scrolling", "Boolean", "false"),
          ),
        ),
      ),

      /*
       * Snackbars: a request that answers.
       *
       * The first shape where the report is not an observation but a **reply**. It carries the
       * sequence it is answering, which is what stops two requests in flight being confused -- the
       * sequence that went down is the sequence that comes back. See ADR-051.
       */
      HolderShape(
        type = "SnackbarHostState",
        mirror = "rememberSnackbarMirror",
        properties = listOf(
          HolderProperty(suffix = "Message", type = "String", field = "message", absent = "\"\""),
          HolderProperty(suffix = "ActionLabel", type = "String", field = "actionLabel", absent = "\"\""),
          HolderProperty(suffix = "Sequence", type = "Int", field = "sequence", absent = "0"),
          HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
        ),
        report = HolderReport(
          method = "report",
          arguments = listOf(
            HolderArgument("sequence", "Int", "0"),
            HolderArgument("actionPerformed", "Boolean", "false"),
          ),
        ),
      ),
    )

    val LIVE_STATE = listOf(
      "InteractionSource", "ScrollState", "LazyListState", "CarouselState", "PagerState",
      "FocusRequester", "TextFieldState", "MutableState",
    )
    val ASSET_TYPES = listOf("Painter", "ImageBitmap", "ImageVector", "Brush", "TextStyle")
    val SERIALIZABLE = listOf("String", "Int", "Long", "Float", "Double", "Boolean")

    /**
     * Types whose value the host resolves at draw time.
     *
     * They cross as a recipe rather than a result, so the same wire bytes render differently in
     * dark mode, in another locale, or on a device with different currency conventions. See
     * `specs/layer-5-host.md`, "Named Resources".
     */
    val HOST_RESOLVED = listOf("TextValue", "Color", "Shape")
  }
}
