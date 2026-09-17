/*
 * Project Dogwood -- the authoring check, and what it must not do.
 *
 * Layer 1 has required this since the first draft, and stated the reason a rule in a document was
 * never going to be enough: **nothing about these APIs fails.** `animateFloatAsState` in a guest
 * compiles, runs, animates, and crosses the boundary sixty times a second. The screen looks right.
 *
 * Half of these tests are about false positives, and that is the right proportion. A check that
 * rejects a comment, a similarly-named helper, or the word inside a string is a check that gets
 * suppressed within a week, and a suppressed check enforces nothing at all.
 */
package dev.dogwood.codegen

import dev.dogwood.codegen.guest.checkGuestSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestCheckTest {

  private fun violations(source: String) = checkGuestSource("Screen.kt", source)

  @Test
  fun ordinaryGuestCodePasses() {
    val clean = """
      package dev.acme
      @Composable
      fun Screen() {
        var count by remember { mutableStateOf(0) }
        Column {
          Text("tapped ${'$'}count")
          PrimaryButton(label = "tap", onClick = { count += 1 })
          Box(modifier = Modifier.alpha(animate(0.5f, Animations.tween(200))))
        }
      }
    """.trimIndent()
    assertEquals(emptyList(), violations(clean))
  }

  @Test
  fun anAnimationStateCallIsRejectedWithItsReplacement() {
    val source = """
      @Composable
      fun Screen() {
        val alpha by animateFloatAsState(targetValue = 1f)
      }
    """.trimIndent()
    val found = violations(source)
    assertEquals(1, found.size, found.toString())
    assertEquals(3, found.single().line)
    assertEquals("animateFloatAsState", found.single().api.name)
    assertTrue("declare a target" in found.single().api.instead, found.single().api.instead)
  }

  @Test
  fun aGuestFrameLoopIsRejected() {
    // The one that does not look like an animation API at all, and is the same thing underneath.
    val found = violations("suspend fun pump() { withFrameNanos { it } }")
    assertEquals(listOf("withFrameNanos"), found.map { it.api.name })
  }

  @Test
  fun aResourceLoaderIsRejected() {
    val found = violations("val p = painterResource(R.drawable.x)")
    assertEquals(listOf("painterResource"), found.map { it.api.name })
  }

  @Test
  fun aFunctionReferenceIsRejectedToo() {
    // Passing one of these as a reference is the same per-frame state arriving by a longer road.
    val found = violations("val f = ::rememberInfiniteTransition")
    assertEquals(listOf("rememberInfiniteTransition"), found.map { it.api.name })
  }

  @Test
  fun aLineCommentIsNotAViolation() {
    // The sample's About screen contains exactly this: a comment explaining why the API is
    // forbidden. A checker that rejected the documentation of its own rule would be uninstalled
    // the same afternoon.
    val found = violations("// Compose's own animateFloatAsState(x) does this per frame; we do not.")
    assertEquals(emptyList(), found)
  }

  @Test
  fun aBlockCommentIsNotAViolation() {
    val found = violations(
      """
        /*
         * See ADR-020: animateFloatAsState(target) is per-frame state by construction.
         */
        fun ok() = 1
      """.trimIndent(),
    )
    assertEquals(emptyList(), found)
  }

  @Test
  fun aStringLiteralIsNotAViolation() {
    val found = violations("""val why = "animateFloatAsState(x) is forbidden"""")
    assertEquals(emptyList(), found)
  }

  @Test
  fun aRawStringIsNotAViolation() {
    val found = violations("val why = \"\"\"withFrameNanos(x) is forbidden\"\"\"")
    assertEquals(emptyList(), found)
  }

  @Test
  fun aSimilarlyNamedHelperIsNotAViolation() {
    // `myAnimateFloatAsState` and `state.animateFloatAsStateLike` are somebody's own code.
    val found = violations(
      """
        fun myAnimateFloatAsState() = 1
        val a = holder.animateFloatAsStateLike(2)
      """.trimIndent(),
    )
    assertEquals(emptyList(), found)
  }

  @Test
  fun theReportedLineSurvivesStripping() {
    // Every stripped character becomes a space rather than disappearing. A scanner that collapsed
    // the text would report the right problem at the wrong place, which sends somebody to read a
    // line that is fine.
    val source = """
      /*
       * A long comment
       * spanning several lines
       */
      fun ok() = 1
      val bad = animateDpAsState(4.dp)
    """.trimIndent()
    assertEquals(6, violations(source).single().line)
  }

  @Test
  fun everyForbiddenApiCarriesAReplacement() {
    // A rejection with no alternative is a rejection somebody works around.
    val all = dev.dogwood.codegen.guest.FORBIDDEN_GUEST_APIS +
      dev.dogwood.codegen.guest.CONTROLLED_TEXT_FIELD
    for (api in all) {
      assertTrue(api.because.isNotBlank(), api.name)
      assertTrue(api.instead.isNotBlank(), "${api.name} has no replacement")
    }
  }

  // --- The controlled text fields (ADR-019). ------------------------------------------------
  //
  // These do not compile: nothing a guest can see declares `OutlinedTextField`. The check is not
  // stopping a build that would otherwise pass -- it is replacing "unresolved reference" with the
  // name of the component that does exist.

  @Test
  fun anOutlinedTextFieldIsRejectedAndNamesTextInput() {
    val found = violations("OutlinedTextField(value = name, onValueChange = { name = it })")
    assertEquals(listOf("OutlinedTextField"), found.map { it.api.name })
    assertTrue("TextInput" in found.single().api.instead, found.single().api.instead)
    assertTrue("keystroke" in found.single().api.because, found.single().api.because)
  }

  @Test
  fun aBasicTextFieldIsRejected() {
    val found = violations("BasicTextField(value = q, onValueChange = ::set)")
    assertEquals(listOf("BasicTextField"), found.map { it.api.name })
  }

  @Test
  fun aSearchBarIsRejected() {
    // Excluded in the same set by generator v2's classifier, and for the same reason: the thing
    // inside it is a controlled text field.
    val found = violations("SearchBar(inputField = { }, expanded = false, onExpandedChange = { })")
    assertEquals(listOf("SearchBar"), found.map { it.api.name })
  }

  @Test
  fun theSupportedWrapperIsNotAViolation() {
    // `dev.dogwood.compose.TextField(state = ...)` is what a guest is *supposed* to write, and
    // `ExploreScreen.kt` and `AboutScreen.kt` both write it. A check that rejected the API the
    // engine recommends would be uninstalled faster than one that rejected a comment.
    val source = """
      val card = rememberTextFieldState()
      TextField(
        state = card,
        modifier = Modifier.fillMaxWidth(),
        label = TextValue("Card number"),
        mask = "#### #### #### ####",
        showCounter = true,
      )
    """.trimIndent()
    assertEquals(emptyList(), violations(source))
  }

  @Test
  fun theControlledOverloadOfTheSameNameIsRejected() {
    // The marker is an argument, not a name, and it is never on the line the name is on: a real
    // call spans five lines. The check balances parentheses rather than reading one line.
    val source = """
      TextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search") },
      )
    """.trimIndent()
    val found = violations(source)
    assertEquals(1, found.size, found.toString())
    assertEquals(1, found.single().line)
    assertTrue("TextInput" in found.single().api.instead, found.single().api.instead)
  }

  @Test
  fun theControlledOverloadIsReportedOnItsOwnLine() {
    val source = """
      @Composable
      fun Screen() {

        TextField(
          value = query,
          onValueChange = { query = it },
        )
      }
    """.trimIndent()
    assertEquals(4, violations(source).single().line)
  }

  @Test
  fun aWrapperCallWithALambdaArgumentIsNotAViolation() {
    // Parenthesis balancing has to survive a nested call in the argument list, or the scan would
    // run past the end of this call and find the next call's `onValueChange`.
    val source = """
      TextField(state = card, label = TextValue(strings("cardNumber")))
      TextInput(text = t, version = v, onValueChange = { a, b -> set(a, b) })
    """.trimIndent()
    assertEquals(emptyList(), violations(source))
  }

  @Test
  fun aStateHolderFactoryIsNotAViolation() {
    assertEquals(emptyList(), violations("val q = rememberTextFieldState(initialText = \"\")"))
  }
}
