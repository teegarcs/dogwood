/*
 * Project Dogwood -- conformance claim J2 on Android: launch parameters reach the experience the
 * host named.
 *
 * Its own class rather than another assertion in `AccessibilityConformanceTest`, and the reason is
 * the screen. That drill opens Diagnostics and asserts on it; this one has to open a *different*
 * entry point, because the claim is precisely that the host chose which experience to open and told
 * it something at the same time. Two screens is two launches, and a `@Before` that opened one of
 * them for both would make one of the two tests lie about what it was looking at.
 *
 * **Why the city is the evidence.** `city` is a launch parameter -- `TabsActivity` puts it in the
 * bundle it hands `DogwoodShell.activate`, and nothing in the payload knows it. A screen reading
 * "Stays in Tokyo" therefore proves both halves at once: the host named the entry point, and the
 * parameter it sent arrived in the composition that entry point started.
 *
 * The `RESULT` line is synthesised by `tools/conformance/run-android.sh` from every `CONF` line in
 * logcat, which is what makes a second class here free: the aggregator reads one run per client and
 * two classes declaring their own results would make the second silently win.
 */
package dev.dogwood.slice.android

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "dev.dogwood.slice.android"

@RunWith(AndroidJUnit4::class)
class HostServicesConformanceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device: UiDevice = UiDevice.getInstance(instrumentation)

  private fun emit(line: String) = Log.i("DogwoodConf", line)

  @Test
  fun launchParametersReachTheNamedExperience() {
    instrumentation.targetContext.startActivity(
      Intent().apply {
        component = ComponentName(PACKAGE, "$PACKAGE.TabsActivity")
        // The host names the entry point. That is the first half of the claim, and it is why this
        // is an intent extra rather than a tap on a tab.
        putExtra("entry", "explore")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      },
    )
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 20_000)

    // The guest is fetched over the network and then composes, so this waits on the outcome rather
    // than on a delay. `Tokyo` is the launch parameter; the sandbox has no other route to it.
    val arrived = device.wait(Until.hasObject(By.textContains("Tokyo")), 60_000) != null

    emit(
      if (arrived) {
        "CONF J2 PASS -- the host named 'explore' and the city it passed reached the composition"
      } else {
        "CONF J2 FAIL -- no launch-parameter text on screen after 60s"
      },
    )
  }
}
