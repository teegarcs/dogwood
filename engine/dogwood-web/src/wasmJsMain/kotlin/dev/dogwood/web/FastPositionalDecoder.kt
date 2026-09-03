/*
 * Project Dogwood -- decoding the positional batch, which on the web is the expensive half.
 *
 * The bridge measurement (`tools/web-weight/results/bridge.md`) splits a frame's boundary cost
 * three ways on this platform: encoding 44.7%, transport 0.02%, **decoding 55.3%**. On mobile the
 * same split is encoding 99.4% / transport 1.0%, because decoding happens in compiled host code
 * and is invisible. So on web the decoder is where optimisation effort belongs, and the transport
 * is where it does not --
 * [ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md) says exactly that, and
 * this file is the only place in the web host that tries to be fast.
 *
 * **What makes it faster than `decodePositional`.** In Kotlin/WebAssembly 2.3.20 a `kotlin.String`
 * is a wrapper around a `JsString`, which is an `externref` holding the JavaScript string itself;
 * see `libraries/stdlib/wasm/js/builtins/kotlin/String.kt` in JetBrains/kotlin. Nothing is copied
 * when one crosses the boundary, but every `s[i]` compiles to the `charCodeAt` JavaScript-string
 * builtin -- so a parser that walks the string character by character pays an imported call per
 * character. `toCharArray()` performs one bulk copy into a WebAssembly garbage-collected array,
 * after which every read is a plain array load. ADR-032 calls this "the cheapest known win", and
 * it needs no protocol change: the bytes are identical, only the reading of them differs.
 *
 * **And it is the exact function the toolchain miscompiles.** Kotlin 2.3.20's production
 * `wasm-opt` pass list contains `--gufa`, and under `--closed-world` that pass does not model an
 * imported builtin as able to mutate a garbage-collected object. `intoCharCodeArray` is such a
 * builtin, so the optimiser concludes the array only ever holds its default value and
 * `toCharArray()` silently returns the right number of zeros. Every batch would decode as an empty
 * one, and nothing would throw.
 *
 * That is why `--gufa` is removed from this build's pass list, and why [BulkCopyGate] exists: the
 * removal is a build-file line that a Kotlin upgrade or a copied configuration can undo, and the
 * defect is context-sensitive enough that it cannot be reasoned about locally. The gate compares
 * this decoder against the reference one on real input, in the shipped binary, before the host
 * renders anything.
 */
package dev.dogwood.web

import dev.dogwood.protocol.Change
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChangeKind
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildMove
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.ChildrenTag
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.ModifierTag
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.PropertyTag
import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.WidgetTag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses `[sequence, [change, ...]]` in one pass over a bulk-copied character array.
 *
 * The grammar is `dev.dogwood.protocol.PositionalCodec`'s, unchanged and not extended:
 *
 *     create   = [0, id, widgetTag]
 *     property = [1, id, propertyTag, value]
 *     modifier = [2, id, [[tag, value], ...]]
 *     add      = [3, parentId, slot, childId, index]
 *     remove   = [4, id, slot, index, count]
 *     move     = [5, id, slot, from, to, count]
 *
 * **Arity is checked, and that is not decoration.** Every interesting position in these tuples is
 * an integer, so a payload whose tuples have shifted by one type-checks perfectly: a child
 * identifier is read as an insertion index and the host applies a corrupted tree believing it
 * applied a correct one. This decoder therefore consumes exactly the arity its kind declares and
 * demands the closing bracket immediately after, which catches both a short tuple and a long one.
 *
 * **Failures are whole-batch.** [ProtocolMismatch] is thrown rather than a partial batch returned,
 * for the reason the reference decoder gives: changes within a batch are ordered and
 * interdependent, so applying a prefix leaves the tree in a state the guest never intended and
 * every later batch compounds against it.
 */
class FastPositionalDecoder {
  private var chars: CharArray = CharArray(0)
  private var at: Int = 0
  private var end: Int = 0

