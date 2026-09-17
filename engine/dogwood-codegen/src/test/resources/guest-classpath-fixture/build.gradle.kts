/*
 * A guest module with nothing in it, so that what is on its classpath is exactly what a test put
 * there.
 *
 * `-PfixtureDependency=<coordinate>` adds one dependency. With none, the build is clean and
 * `dogwoodGuestClasspathCheck` passes; with `androidx.compose.animation:animation-core:1.9.0` the
 * banned artifact is direct; with `com.example:helper:1.0` it arrives transitively, which is the
 * case the check exists for and the one a source scan cannot see.
 */
plugins {
  java
  id("io.github.teegarcs.dogwood.guest")
}

/*
 * A repository of metadata and nothing else. Resolution reads Project Object Model (POM) files to
 * build the graph; it downloads a jar only when somebody asks for artifacts, and this check never
 * does -- it reads the resolution result. So two small POM files stand in for two real libraries,
 * and the fixture needs no network and no binaries in version control.
 */
repositories {
  maven { url = uri(layout.projectDirectory.dir("repo")) }
}

dependencies {
  val coordinate = providers.gradleProperty("fixtureDependency")
  if (coordinate.isPresent) implementation(coordinate.get())
}
