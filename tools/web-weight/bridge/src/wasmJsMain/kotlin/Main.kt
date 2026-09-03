/*
 * Project Dogwood -- what a per-frame tree-diff costs to cross from JavaScript into
 * Kotlin/WebAssembly, measured in a real browser.
 *
 * On mobile the guest runs inside an embedded QuickJS interpreter and the diff crosses as a
 * String through Zipline's `CallChannel.call(String): String`. That measurement said the crossing
 * is 99.4% guest-side encoding, and that positional JavaScript Object Notation (JSON) beats binary
 * formats because `JSON.stringify` is native C inside QuickJS while a binary encoder is
 * interpreted Kotlin.
 *
 * On web none of that holds. The guest is ordinary JavaScript in the browser's own just-in-time
 * (JIT) compiled engine, there is no CallChannel, and nothing forces a string channel. This module
 * exports one function per candidate transport so a page can time them against each other.
 *
 * Every exported function returns a checksum. The page accumulates it into a sink it later prints,
 * which is what stops the just-in-time compiler from deleting the work being measured.
 */
@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package dogwood.bridge

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

fun main() {
  // Nothing to do at startup. The page drives every measurement through the exports below.
}

// ---------------------------------------------------------------------------------------------
// Calls back into JavaScript.
//
// Path 2 and path 4 need JavaScript to write into the module's linear memory, and only Kotlin
// knows a safe address to write at, so Kotlin allocates first and then asks JavaScript to fill.
// The page installs these two globals before running anything.
// ---------------------------------------------------------------------------------------------

/** Asks the page to write the currently selected payload's Unicode Transformation Format 8-bit
 *  (UTF-8) bytes at [addr], returning how many bytes it wrote. */
private fun jsFillBytes(addr: Int, cap: Int): Int = js("globalThis.__dogwoodFillBytes(addr, cap)")

/** Asks the page to write the currently selected payload's flat 32-bit integer encoding at
 *  [addr], returning how many integers it wrote. */
private fun jsFillInt32(addr: Int, cap: Int): Int = js("globalThis.__dogwoodFillInt32(addr, cap)")

private fun jsIsArray(v: JsAny): Boolean = js("Array.isArray(v)")

private fun jsIsNumber(v: JsAny): Boolean = js("typeof v === 'number'")

// ---------------------------------------------------------------------------------------------
// Path 1 -- the String argument. The direct analogue of today's mobile design.
//
// In Kotlin/WebAssembly 2.3.20 a `kotlin.String` is a wrapper around a `JsString`, which is an
// `externref` holding the JavaScript string itself; see
// libraries/stdlib/wasm/js/builtins/kotlin/String.kt in JetBrains/kotlin. Passing one across the
// boundary therefore hands over a reference and copies nothing. The cost has not vanished, it has
// moved: every `s[i]` compiles to the `charCodeAt` JavaScript-string builtin, and `String.length`
// to the `length` builtin, so a parser that walks the string pays a builtin call per character.
// ---------------------------------------------------------------------------------------------

/** Path 1 transfer floor: takes the argument and reads one cheap property. If passing a String
 *  cost a transcoding copy, this would grow with payload size. */
@JsExport
fun stringTouch(s: String): Int = s.length

/** Path 1a: parse straight off the JavaScript string, one builtin call per character. */
@JsExport
fun stringParseDirect(s: String): Int = parseChars(s)

/** Path 1b: one bulk `intoCharCodeArray` copy into a WebAssembly garbage-collected (GC) array of
 *  16-bit characters, then parse that with plain array reads. Trades one native bulk copy plus
 *  the garbage it creates against per-character boundary calls. */
@JsExport
fun stringParseViaCharArray(s: String): Int = parseCharArray(s.toCharArray())

/** Path 1c: the bulk copy alone, to price it apart from the parse that follows it. */
@JsExport
fun stringToCharArrayOnly(s: String): Int = s.toCharArray().size

