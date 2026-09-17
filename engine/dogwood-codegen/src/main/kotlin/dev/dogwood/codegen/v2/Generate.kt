/*
 * Project Dogwood -- generating one library tier: guest stubs, host bindings, dictionary, lock.
 *
 * Both halves come from one classification, so they cannot disagree about which parameters cross
 * (ADR-011's argument, at library scale). The guest half lives in `dogwood-compose` under a tier
 * package and records through the same seam v1's stubs use. The host half lives in its own module
 * -- one file per library file, carrying that file's imports, so the library's default expressions
 * resolve there exactly as they did at home -- and dispatches through one `DogwoodSegmentBinding`
 * a host registers like any product's (ADR-046).
 *
 * What is deliberately fully qualified: every call to the library function itself. The host file
 * imports the library package with a star for the sake of default expressions, and Dogwood's own
 * host package declares composables with library-sounding names; a bare `Button(...)` would be an
 * ambiguity the first time both packages had one.
 */
package dev.dogwood.codegen.v2

import dev.dogwood.codegen.Dictionary
import dev.dogwood.codegen.LockResult
import dev.dogwood.codegen.ParameterKind
import dev.dogwood.codegen.ParsedComponent
import dev.dogwood.codegen.ParsedParameter
import dev.dogwood.codegen.buildDictionary
import dev.dogwood.codegen.checkAgainstLock
import dev.dogwood.codegen.emitDocs
import dev.dogwood.codegen.encode
import java.io.File

/** Everything the emitters need about one bound component. */
internal class Bound(
  val classified: ClassifiedComposable,
  /** v1's model of the same component, which is what the dictionary, the lock and the reference read. */
  val component: ParsedComponent,
) {
  val name: String get() = classified.source.name
  val file: String get() = classified.source.file
}

