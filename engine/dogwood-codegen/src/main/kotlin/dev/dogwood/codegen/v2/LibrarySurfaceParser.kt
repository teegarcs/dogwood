/*
 * Project Dogwood -- reading a library's common source set as a surface.
 *
 * The same Kotlin frontend the v1 parser uses, over many files instead of one. It reads
 * declarations, not resolved types: for the host binding that is enough because the binding is
 * emitted with the library file's own imports, so a name resolves there exactly as it resolved
 * here; for the guest stub, types go through a mapping table and a type the table does not know
 * is a parameter the guest cannot set rather than a component it cannot call
 * (plans/generator-v2.md §0).
 *
 * What is kept: public, top-level, uppercase-named `@Composable` functions. What is dropped and
 * why: `private`/`internal`/`protected` (not callable); `actual` (the `expect` in the same tree is
 * the declaration, and emitting both would bind one function twice); nested and member functions
 * (a binding calls a top-level function by name). Deprecated functions are kept and *marked*, so
 * the policy of whether to bind them is taken downstream and reported, not silently here.
 */
package dev.dogwood.codegen.v2

import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

class LibrarySurfaceParser {

  private val environment: KotlinCoreEnvironment = KotlinCoreEnvironment.createForProduction(
    Disposer.newDisposable("dogwood-codegen-v2"),
    CompilerConfiguration(),
    EnvironmentConfigFiles.JVM_CONFIG_FILES,
  )

  private val factory = PsiFileFactory.getInstance(environment.project)

  /** Parses every `.kt` under [commonMain] (a `commonMain/` directory, or a directory containing one). */
  fun parseModule(module: String, root: File): LibrarySurface {
    val base = if (root.name == "commonMain") root else File(root, "commonMain")
    val files = base.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .sortedBy { it.relativeTo(base).path }
      .toList()
    val composables = mutableListOf<LibraryComposable>()
    val internalNames = mutableSetOf<String>()
    val publicMarkers = mutableSetOf<String>()
    val internalMarkers = mutableSetOf<String>()
    for (file in files) {
      val text = file.readText()
      composables += parseFile(file.relativeTo(base).path, text)
      val kt = factory.createFileFromText(file.name, KotlinLanguage.INSTANCE, text) as KtFile
      for (declaration in kt.declarations) {
        val name = (declaration as? KtNamedDeclaration)?.name ?: continue
        val internal = declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) || declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)
        val isMarker = declaration is KtClass && declaration.isAnnotation() && name.startsWith("Experimental")
        when {
          isMarker && internal -> internalMarkers += name
          isMarker -> publicMarkers += name
          internal && name.first().isUpperCase() -> internalNames += name
        }
      }
    }
    return LibrarySurface(module, composables, internalNames, publicMarkers, internalMarkers)
  }

  /** One file's contribution. Exposed for tests, which pass source text directly. */
  fun parseFile(relativePath: String, source: String): List<LibraryComposable> {
    val file = factory.createFileFromText(relativePath.substringAfterLast('/'), KotlinLanguage.INSTANCE, source) as KtFile
    val packageName = file.packageFqName.asString()
    val imports = file.importDirectives.map { it.text.replace(Regex("\\s+"), " ").trim() }
    val fileOptIns = file.annotationEntries.flatMap { it.optInMarkers() }.toSet()

    return file.declarations
      .filterIsInstance<KtNamedFunction>()
      .filter { it.isCandidate() }
      .map { function ->
        LibraryComposable(
          name = function.name!!,
          file = relativePath,
          packageName = packageName,
          imports = imports,
          parameters = function.valueParameters.map { p ->
            LibraryParameter(
              name = p.name ?: error("unnamed parameter on ${function.name} in $relativePath"),
              type = p.typeReference?.text?.replace(Regex("\\s+"), " ")?.trim() ?: "Unit",
              defaultText = p.defaultValue?.text?.trim(),
              isVararg = p.hasModifier(KtTokens.VARARG_KEYWORD),
            )
          },
          deprecated = function.annotationEntries.any { it.shortName?.asString() == "Deprecated" },
          optIns = fileOptIns + function.annotationEntries.flatMap { it.optInMarkers() },
          isExpect = function.hasModifier(KtTokens.EXPECT_KEYWORD),
          isInline = function.hasModifier(KtTokens.INLINE_KEYWORD),
          typeParameters = function.typeParameters.map { it.text },
          receiver = function.receiverTypeReference?.text?.replace(Regex("\\s+"), " ")?.trim(),
        )
      }
  }

  private fun KtNamedFunction.isCandidate(): Boolean {
    val name = name ?: return false
    if (!name.first().isUpperCase()) return false
    if (annotationEntries.none { it.shortName?.asString() == "Composable" }) return false
    if (hasModifier(KtTokens.PRIVATE_KEYWORD) || hasModifier(KtTokens.INTERNAL_KEYWORD) ||
      hasModifier(KtTokens.PROTECTED_KEYWORD)
    ) return false
    // `actual` is the platform half of a declaration the `expect` already made; binding both
    // would be one function twice, and the `expect` is the one every platform shares.
    if (hasModifier(KtTokens.ACTUAL_KEYWORD)) return false
    return true
  }

  /**
   * The opt-in markers an annotation entry names.
   *
   * `@OptIn(ExperimentalMaterial3Api::class)` names them as arguments; `@ExperimentalMaterial3Api`
   * *is* one. Anything whose simple name begins `Experimental` is taken to be a marker, which is
   * the convention every Compose library follows and the reason the generated binding can opt in
   * file-wide without enumerating them by hand.
   */
  private fun KtAnnotationEntry.optInMarkers(): List<String> {
    val name = shortName?.asString() ?: return emptyList()
    return when {
      name == "OptIn" -> valueArguments.mapNotNull { argument ->
        argument.getArgumentExpression()?.text?.removeSuffix("::class")?.substringAfterLast('.')
      }
      name.startsWith("Experimental") -> listOf(name)
      else -> emptyList()
    }
  }
}
