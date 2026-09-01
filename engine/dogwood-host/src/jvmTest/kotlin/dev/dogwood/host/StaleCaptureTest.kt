/*
 * Project Dogwood -- what a binding may capture, and for how long.
 *
 * This is a regression test for a defect the leak detector found on a device, and the memory was
 * the smaller half of it.
 *
 * A code update replaces the guest and therefore the whole host tree. The replacement tree hands
 * out the same node identifiers from one, so `key(node.id)` matches the same composition groups
 * and every `remember` in a binding is preserved -- which is what makes a code update feel
 * seamless rather than like a reload. The consequence is that a `LaunchedEffect` keyed on anything
 * tree-stable is **not** restarted, and it goes on using whatever it captured in the composition it
 * was launched in.
 *
 * The lazy list's viewport reporter captured the `EventSink`, and that sink captures the
 * `DogwoodExperience`. So after a code update it was still delivering viewport reports to the
 * previous, closed guest -- and holding that guest's Zipline instance, and through it an entire
 * QuickJS heap, alive forever. Six updates in a row leaked exactly one generation: the first.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.EventTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TEXT_ID = DogwoodDictionary.Text.value
private val LIST_ID = DogwoodDictionary.VerticalList.value

/** A list of forty rows, watched, with node identifiers that a replacement guest would repeat. */
private fun watchedList(rows: Int = 40): HostTree {
  val changes = mutableListOf(
    "[0,1,$LIST_ID]",
    "[1,1,5,true]",
    "[3,0,1,1,0]",
  )
  repeat(rows) { index ->
    val id = 100 + index
    changes += "[0,$id,$TEXT_ID]"
    changes += "[1,$id,1,\"row $index\"]"
    changes += "[3,1,1,$id,$index]"
  }
  return HostTree().also { it.apply(decodePositional("[1,[${changes.joinToString(",")}]]")) }
}

private class CountingSink : EventSink {
  var events = 0
    private set

  override fun send(node: WidgetView, tag: EventTag, args: List<kotlinx.serialization.json.JsonElement>) {
    events++
  }
}

class StaleCaptureTest {

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aViewportReportGoesToTheCurrentGuestNotTheOneThatComposedTheEffect() {
    val first = watchedList()
    val second = watchedList()
    val firstSink = CountingSink()
    val secondSink = CountingSink()

    runComposeUiTest {
      var current by mutableStateOf(first to (firstSink as EventSink))
      setContent {
        Box(Modifier.size(240.dp, 320.dp)) {
          DogwoodTree(current.first, current.second, Modifier.size(240.dp, 320.dp))
        }
      }
      waitForIdle()

      onNode(hasScrollAction()).performScrollToIndex(10)
      waitForIdle()
      val beforeSwap = firstSink.events
      assertTrue(beforeSwap > 0, "the first guest must have been receiving reports at all")

      // A code update: a new tree, with the same identifiers, exactly as a replacement guest
      // produces. The composition groups are reused, so nothing restarts on its own.
      current = second to (secondSink as EventSink)
      Snapshot.sendApplyNotifications()
      waitForIdle()

      onNode(hasScrollAction()).performScrollToIndex(25)
      waitForIdle()

      assertTrue(
        secondSink.events > 0,
        "the replacement guest received nothing, so the reporter is still talking to the guest " +
          "that was closed",
      )
      assertEquals(
        beforeSwap,
        firstSink.events,
        "the replaced guest is still being sent viewport reports. It is closed, and holding it " +
          "alive to receive them holds its Zipline instance and an entire QuickJS heap",
      )
    }
  }
}