fun generateTier(
  sources: File,
  module: String,
  wireName: String,
  segmentName: String,
  segmentId: Int,
  /** The library's own version, as text. The encoded segment version is derived below. */
  libraryVersion: String,
  guestPackage: String,
  hostPackage: String,
  guestOut: File,
  hostOut: File,
  dictionaryOut: File,
  lock: File,
  exclusions: Map<String, String>,
  docsOut: File?,
  acceptDowngrade: Boolean = false,
) {
  val surface = LibrarySurfaceParser().parseModule(module, File(sources, module))
  val classified = Classifier.classify(surface)
  val bound = classified
    .filter { it.isBindable && it.dictionaryName !in exclusions }
    .map { Bound(it, it.toParsedComponent()) }

  /*
   * The lock is read before the dictionary is built, not only afterwards to check it.
   *
   * A generated tier's component list belongs to a library, and a library release that drops one
   * composable would renumber every tag after it if tags followed position -- so the tags a
   * previous run published are an *input* here. Names the lock has never seen get new tags above
   * everything taken; names the lock has and this run does not get their tags retired, so nothing
   * can ever take them and a payload in the field that still sends one gets a placeholder and a
   * report rather than the wrong widget. ADR-073; docs/upgrading-compose.md says what an upgrade
   * does with each outcome.
   */
  val locked = if (lock.isFile) {
    runCatching {
      kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromString(dev.dogwood.codegen.Dictionary.serializer(), lock.readText())
    }.getOrNull()
  } else {
    null
  }
  val generatedNames = bound.mapTo(mutableSetOf()) { it.component.name }
  val retiredTags = locked?.let { previous ->
    previous.reservedLocalTags.toSet() +
      previous.components.filter { it.name !in generatedNames }.map { it.localTag }
  }.orEmpty()

  /*
   * The generator revision, computed rather than typed (ADR-074).
   *
   * A segment version identifies a surface, and this surface has two authors: the library, whose
   * version is the first three components, and the generator, whose revision is the last two. When
   * the generator learns to bind something new from a library version it has already generated --
   * which is exactly what ADR-074's change did, twenty-six times over -- the contents grow while
   * the library version does not, and two different surfaces would otherwise share a number.
   *
   * So: same library version as the lock and a different surface means the revision goes up. A
   * different library version starts again at zero, because the first three components already
   * distinguish it. Nobody types it; it appears in the lock's diff beside the components that
   * caused it.
   */
  val previousRevision = locked?.let { generatorRevisionOf(it.version) } ?: 0
  val sameLibrary = locked != null && decodeLibraryVersion(locked.version) == libraryVersion
  fun dictionaryAt(version: Int) = buildDictionary(
    segmentName = segmentName, segmentId = segmentId, version = version,
    components = bound.map { it.component }, wireName = wireName,
    reservedLocalTags = retiredTags,
    existingTags = locked?.components?.associate { it.name to it.localTag }.orEmpty(),
    previous = locked?.components?.associateBy { it.name }.orEmpty(),
  )
  val surfaceChanged = locked != null && dictionaryAt(locked.version).encode() != lock.readText()
  val revision = when {
    !sameLibrary -> 0
    surfaceChanged -> previousRevision + 1
    else -> previousRevision
  }
  val version = encodeLibraryVersion(libraryVersion, revision)
  if (sameLibrary && surfaceChanged) {
    println(
      "generator-v2: $wireName stays at library $libraryVersion and its surface changed, so the " +
        "generator revision goes $previousRevision -> $revision (version $version). A payload may " +
        "declare the new number only once the fleet's hosts carry it (docs/upgrading-compose.md).",
    )
  }

  val dictionary = dictionaryAt(version)
  val lockedVersion = locked?.version
  when (val result = checkAgainstLock(dictionary, lock, acceptDowngrade)) {
    is LockResult.Violated -> error(
      buildString {
        appendLine("dictionary lock violated for $wireName; tags are permanent:")
        for (problem in result.problems) appendLine("  - $problem")
        appendLine("A library upgrade that removes or reorders a component retires its tag; add the component to exclusions.txt with the reason rather than moving the tag.")
      },
    )
    is LockResult.Updated -> {
      println("generator-v2: $wireName lock updated, added ${result.added.size}")
      for (entry in result.retired) {
        println(
          "generator-v2: $wireName retired $entry -- the library no longer declares it, or an " +
            "overload now wins it. Its tag can never be reused; payloads that still send it get a " +
            "placeholder and a skew report (docs/upgrading-compose.md).",
        )
      }
    }
    LockResult.Unchanged -> Unit
  }
  // Reported only when the *library* moved; a revision bump has already said so above, and one
  // event printed twice reads as two events.
  if (lockedVersion != null && !sameLibrary) {
    println(
      "generator-v2: $wireName moved from version $lockedVersion (${decodeLibraryVersion(lockedVersion)}) " +
        "to $version (${decodeLibraryVersion(version)}) -- the library the host resolves moved, and " +
        "a payload may declare the new number only once the fleet has it (docs/upgrading-compose.md)",
    )
  }

  guestOut.deleteRecursively(); guestOut.mkdirs()
  hostOut.deleteRecursively(); hostOut.mkdirs()
  val prefix = segmentName.replaceFirstChar { it.uppercase() }

  for ((file, inFile) in bound.groupBy { it.file }) {
    val stem = file.substringAfterLast('/').removeSuffix(".kt")
    File(guestOut, "$stem.kt").writeText(emitGuestFile(guestPackage, dictionary, inFile))
    File(hostOut, "${stem}Bindings.kt").writeText(emitHostFile(hostPackage, prefix, dictionary, inFile, stem, surface.publicMarkers))
  }
  File(hostOut, "${prefix}Binding.kt").writeText(emitBindingObject(hostPackage, prefix, wireName, version, dictionary, bound))

  dictionaryOut.parentFile.mkdirs()
  dictionaryOut.writeText(dictionary.encode())
  docsOut?.let { out ->
    out.parentFile.mkdirs()
    out.writeText(emitDocs(dictionary, bound.map { it.component }) + emitNotSettable(bound))
  }
  val skipped = classified.size - bound.size
  println("generator-v2: $wireName -- ${bound.size} bound, $skipped not bound (see the coverage report)")
}

// ---------------------------------------------------------------------------------------------
// From the v2 classification to v1's dictionary model
// ---------------------------------------------------------------------------------------------

/**
 * The dictionary sees only what crosses. Host-default-only parameters are not on the wire and are
 * absent here; an optional event gains a presence property beside it, because the host cannot see
 * guest closures and `Card(onClick = null)` and `Card(onClick = {})` are different controls.
 */
internal fun ClassifiedComposable.toParsedComponent(): ParsedComponent = ParsedComponent(
  name = dictionaryName,
  parameters = parameters.flatMap { (p, v) ->
    when (v) {
      is Verdict.Settable -> when (v.kind) {
        Kind.MODIFIER -> listOf(ParsedParameter(p.name, "Modifier", ParameterKind.MODIFIER, hasDefault = true, defaultExpression = "Modifier"))
        Kind.SLOT -> listOf(ParsedParameter(p.name, "@Composable () -> Unit" + if (v.nullable || v.hasDefault) "?" else "", ParameterKind.SLOT, hasDefault = v.hasDefault))
        Kind.EVENT -> {
          val type = "(${v.eventArguments.joinToString(", ") { guestArgumentType(it) }}) -> Unit"
          val optional = v.nullable || v.hasDefault
          buildList {
            if (optional) add(ParsedParameter(p.name + "Present", "Boolean", ParameterKind.VALUE, hasDefault = true, defaultExpression = "false"))
            add(ParsedParameter(p.name, if (optional) "($type)?" else type, ParameterKind.EVENT, hasDefault = v.hasDefault))
          }
        }
        else -> listOf(
          ParsedParameter(
            p.name, Classifier.stripAnnotations(v.libraryType), ParameterKind.VALUE,
            hasDefault = v.hasDefault, defaultExpression = v.defaultText, affordance = v.affordance,
          ),
        )
      }
      else -> emptyList()
    }
  },
)