// ---------------------------------------------------------------------------------------------
// A probe for a miscompilation in the production optimiser, not a measurement.
//
// Kotlin 2.3.20 finishes a production WebAssembly build by running Binaryen's `wasm-opt` with
// `--closed-world ... -O3 -O3 --gufa -O3 --type-merging -O3 -Oz`. Under that pass list
// `String.toCharArray()` silently returns an array of the right length filled with zeros, because
// the bulk copy is performed by the imported `wasm:js-string` `intoCharCodeArray` builtin writing
// into a WebAssembly garbage-collected array, and `--gufa` under `--closed-world` does not model
// an import as being able to mutate a garbage-collected object. It therefore concludes the array
// only ever holds its default value.
//
// `probeBulkCopy` returns the first eight character codes of its argument packed into an integer,
// once via the bulk copy and once via per-character access. They must be equal. When they are not,
// path 1b in this harness is measuring an empty loop rather than a parse.
// ---------------------------------------------------------------------------------------------

@JsExport
fun probeBulkCopy(s: String): Int {
  val a = s.toCharArray()
  var sum = 0
  for (i in 0 until minOf(8, a.size)) sum = sum * 1000 + a[i].code
  return sum
}

@JsExport
fun probePerChar(s: String): Int {
  var sum = 0
  for (i in 0 until minOf(8, s.length)) sum = sum * 1000 + s[i].code
  return sum
}

@JsExport
fun probeBulkCopySize(s: String): Int = s.toCharArray().size

// A string held on the Kotlin side, so the parse can be timed without the call that delivered it.
private var cached: String = ""
private var cachedChars: CharArray = CharArray(0)

@JsExport
fun cacheString(s: String) {
  cached = s
  cachedChars = s.toCharArray()
}

/** Parse-only, no argument crossing at all: the lower bound for path 1a. */
@JsExport
fun parseCachedDirect(): Int = parseChars(cached)

/** Parse-only over an already-copied character array: the lower bound for path 1b. */
@JsExport
fun parseCachedCharArray(): Int = parseCharArray(cachedChars)

// ---------------------------------------------------------------------------------------------
// Path 2 -- bytes written straight into linear memory.
//
// The option that is impossible over the mobile transport and trivial here. Note that Kotlin
// objects do NOT live in linear memory: Kotlin/WebAssembly compiles to WebAssembly garbage
// collection (WasmGC), so a `ByteArray` is a GC array that JavaScript cannot take a `Uint8Array`
// view over. Linear memory is still there and still exported, and `kotlin.wasm.unsafe` is the
// supported way to reach it, so the byte path allocates a scratch region, hands its address to
// JavaScript, and reads the bytes back with `i32.load8_u` instructions.
// ---------------------------------------------------------------------------------------------

/** Path 2 transfer floor: allocate, let JavaScript encode into the region, read a single byte. */
@JsExport
fun bytesTouch(cap: Int): Int = withScopedMemoryAllocator { allocator ->
  val base = allocator.allocate(cap)
  val n = jsFillBytes(base.address.toInt(), cap)
  if (n <= 0) 0 else n + base.loadByte().toInt() + (base + (n - 1)).loadByte().toInt()
}

/** Path 2 in full: allocate, JavaScript encodes into the region, Kotlin parses the bytes. */
@JsExport
fun bytesParse(cap: Int): Int = withScopedMemoryAllocator { allocator ->
  val base = allocator.allocate(cap)
  val n = jsFillBytes(base.address.toInt(), cap)
  parseBytes(base, n)
}

// ---------------------------------------------------------------------------------------------
// Path 4 -- the floor of the whole design space: no textual encoding at all.
//
// JavaScript copies a flat `Int32Array` of the diff into linear memory and Kotlin reads 32-bit
// integers out of it. There is nothing to parse, so this is the cheapest a diff of this shape can
// possibly cross. It is included as a bound, not as a proposal: strings inside the diff need a
// side table, which this does not model.
// ---------------------------------------------------------------------------------------------

@JsExport
fun int32Read(capBytes: Int): Int = withScopedMemoryAllocator { allocator ->
  val base = allocator.allocate(capBytes + 4)
  // Round up to a four-byte boundary; `i32.load` on an unaligned address is legal in WebAssembly
  // but slower, and a real host would align.
  val aligned = base + ((4 - (base.address.toInt() and 3)) and 3)
  val n = jsFillInt32(aligned.address.toInt(), capBytes)
  var sum = 0
  var i = 0
  while (i < n) {
    sum += (aligned + (i shl 2)).loadInt()
    i++
  }
  sum
}

// ---------------------------------------------------------------------------------------------
// Path 3 -- walking a JavaScript array from Kotlin without serialising at all.
//
// `JsArray` is an external type, so `a[i]` is an imported JavaScript function call, not a memory
// read. Nothing is copied, but every single element read is a boundary crossing. Two variants are
// measured because the difference between them is the cost of type dispatch:
//   - flat: the page supplies one flat array of numbers, and Kotlin reads it with no checks.
//   - nested: the real positional shape, so Kotlin must ask JavaScript what each element is.
// ---------------------------------------------------------------------------------------------

