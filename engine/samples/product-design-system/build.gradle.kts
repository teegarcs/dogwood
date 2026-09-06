/*
 * Acme's design system -- what a product's own component module looks like.
 *
 * This module exists to prove the registration mechanism against something the engine has never
 * heard of. Everything in it is what a real product would write, and the list is short:
 *
 *   - a surface file describing its components (`surface/`),
 *   - one `*Impl` per component (`src/commonMain/`),
 *   - this build file, pointing the generator at the surface and claiming a segment identifier.
 *
 * It writes no dispatch, no property decoding, no tag arithmetic and no skew handling. Those are
 * generated from the surface, by the same generator and the same emitters that produce Dogwood's
 * own segment -- which is the claim being tested. A mechanism with one caller is not a mechanism.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin {
  jvmToolchain(21)
  jvm()
  androidTarget {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }

  sourceSets {
    commonMain {
      // `api`, not `implementation`: a consumer registers `AcmeDesignSystemBinding`, which is a
      // `DogwoodSegmentBinding`, so the interface has to be on their compile classpath too.
      dependencies {
        api(project(":dogwood-host"))
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
      }
      kotlin.srcDir(layout.buildDirectory.dir("generated/acme/host"))
    }
  }
}

android {
  namespace = "dev.acme.design"
  compileSdk = 36
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

/*
 * The generator, pointed at a product's surface.
 *
 * Segment 2, and the number is the whole of what a product has to choose. 0 is the layout tier and
 * 1 is Dogwood's design system; `FIRST_PRODUCT_SEGMENT` says so in code, and `DogwoodRegistry`
 * refuses a collision at registration rather than letting one segment render another's widgets.
 *
 * Its lock lives beside its surface, committed, exactly as Dogwood's does. A product's tags are as
 * permanent as Dogwood's are, for the same reason: a client one version behind resolves them.
 */
val acmeGenerated: Provider<Directory> = layout.buildDirectory.dir("generated/acme")

val generateAcme by tasks.registering(JavaExec::class) {
  group = "build"
  description = "Generates Acme's guest stubs, host bindings and dictionary from its own surface"
  classpath = project(":dogwood-codegen").sourceSets["main"].runtimeClasspath
  mainClass.set("dev.dogwood.codegen.MainKt")

  val surface = project.file("surface")
  inputs.dir(surface).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(acmeGenerated)

  argumentProviders.add {
    val root = acmeGenerated.get().asFile
    listOf(
      "--source", surface.absolutePath,
      "--segment", "acmeDesignSystem",
      "--wire-name", "acme.designsystem",
      "--segment-id", "2",
      "--version", "1",
      "--guest-package", "dev.acme.guest",
      "--host-package", "dev.acme.design",
      "--impl-package", "dev.acme.design",
      "--guest-out", File(root, "guest/dev/acme/guest/AcmeStubs.kt").absolutePath,
      "--host-out", File(root, "host/dev/acme/design/AcmeBindings.kt").absolutePath,
      "--dictionary-out", File(root, "dictionary/acme.designsystem.json").absolutePath,
      "--lock", project.file("surface/acme.designsystem.lock.json").absolutePath,
    )
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(generateAcme)
}
