/*
 * Project Dogwood -- generator v2 reads a library, not a surface.
 *
 * A library file has things a surface never has: `expect` declarations with platform actuals,
 * `internal` helpers beside public functions, deprecated overloads kept for compatibility,
 * experimental opt-in markers at file and function level, and several overloads of one name in
 * one file. Each is a way to bind the wrong thing silently, and each is pinned here.
 */
package dev.dogwood.codegen.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FIXTURE = """
@file:OptIn(ExperimentalFoundationApi::class)
package androidx.compose.material3

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.internal.Strings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun Platformish(modifier: Modifier = Modifier)

@Composable
actual fun Platformish(modifier: Modifier) {}

@Composable
internal fun Helper(text: String) {}

@Composable
private fun Hidden(text: String) {}

@Deprecated("use the other one")
@Composable
fun Widget(text: String) {}

@Composable
@ExperimentalMaterial3Api
fun Widget(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {}

fun lowercase() {}

@Composable
fun lowercaseComposable() {}
"""

class LibrarySurfaceParserTest {

  private val parsed = LibrarySurfaceParser().parseFile("Fixture.kt", FIXTURE)

  @Test
  fun keepsPublicUppercaseComposablesAndTheExpectHalf() {
    assertEquals(listOf("Platformish", "Widget", "Widget"), parsed.map { it.name })
    assertTrue(parsed.first { it.name == "Platformish" }.isExpect)
    // `actual` is dropped: binding both halves would bind one function twice.
    assertEquals(1, parsed.count { it.name == "Platformish" })
  }

  @Test
  fun dropsInternalPrivateAndLowercase() {
    assertFalse(parsed.any { it.name in setOf("Helper", "Hidden", "lowercase", "lowercaseComposable") })
  }

  @Test
  fun recordsDeprecationRatherThanDeciding() {
    val widgets = parsed.filter { it.name == "Widget" }
    assertTrue(widgets[0].deprecated)
    assertFalse(widgets[1].deprecated)
  }

  @Test
  fun collectsOptInsFromFileAndFunction() {
    val experimental = parsed.filter { it.name == "Widget" }[1]
    assertEquals(setOf("ExperimentalFoundationApi", "ExperimentalMaterial3Api"), experimental.optIns)
  }

  @Test
  fun capturesDefaultsVerbatimAndTheFileImports() {
    val experimental = parsed.filter { it.name == "Widget" }[1]
    assertEquals("Modifier", experimental.parameters[1].defaultText)
    assertEquals("true", experimental.parameters[2].defaultText)
    assertEquals(null, experimental.parameters[0].defaultText)
    assertEquals("@Composable RowScope.() -> Unit", experimental.parameters[3].type)
    assertTrue("import androidx.compose.foundation.layout.RowScope" in experimental.imports)
    assertEquals("androidx.compose.material3", experimental.packageName)
  }
}