@JsExport
fun walkFlatJsArray(a: JsArray<JsNumber>): Int {
  var sum = 0
  val n = a.length
  var i = 0
  while (i < n) {
    sum += a[i]!!.toDouble().toInt()
    i++
  }
  return sum
}

@JsExport
fun walkNestedJsArray(a: JsArray<JsAny>): Int = walkNested(a)

private fun walkNested(a: JsArray<JsAny>): Int {
  var sum = 0
  val n = a.length
  var i = 0
  while (i < n) {
    val v = a[i]
    if (v != null) {
      sum += when {
        jsIsNumber(v) -> v.unsafeCast<JsNumber>().toDouble().toInt()
        jsIsArray(v) -> walkNested(v.unsafeCast<JsArray<JsAny>>())
        else -> v.unsafeCast<JsString>().toString().length
      }
    }
    i++
  }
  return sum
}

// ---------------------------------------------------------------------------------------------
// The parsers.
//
// One algorithm, three backing stores, so the three paths are compared like with like. The format
// is positional JSON as the guest emits it -- nested arrays of small integers with occasional
// short strings, e.g. `[1,[[0,1,2],[1,1,1,"text"],[3,0,1,1,0]]]`. Structural characters are
// skipped rather than validated and string escapes are not handled, because the payloads this
// harness generates contain none; a production parser would cost slightly more, equally on all
// three paths.
// ---------------------------------------------------------------------------------------------

private fun parseChars(s: String): Int {
  var checksum = 0
  var i = 0
  val n = s.length
  while (i < n) {
    val c = s[i].code
    if (c == QUOTE) {
      i++
      var h = 0
      while (i < n) {
        val d = s[i].code
        if (d == QUOTE) break
        h = h * 31 + d
        i++
      }
      i++
      checksum += h
    } else if (c == MINUS || (c >= ZERO && c <= NINE)) {
      var negative = false
      var j = i
      if (c == MINUS) {
        negative = true
        j++
      }
      var v = 0
      while (j < n) {
        val d = s[j].code
        if (d < ZERO || d > NINE) break
        v = v * 10 + (d - ZERO)
        j++
      }
      checksum += if (negative) -v else v
      i = j
    } else {
      i++
    }
  }
  return checksum
}

private fun parseCharArray(s: CharArray): Int {
  var checksum = 0
  var i = 0
  val n = s.size
  while (i < n) {
    val c = s[i].code
    if (c == QUOTE) {
      i++
      var h = 0
      while (i < n) {
        val d = s[i].code
        if (d == QUOTE) break
        h = h * 31 + d
        i++
      }
      i++
      checksum += h
    } else if (c == MINUS || (c >= ZERO && c <= NINE)) {
      var negative = false
      var j = i
      if (c == MINUS) {
        negative = true
        j++
      }
      var v = 0
      while (j < n) {
        val d = s[j].code
        if (d < ZERO || d > NINE) break
        v = v * 10 + (d - ZERO)
        j++
      }
      checksum += if (negative) -v else v
      i = j
    } else {
      i++
    }
  }
  return checksum
}

private fun parseBytes(base: Pointer, n: Int): Int {
  var checksum = 0
  var i = 0
  while (i < n) {
    val c = (base + i).loadByte().toInt()
    if (c == QUOTE) {
      i++
      var h = 0
      while (i < n) {
        val d = (base + i).loadByte().toInt()
        if (d == QUOTE) break
        h = h * 31 + d
        i++
      }
      i++
      checksum += h
    } else if (c == MINUS || (c >= ZERO && c <= NINE)) {
      var negative = false
      var j = i
      if (c == MINUS) {
        negative = true
        j++
      }
      var v = 0
      while (j < n) {
        val d = (base + j).loadByte().toInt()
        if (d < ZERO || d > NINE) break
        v = v * 10 + (d - ZERO)
        j++
      }
      checksum += if (negative) -v else v
      i = j
    } else {
      i++
    }
  }
  return checksum
}

private const val QUOTE = 34   // '"'
private const val MINUS = 45   // '-'
private const val ZERO = 48    // '0'
private const val NINE = 57    // '9'