  fun decode(payload: String): ChangeBatch {
    // The one bulk copy. Everything below is an array read.
    chars = payload.toCharArray()
    at = 0
    end = chars.size

    skipWhitespace()
    expect('[')
    val sequence = readInt()
    skipWhitespace()
    expect(',')
    skipWhitespace()
    expect('[')

    val changes = ArrayList<Change>()
    skipWhitespace()
    if (peek() != ']') {
      while (true) {
        changes.add(readChange())
        skipWhitespace()
        val c = peek()
        if (c == ',') {
          at++
          skipWhitespace()
        } else {
          break
        }
      }
    }
    expect(']')
    skipWhitespace()
    expect(']')
    // Trailing content is a disagreement about the envelope, not a harmless suffix.
    skipWhitespace()
    if (at != end) throw ProtocolMismatch("trailing content after the batch at offset $at")
    return ChangeBatch(sequence, changes)
  }

  private fun readChange(): Change {
    expect('[')
    val kind = readInt()
    if (kind !in ChangeArity.indices) {
      throw ProtocolMismatch("unknown change kind $kind; this payload is newer than this client")
    }
    val change = when (kind) {
      ChangeKind.CREATE -> {
        val id = Id(readIntAfterComma())
        Create(id, WidgetTag(readIntAfterComma()))
      }

      ChangeKind.PROPERTY -> {
        val id = Id(readIntAfterComma())
        val property = PropertyTag(readIntAfterComma())
        comma()
        PropertySet(id, property, readValue())
      }

      ChangeKind.MODIFIER -> {
        val id = Id(readIntAfterComma())
        comma()
        ModifierSet(id, readChain())
      }

      ChangeKind.CHILD_ADD -> {
        val parent = Id(readIntAfterComma())
        val slot = ChildrenTag(readIntAfterComma())
        val child = Id(readIntAfterComma())
        ChildAdd(parent, slot, child, readIntAfterComma())
      }

      ChangeKind.CHILD_REMOVE -> {
        val id = Id(readIntAfterComma())
        val slot = ChildrenTag(readIntAfterComma())
        val index = readIntAfterComma()
        ChildRemove(id, slot, index, readIntAfterComma())
      }

      else -> {
        val id = Id(readIntAfterComma())
        val slot = ChildrenTag(readIntAfterComma())
        val from = readIntAfterComma()
        val to = readIntAfterComma()
        ChildMove(id, slot, from, to, readIntAfterComma())
      }
    }
    skipWhitespace()
    // The arity check, enforced by position rather than by counting: the tuple must end here.
    if (peek() != ']') {
      throw ProtocolMismatch(
        "change kind $kind carries more than the ${ChangeArity[kind]} elements this client " +
          "expects; the payload's tuple shape disagrees with this client's",
      )
    }
    at++
    return change
  }

  private fun readChain(): List<ModifierElem> {
    skipWhitespace()
    expect('[')
    skipWhitespace()
    if (peek() == ']') {
      at++
      return emptyList()
    }
    val elements = ArrayList<ModifierElem>()
    while (true) {
      skipWhitespace()
      expect('[')
      val tag = ModifierTag(readInt())
      comma()
      val value = readValue()
      skipWhitespace()
      if (peek() != ']') {
        throw ProtocolMismatch("a modifier element is [tag, value]; this one has more")
      }
      at++
      elements.add(ModifierElem(tag, value))
      skipWhitespace()
      if (peek() == ',') at++ else break
    }
    expect(']')
    return elements
  }

  // -------------------------------------------------------------------------------------------
  // Scalars.
  // -------------------------------------------------------------------------------------------

  private fun comma() {
    skipWhitespace()
    expect(',')
    skipWhitespace()
  }

  /**
   * A comma and the integer after it, which is the shape of every structural field but the first.
   *
   * A named helper rather than two calls at each site, because Kotlin evaluates constructor
   * arguments left to right and a tuple read out of order would produce a plausible wrong node
   * rather than an error -- the identifier and the tag swapped, say, which type-checks perfectly.
   */
  private fun readIntAfterComma(): Int {
    comma()
    return readInt()
  }

  private fun peek(): Char {
    if (at >= end) throw ProtocolMismatch("the batch ended in the middle of a change")
    return chars[at]
  }