private fun emitNotSettable(bound: List<Bound>): String = buildString {
  appendLine()
  appendLine("## Parameters a payload cannot set")
  appendLine()
  appendLine("Present in the library, absent from the stub: their type does not cross the boundary, so the")
  appendLine("host passes the library's own default every time. Listed so nobody looks for a missing argument.")
  appendLine()
  appendLine("| Component | Parameter | Library type | Host passes |")
  appendLine("| --- | --- | --- | --- |")
  for (b in bound.sortedBy { it.classified.dictionaryName }) {
    for ((p, v) in b.classified.hostDefaultOnly) {
      v as Verdict.HostDefaultOnly
      appendLine("| `${b.classified.dictionaryName}` | `${p.name}` | `${v.libraryType.replace("|", "\\|")}` | `${v.defaultText.replace("|", "\\|")}` |")
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Guest stubs
// ---------------------------------------------------------------------------------------------

private fun guestType(v: Verdict.Settable): String = when (v.kind) {
  Kind.PRIMITIVE -> Classifier.stripAnnotations(v.libraryType).removeSuffix("?")
  Kind.DP -> "Dp"
  Kind.TEXT_UNIT -> "TextUnit"
  Kind.COLOR -> "Color"
  Kind.SHAPE -> "Shape"
  Kind.PADDING_VALUES -> "PaddingValues"
  Kind.BORDER_STROKE -> "BorderStroke"
  Kind.FLOAT_RANGE -> "FloatRange"
  Kind.ARRANGEMENT_H, Kind.ARRANGEMENT_V, Kind.ARRANGEMENT_HV -> "Arrangement"
  Kind.ALIGNMENT_H -> "HorizontalAlignment"
  Kind.ALIGNMENT_V -> "VerticalAlignment"
  Kind.ALIGNMENT_2D -> "BoxAlignment"
  Kind.FONT_WEIGHT -> "FontWeight"
  Kind.TEXT_ALIGN -> "TextAlign"
  Kind.TEXT_OVERFLOW -> "TextOverflow"
  Kind.TEXT_DECORATION -> "TextDecoration"
  Kind.MODIFIER -> "Modifier"
  Kind.SLOT -> "@Composable () -> Unit"
  Kind.EVENT -> "(${v.eventArguments.joinToString(", ") { guestArgumentType(it) }}) -> Unit"
}

private fun guestEncode(v: Verdict.Settable): String = when (v.kind) {
  Kind.PRIMITIVE -> "JsonPrimitive(it)"
  Kind.DP -> "JsonPrimitive(it.value)"
  Kind.TEXT_UNIT, Kind.COLOR, Kind.SHAPE, Kind.PADDING_VALUES,
  Kind.BORDER_STROKE, Kind.FLOAT_RANGE -> "it.json"
  Kind.ARRANGEMENT_H, Kind.ARRANGEMENT_V, Kind.ARRANGEMENT_HV,
  Kind.FONT_WEIGHT, Kind.TEXT_ALIGN, Kind.TEXT_OVERFLOW, Kind.TEXT_DECORATION -> "JsonPrimitive(it.wire)"
  Kind.ALIGNMENT_H, Kind.ALIGNMENT_V, Kind.ALIGNMENT_2D -> "JsonPrimitive(it.ordinal)"
  else -> error("not a value kind: ${v.kind}")
}

/** Each callback argument paired with the wire index it starts at, since one may occupy two. */
private fun wireIndexed(types: List<String>): List<Pair<String, Int>> {
  var at = 0
  return types.map { type -> (type to at).also { at += wireArity(type) } }
}

/** How many wire arguments a callback argument of this type occupies. */
private fun wireArity(type: String): Int = if (type == "ClosedFloatingPointRange<Float>") 2 else 1

/** The guest lambda's parameter type for a callback argument. */
private fun guestArgumentType(type: String): String =
  if (type == "ClosedFloatingPointRange<Float>") "FloatRange" else type

private fun guestDecodeArgument(type: String, index: Int): String = when (type) {
  "ClosedFloatingPointRange<Float>" ->
    "FloatRange(args[$index].jsonPrimitive.floatOrNull ?: 0f, args[${index + 1}].jsonPrimitive.floatOrNull ?: 0f)"
  "Boolean" -> "args[$index].jsonPrimitive.booleanOrNull ?: false"
  "Int" -> "args[$index].jsonPrimitive.intOrNull ?: 0"
  "Long" -> "args[$index].jsonPrimitive.longOrNull ?: 0L"
  "Float" -> "args[$index].jsonPrimitive.floatOrNull ?: 0f"
  "Double" -> "args[$index].jsonPrimitive.doubleOrNull ?: 0.0"
  else -> "args[$index].jsonPrimitive.content"
}

private fun emitGuestFile(guestPackage: String, dictionary: Dictionary, bound: List<Bound>): String = buildString {
  appendLine("// Generated by dogwood-codegen (generator v2) from ${bound.first().file}. Do not edit.")
  appendLine("//")
  appendLine("// Segment '${dictionary.wireName}' (id ${dictionary.segmentId}), version ${dictionary.version}.")
  appendLine("@file:OptIn(dev.dogwood.compose.DogwoodGeneratedApi::class)")
  appendLine("@file:Suppress(\"unused\", \"UNUSED_PARAMETER\")")
  appendLine()
  appendLine("package $guestPackage")
  appendLine()
  appendLine("import dev.dogwood.compose.*")
  appendLine("import androidx.compose.runtime.Composable")
  appendLine("import androidx.compose.runtime.ComposeNode")
  appendLine("import dev.dogwood.protocol.ChildrenTag")
  appendLine("import dev.dogwood.protocol.EventTag")
  appendLine("import dev.dogwood.protocol.PropertyTag")
  appendLine("import dev.dogwood.protocol.widgetTag")
  appendLine("import kotlinx.serialization.json.JsonPrimitive")
  appendLine("import kotlinx.serialization.json.booleanOrNull")
  appendLine("import kotlinx.serialization.json.doubleOrNull")
  appendLine("import kotlinx.serialization.json.floatOrNull")
  appendLine("import kotlinx.serialization.json.intOrNull")
  appendLine("import kotlinx.serialization.json.longOrNull")
  appendLine("import kotlinx.serialization.json.jsonPrimitive")
  appendLine()

  for (b in bound) {
    val entry = dictionary.components.first { it.name == b.classified.dictionaryName }
    val settable = b.classified.settable
    appendLine("/** `${b.classified.dictionaryName}`, widget tag ${(dictionary.segmentId shl 24) or entry.localTag}. Parameters the library has and this stub does not are host-default-only; see the reference. */")
    appendLine("@Composable")
    appendLine("fun ${b.name}(")
    for ((p, v) in settable) {
      v as Verdict.Settable
      val optional = v.hasDefault || v.nullable
      val declaration = when (v.kind) {
        Kind.MODIFIER -> "modifier: Modifier = Modifier"
        Kind.SLOT -> if (optional) "${p.name}: (@Composable () -> Unit)? = null" else "${p.name}: @Composable () -> Unit"
        Kind.EVENT -> if (optional) "${p.name}: (${guestType(v)})? = null" else "${p.name}: ${guestType(v)}"
        else -> if (optional) "${p.name}: ${guestType(v)}? = null" else "${p.name}: ${guestType(v)}"
      }
      appendLine("  $declaration,")
    }
    appendLine(") {")
    appendLine("  ComposeNode<WidgetNode, DogwoodApplier>(")
    appendLine("    factory = { newWidget(widgetTag(${dictionary.segmentId}, ${entry.localTag})) },")
    appendLine("    update = {")
    for ((p, v) in settable) {
      v as Verdict.Settable
      when (v.kind) {
        Kind.MODIFIER -> {
          appendLine("      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }")
          appendLine("      reconcile { applyModifier(id, modifier) }")
        }
        Kind.SLOT -> Unit
        Kind.EVENT -> {
          val tag = entry.events.getValue(p.name)
          val body = if (v.eventArguments.isEmpty()) "{ handler() }" else {
            "{ args -> handler(${wireIndexed(v.eventArguments).joinToString(", ") { (t, at) -> guestDecodeArgument(t, at) }}) }"
          }
          if (v.hasDefault || v.nullable) {
            val presence = entry.properties.getValue(p.name + "Present")
            appendLine("      set(${p.name} != null) { present -> recording.recorder.property(id, PropertyTag($presence), JsonPrimitive(present)) }")
            appendLine("      set(${p.name}) { handler -> if (handler == null) recording.lambdas.clear(id, EventTag($tag)) else recording.lambdas.set(id, EventTag($tag)) $body }")
          } else {
            appendLine("      set(${p.name}) { handler -> recording.lambdas.set(id, EventTag($tag)) $body }")
          }
        }
        else -> {
          val tag = entry.properties.getValue(p.name)
          if (v.hasDefault || v.nullable) {
            appendLine("      set(${p.name}) { if (it != null) recording.recorder.property(id, PropertyTag($tag), ${guestEncode(v)}) }")
          } else {
            appendLine("      set(${p.name}) { recording.recorder.property(id, PropertyTag($tag), ${guestEncode(v)}) }")
          }
        }
      }
    }
    appendLine("    },")
    val slots = settable.filter { (it.verdict as Verdict.Settable).kind == Kind.SLOT }
    if (slots.isNotEmpty()) {
      appendLine("    content = {")
      for ((p, v) in slots) {
        v as Verdict.Settable
        val tag = entry.slots.getValue(p.name)
        if (v.hasDefault || v.nullable) appendLine("      if (${p.name} != null) Children(ChildrenTag($tag), ${p.name})")
        else appendLine("      Children(ChildrenTag($tag), ${p.name})")
      }
      appendLine("    },")
    }
    appendLine("  )")
    appendLine("}")
    appendLine()
  }
}

// ---------------------------------------------------------------------------------------------
// Host bindings
// ---------------------------------------------------------------------------------------------

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun hostReader(kind: Kind, libraryType: String, tag: Int): String {
  val base = Classifier.stripAnnotations(libraryType).removeSuffix("?")
  return when (kind) {
    Kind.PRIMITIVE -> when (base) {
      "Boolean" -> "node.booleanOrNull($tag)"
      "Int" -> "node.intOrNull($tag)"
      "Long" -> "node.longOrNull($tag)"
      "Float" -> "node.floatOrNull($tag)"
      "Double" -> "node.doubleOrNull($tag)"
      else -> "node.stringOrNull($tag)"
    }
    Kind.DP -> "node.dpOrNull($tag)"
    Kind.TEXT_UNIT -> "node.textUnitOrNull($tag)"
    Kind.COLOR -> "node.colorOrNull($tag)"
    Kind.SHAPE -> "node.shapeOrNull($tag)"
    Kind.PADDING_VALUES -> "node.paddingValuesOrNull($tag)"
    Kind.ARRANGEMENT_H -> "node.horizontalArrangementOrNull($tag)"
    Kind.ARRANGEMENT_V -> "node.verticalArrangementOrNull($tag)"
    Kind.ARRANGEMENT_HV -> "node.horizontalOrVerticalArrangementOrNull($tag)"
    Kind.ALIGNMENT_H -> "node.horizontalAlignmentOrNull($tag)"
    Kind.ALIGNMENT_V -> "node.verticalAlignmentOrNull($tag)"
    Kind.ALIGNMENT_2D -> "node.alignmentOrNull($tag)"
    Kind.FONT_WEIGHT -> "node.fontWeightOrNull($tag)"
    Kind.TEXT_ALIGN -> "node.textAlignOrNull($tag)"
    Kind.TEXT_OVERFLOW -> "node.textOverflowOrNull($tag)"
    Kind.TEXT_DECORATION -> "node.textDecorationOrNull($tag)"
    Kind.BORDER_STROKE -> "node.borderStrokeOrNull($tag)"
    Kind.FLOAT_RANGE -> "node.floatRangeOrNull($tag)"
    else -> error("not a value kind: $kind")
  }
}

/** What a required value reads as when a payload sent nothing: the type's quiet zero. */
private fun hostFallback(kind: Kind, libraryType: String): String {
  val base = Classifier.stripAnnotations(libraryType).removeSuffix("?")
  return when (kind) {
    Kind.PRIMITIVE -> when (base) {
      "Boolean" -> "false"; "Int" -> "0"; "Long" -> "0L"; "Float" -> "0f"; "Double" -> "0.0"; else -> "\"\""
    }
    Kind.DP -> "0.dp"
    Kind.TEXT_UNIT -> "androidx.compose.ui.unit.TextUnit.Unspecified"
    Kind.COLOR -> "androidx.compose.ui.graphics.Color.Unspecified"
    Kind.SHAPE -> "androidx.compose.ui.graphics.RectangleShape"
    Kind.PADDING_VALUES -> "androidx.compose.foundation.layout.PaddingValues(0.dp)"
    Kind.ARRANGEMENT_H -> "androidx.compose.foundation.layout.Arrangement.Start"
    Kind.ARRANGEMENT_V -> "androidx.compose.foundation.layout.Arrangement.Top"
    Kind.ARRANGEMENT_HV -> "androidx.compose.foundation.layout.Arrangement.Center"
    Kind.ALIGNMENT_H -> "androidx.compose.ui.Alignment.Start"
    Kind.ALIGNMENT_V -> "androidx.compose.ui.Alignment.Top"
    Kind.ALIGNMENT_2D -> "androidx.compose.ui.Alignment.TopStart"
    Kind.FONT_WEIGHT -> "androidx.compose.ui.text.font.FontWeight.Normal"
    Kind.TEXT_ALIGN -> "androidx.compose.ui.text.style.TextAlign.Unspecified"
    Kind.TEXT_OVERFLOW -> "androidx.compose.ui.text.style.TextOverflow.Clip"
    Kind.TEXT_DECORATION -> "androidx.compose.ui.text.style.TextDecoration.None"
    Kind.BORDER_STROKE -> "androidx.compose.foundation.BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent)"
    Kind.FLOAT_RANGE -> "0f..1f"
    else -> error("no fallback for $kind")
  }
}

private fun scopeFor(receiver: String?): String = when (receiver?.substringAfterLast('.')) {
  "RowScope" -> "LayoutScope(row = this)"
  "ColumnScope" -> "LayoutScope(column = this)"
  else -> "dogwoodScope"
}

/** The library's type text with every annotation but `@Composable` dropped: what a typed local is declared as. */
private fun localType(libraryType: String): String {
  val stripped = Classifier.stripAnnotations(libraryType)
  return if (libraryType.trimStart().startsWith("@Composable") && !stripped.startsWith("@Composable")) "@Composable $stripped" else stripped
}

private fun emitHostFile(hostPackage: String, prefix: String, dictionary: Dictionary, bound: List<Bound>, stem: String, publicMarkers: Set<String>): String = buildString {
  val source = bound.first().classified.source
  val libraryPackage = source.packageName
  // Every simple name a generated line might use: default expressions, the parameter types the
  // locals are declared with, and slot receivers. Used only to decide which of the library file's
  // imports to keep -- an import of something `internal` is an error whether or not it is used.
  val referenced = bound.flatMap { b ->
    b.classified.parameters.flatMap { (p, v) ->
      val fromType = IDENTIFIER.findAll(p.type).map { it.value }.toList()
      val fromDefault = p.defaultText?.let { d -> IDENTIFIER.findAll(d).map { it.value }.toList() }.orEmpty()
      fromType + fromDefault
    }
  }.toSet()
  val imports = source.imports.filter { line ->
    val target = line.removePrefix("import ").trim()
    when {
      target.contains(".internal") -> false
      target.endsWith(".*") -> true
      else -> {
        val simple = if (" as " in target) target.substringAfter(" as ").trim() else target.substringAfterLast('.')
        simple in referenced
      }
    }
  }
  // Every public marker the library declares, not only the ones on these functions: a default
  // expression may reach an experimental API the function itself is not marked with, and the
  // compiler refuses the file either way.
  val optIns = bound.flatMap { it.classified.source.optIns }.toSet() + publicMarkers

  appendLine("// Generated by dogwood-codegen (generator v2) from ${source.file}. Do not edit.")
  appendLine("//")
  appendLine("// Segment '${dictionary.wireName}' (id ${dictionary.segmentId}), version ${dictionary.version}.")
  if (optIns.isNotEmpty()) {
    appendLine("@file:OptIn(${optIns.sorted().joinToString(", ") { "$it::class" }})")
  }
  appendLine("@file:Suppress(\"DEPRECATION\", \"UNUSED_ANONYMOUS_PARAMETER\", \"UNUSED_VARIABLE\", \"NAME_SHADOWING\")")
  appendLine()
  appendLine("package $hostPackage")
  appendLine()
  val allImports = LinkedHashSet<String>()
  allImports += "import $libraryPackage.*"
  allImports += imports
  allImports += listOf(
    "import androidx.compose.runtime.Composable",
    "import androidx.compose.ui.Modifier",
    "import androidx.compose.ui.unit.dp",
    "import androidx.compose.foundation.layout.padding",
    "import dev.dogwood.host.WidgetView",
    "import dev.dogwood.host.LayoutScope",
    "import dev.dogwood.host.EventSink",
    "import dev.dogwood.host.RenderChildren",
    "import dev.dogwood.host.send",
    "import dev.dogwood.host.booleanOrNull",
    "import dev.dogwood.host.intOrNull",
    "import dev.dogwood.host.longOrNull",
    "import dev.dogwood.host.floatOrNull",
    "import dev.dogwood.host.doubleOrNull",
    "import dev.dogwood.host.stringOrNull",
    "import dev.dogwood.host.colorOrNull",
    "import dev.dogwood.host.shapeOrNull",
    "import dev.dogwood.host.dpOrNull",
    "import dev.dogwood.host.textUnitOrNull",
    "import dev.dogwood.host.borderStrokeOrNull",
    "import dev.dogwood.host.floatRangeOrNull",
    "import dev.dogwood.host.paddingValuesOrNull",
    "import dev.dogwood.protocol.EventTag",
    "import dev.dogwood.protocol.widgetTag",
    "import kotlinx.serialization.json.JsonPrimitive",
  )
  for (line in allImports) appendLine(line)
  appendLine()
  appendLine("/** Dispatches the components generated from `${source.file}`. Returns false when the tag is not one of them. */")
  appendLine("@Composable")
  appendLine("internal fun bind$prefix$stem(dogwoodNode: WidgetView, dogwoodModifier: Modifier, dogwoodScope: LayoutScope, dogwoodEvents: EventSink): Boolean {")
  appendLine("  when (dogwoodNode.tag.value) {")
  for (b in bound) {
    val entry = dictionary.components.first { it.name == b.classified.dictionaryName }
    appendLine("    widgetTag(${dictionary.segmentId}, ${entry.localTag}).value -> {")
    /*
     * One typed local per parameter, in the library's declaration order, then the call by name.
     * Locals rather than inline arguments for two reasons the first compile found: a default
     * expression may name a sibling parameter (`contentColorFor(containerColor)`), and a slot
     * chosen by an `if` needs its lambda type stated or Kotlin infers `Any`.
     */
    /*
     * Host-default-only parameters are left off the call unless something else names them
     * (`ClassifiedComposable.keptHostDefaults`, ADR-074). Omitting is what a Kotlin caller does
     * when it wants the library's default, and unlike quoting it works when that default names a
     * symbol the library keeps `internal`.
     */
    val omitted = b.classified.hostDefaultOnly
      .map { it.parameter.name }
      .filterNot { it in b.classified.keptHostDefaults }
      .toSet()
    for ((p, v) in b.classified.parameters) {
      if (p.name in omitted) continue
      val type = localType(p.type)
      val line = when (v) {
        is Verdict.HostDefaultOnly -> "val ${p.name}: $type = (${v.defaultText})"
        is Verdict.Unbindable -> error("unbindable parameter reached the emitter: ${b.name}.${p.name}")
        is Verdict.Settable -> when (v.kind) {
          Kind.MODIFIER -> "val ${p.name}: Modifier = dogwoodModifier"
          Kind.SLOT -> {
            val tag = entry.slots.getValue(p.name)
            val render = if (v.slotTakesPadding) {
              "{ padding -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) { RenderChildren(dogwoodNode, $tag, dogwoodScope, dogwoodEvents) } }"
            } else {
              "{ RenderChildren(dogwoodNode, $tag, ${scopeFor(v.slotReceiver)}, dogwoodEvents) }"
            }
            if (v.hasDefault || v.nullable) {
              "val ${p.name}: $type = if (dogwoodNode.children($tag).isNotEmpty()) ($render) else (${v.defaultText ?: "null"})"
            } else "val ${p.name}: $type = $render"
          }
          Kind.EVENT -> {
            val tag = entry.events.getValue(p.name)
            val names = v.eventArguments.indices.map { "a$it" }
            val parts = v.eventArguments.mapIndexed { i, t ->
              if (t == "ClosedFloatingPointRange<Float>") {
                "JsonPrimitive(a$i.start), JsonPrimitive(a$i.endInclusive)"
              } else {
                "JsonPrimitive(a$i)"
              }
            }
            val send = if (names.isEmpty()) "{ dogwoodEvents.send(dogwoodNode, EventTag($tag)) }" else {
              "{ ${names.joinToString(", ")} -> dogwoodEvents.send(dogwoodNode, EventTag($tag), listOf(${parts.joinToString(", ")})) }"
            }
            if (v.hasDefault || v.nullable) {
              val presence = entry.properties.getValue(p.name + "Present")
              "val ${p.name}: $type = if (dogwoodNode.booleanOrNull($presence) == true) ($send) else (${v.defaultText ?: "null"})"
            } else "val ${p.name}: $type = $send"
          }
          else -> {
            val tag = entry.properties.getValue(p.name)
            val reader = hostReader(v.kind, v.libraryType, tag).replace("node.", "dogwoodNode.")
            when {
              v.hasDefault -> "val ${p.name}: $type = $reader ?: (${v.defaultText})"
              v.nullable -> "val ${p.name}: $type = $reader"
              else -> "val ${p.name}: $type = $reader ?: ${hostFallback(v.kind, v.libraryType)}"
            }
          }
        }
      }
      appendLine("      $line")
    }
    appendLine("      $libraryPackage.${b.name}(")
    for ((p, _) in b.classified.parameters) {
      if (p.name in omitted) continue
      appendLine("        ${p.name} = ${p.name},")
    }
    appendLine("      )")
    appendLine("    }")
  }
  appendLine("    else -> return false")
  appendLine("  }")
  appendLine("  return true")
  appendLine("}")
}

private fun emitBindingObject(hostPackage: String, prefix: String, wireName: String, version: Int, dictionary: Dictionary, bound: List<Bound>): String = buildString {
  val stems = bound.map { it.file.substringAfterLast('/').removeSuffix(".kt") }.distinct()
  appendLine("// Generated by dogwood-codegen (generator v2). Do not edit.")
  appendLine("package $hostPackage")
  appendLine()
  appendLine("import androidx.compose.runtime.Composable")
  appendLine("import dev.dogwood.host.DogwoodSegmentBinding")
  appendLine("import dev.dogwood.host.EventSink")
  appendLine("import dev.dogwood.host.LayoutScope")
  appendLine("import dev.dogwood.host.LocalRenderTranscript")
  appendLine("import dev.dogwood.host.LocalSkewReport")
  appendLine("import dev.dogwood.host.WidgetView")
  appendLine("import dev.dogwood.host.composeModifier")
  appendLine("import dev.dogwood.host.unknownProperties")
  appendLine("import dev.dogwood.protocol.widgetTag")
  appendLine()
  appendLine("const val ${prefix}Version: Int = $version")
  appendLine()
  appendLine("val ${prefix}Tags: Set<Int> = setOf(")
  for (e in dictionary.components) appendLine("  widgetTag(${dictionary.segmentId}, ${e.localTag}).value,")
  appendLine(")")
  appendLine()
  appendLine("val ${prefix}Names: Map<Int, String> = mapOf(")
  for (e in dictionary.components) appendLine("  widgetTag(${dictionary.segmentId}, ${e.localTag}).value to \"${e.name}\",")
  appendLine(")")
  appendLine()
  appendLine("/**")
  appendLine(" * Widgets whose contract includes an affordance, mapped to the property tags this client knows.")
  appendLine(" * For a library tier the marking is by name -- `enabled`, `checked`, `selected`, `readOnly` --")
  appendLine(" * because nobody can annotate a library; the coverage report records the rule.")
  appendLine(" */")
  appendLine("private val ${prefix}Affordances: Map<Int, Set<Int>> = mapOf(")
  for (e in dictionary.components.filter { it.safetyRelevant.isNotEmpty() }) {
    appendLine("  widgetTag(${dictionary.segmentId}, ${e.localTag}).value to setOf(${e.properties.values.sorted().joinToString(", ")}),")
  }
  appendLine(")")
  appendLine()
  appendLine("@Composable")
  appendLine("private fun withholdUnsafe(node: WidgetView, modifier: androidx.compose.ui.Modifier): Boolean {")
  appendLine("  val known = ${prefix}Affordances[node.tag.value] ?: return false")
  appendLine("  if (node.unknownProperties(known).isEmpty()) return false")
  appendLine("  LocalSkewReport.current.withheldWidgets += node.tag.value")
  appendLine("  LocalRenderTranscript.current?.detail(node, \"withheld\")")
  appendLine("  androidx.compose.foundation.layout.Box(modifier)")
  appendLine("  return true")
  appendLine("}")
  appendLine()
  appendLine("/** The `$wireName` tier, as something a host registers. Version $version is the library's own, encoded. */")
  appendLine("object ${prefix}Binding : DogwoodSegmentBinding {")
  appendLine("  override val segmentName: String = \"$wireName\"")
  appendLine("  override val segmentVersion: Int = ${prefix}Version")
  appendLine("  override val tags: Set<Int> = ${prefix}Tags")
  appendLine("  override val names: Map<Int, String> = ${prefix}Names")
  appendLine("  @Composable")
  appendLine("  override fun bind(node: WidgetView, scope: LayoutScope, events: EventSink): Boolean {")
  appendLine("    if (node.tag.value !in tags) return false")
  appendLine("    val modifier = node.composeModifier(scope, events)")
  appendLine("    if (withholdUnsafe(node, modifier)) return true")
  appendLine("    return " + stems.joinToString(" ||\n      ") { "bind$prefix$it(node, modifier, scope, events)" })
  appendLine("  }")
  appendLine("}")
}