  private fun expect(c: Char) {
    if (at >= end || chars[at] != c) {
      val found = if (at >= end) "end of input" else "'${chars[at]}'"
      throw ProtocolMismatch("expected '$c' at offset $at, found $found")
    }
    at++
  }

  private fun skipWhitespace() {
    while (at < end) {
      val c = chars[at]
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t') at++ else break
    }
  }

  /**
   * A signed decimal integer.
   *
   * Every structural number in this grammar -- kinds, identifiers, tags, indices, counts -- is one
   * of these. Handled separately from [readValue] because it is the overwhelming majority of what
   * a batch contains and because it must not silently accept the floating-point forms a general
   * number parser would.
   */
  private fun readInt(): Int {
    skipWhitespace()
    val start = at
    var negative = false
    if (at < end && chars[at] == '-') {
      negative = true
      at++
    }
    // Accumulated as a Long and range-checked, because the obvious `Int` accumulator wraps in
    // silence. An identifier of 2^32 + 1 became 1, and the change was then applied to a different
    // *live* node -- a corrupted tree with no exception and no report, which is the exact class
    // ADR-009 exists to close. The reference decoder refuses the same input, and after this so
    // does the fast path.
    var value = 0L
    var digits = 0
    while (at < end) {
      val c = chars[at]
      if (c < '0' || c > '9') break
      value = value * 10 + (c.code - '0'.code)
      digits++
      at++
      if (value > OVERFLOW_GUARD) {
        throw ProtocolMismatch("integer at offset $start is out of range for this protocol")
      }
    }
    if (digits == 0) throw ProtocolMismatch("expected an integer at offset $start")
    val signed = if (negative) -value else value
    if (signed > Int.MAX_VALUE.toLong() || signed < Int.MIN_VALUE.toLong()) {
      throw ProtocolMismatch("integer at offset $start is out of range for this protocol")
    }
    return signed.toInt()
  }

  /**
   * Any JSON value, as a [JsonElement].
   *
   * Property values and modifier arguments are not constrained by the positional grammar: a
   * deferred expression is `[factory, args...]`, a text recipe carries strings and numbers, and a
   * colour token is a nested array. So this half really is a general JSON reader -- but it is only
   * ever entered from a value position, which is what keeps the structural fast path above free of
   * dispatch.
   */
  private fun readValue(): JsonElement {
    skipWhitespace()
    return when (val c = peek()) {
      '[' -> {
        at++
        skipWhitespace()
        if (peek() == ']') {
          at++
          JsonArray(emptyList())
        } else {
          val items = ArrayList<JsonElement>()
          while (true) {
            items.add(readValue())
            skipWhitespace()
            if (peek() == ',') {
              at++
            } else {
              break
            }
          }
          expect(']')
          JsonArray(items)
        }
      }

      '{' -> {
        at++
        skipWhitespace()
        if (peek() == '}') {
          at++
          JsonObject(emptyMap())
        } else {
          val entries = LinkedHashMap<String, JsonElement>()
          while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            entries[key] = readValue()
            skipWhitespace()
            if (peek() == ',') {
              at++
            } else {
              break
            }
          }
          expect('}')
          JsonObject(entries)
        }
      }

      '"' -> JsonPrimitive(readString())

      't' -> {
        expectLiteral("true")
        JsonPrimitive(true)
      }

      'f' -> {
        expectLiteral("false")
        JsonPrimitive(false)
      }

      'n' -> {
        expectLiteral("null")
        JsonNull
      }

      else -> {
        if (c != '-' && (c < '0' || c > '9')) {
          throw ProtocolMismatch("expected a value at offset $at, found '$c'")
        }
        readNumber()
      }
    }
  }

  /**
   * A number in a value position, kept as an unquoted literal.
   *
   * `JsonPrimitive(String, isString = false)` is not public, so the number is reconstructed
   * through the typed constructors. The distinction matters: the host's own readers go through
   * `JsonPrimitive.content`, and a value that arrived as `3` must not come back as `"3"` or as
   * `3.0` -- `WidgetView.int` parses the content with `toIntOrNull`, and `"3.0"` is not an
   * integer. So an integral literal with no fraction, exponent or overflow becomes an `Int`, and
   * everything else becomes a `Double`.
   */
  private fun readNumber(): JsonPrimitive {
    val start = at
    if (at < end && chars[at] == '-') at++
    var integral = true
    while (at < end) {
      val c = chars[at]
      if (c in '0'..'9') {
        at++
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        integral = false
        at++
      } else {
        break
      }
    }
    val text = concatRange(start, at)
    if (integral) {
      val asInt = text.toIntOrNull()
      if (asInt != null) return JsonPrimitive(asInt)
      val asLong = text.toLongOrNull()
      if (asLong != null) return JsonPrimitive(asLong)
    }
    return JsonPrimitive(
      text.toDoubleOrNull() ?: throw ProtocolMismatch("malformed number at offset $start"),
    )
  }

  private fun readString(): String {
    expect('"')
    val start = at
    // The common case is a run with nothing to unescape, and it is worth its own path: a batch is
    // mostly short label strings, and building one through a StringBuilder character by character
    // costs an allocation and a copy per string for no reason.
    var simple = true
    while (at < end) {
      val c = chars[at]
      if (c == '"') break
      if (c == '\\') {
        simple = false
        break
      }
      at++
    }
    if (simple) {
      val text = concatRange(start, at)
      expect('"')
      return text
    }

    val builder = StringBuilder()
    builder.appendRange(chars, start, at)
    while (at < end) {
      val c = chars[at]
      if (c == '"') break
      if (c != '\\') {
        builder.append(c)
        at++
        continue
      }
      at++
      if (at >= end) throw ProtocolMismatch("a string ended inside an escape")
      when (val escape = chars[at]) {
        '"' -> builder.append('"')
        '\\' -> builder.append('\\')
        '/' -> builder.append('/')
        'b' -> builder.append('\b')
        'f' -> builder.append('\u000C')
        'n' -> builder.append('\n')
        'r' -> builder.append('\r')
        't' -> builder.append('\t')
        'u' -> {
          if (at + 4 >= end) throw ProtocolMismatch("a \\u escape ran off the end of the batch")
          var code = 0
          for (offset in 1..4) {
            code = (code shl 4) or hexDigit(chars[at + offset])
          }
          builder.append(code.toChar())
          at += 4
        }
        else -> throw ProtocolMismatch("unknown escape '\\$escape' at offset $at")
      }
      at++
    }
    expect('"')
    return builder.toString()
  }

  private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c.code - '0'.code
    in 'a'..'f' -> c.code - 'a'.code + 10
    in 'A'..'F' -> c.code - 'A'.code + 10
    else -> throw ProtocolMismatch("'$c' is not a hexadecimal digit")
  }

  private fun concatRange(from: Int, to: Int): String = chars.concatToString(from, to)

  private fun expectLiteral(literal: String) {
    for (offset in literal.indices) {
      if (at + offset >= end || chars[at + offset] != literal[offset]) {
        throw ProtocolMismatch("expected '$literal' at offset $at")
      }
    }
    at += literal.length
  }
}

/**
 * The per-kind tuple arities, as this decoder sees them.
 *
 * `ChangeKind.arity` in the protocol module is `internal`, so it cannot be read from here. The
 * numbers are therefore restated -- and the restatement is exactly the kind of duplication that
 * lets two halves of a grammar drift, so [BulkCopyGate] compares this decoder against the
 * reference decoder on a payload that exercises every change kind. A disagreement about arity
 * shows up there as a batch that decodes differently, which is a failure rather than a silently
 * wrong tree.
 */
// The arity table is NOT restated here. It was, and that made the fast path a second copy of the
// grammar in exactly the sense ADR-009 abolished -- two tables nothing compared, one of which
// would have drifted the first time a change kind gained a field.
/** Bail out before a Long could itself wrap; any real identifier is far below this. */
private const val OVERFLOW_GUARD = Int.MAX_VALUE.toLong() + 1L

private val ChangeArity get() = ChangeKind.arity
