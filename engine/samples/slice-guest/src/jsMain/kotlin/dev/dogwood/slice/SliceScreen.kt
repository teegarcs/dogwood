/*
 * Project Dogwood -- the Phase 1 vertical slice.
 *
 * The same screen the Phase 0 harness measured, now driven as a real experience: composed in
 * the guest, crossed as a change batch, rendered by native Compose on the host, and responding
 * to taps. Keeping it identical to the measured screen is deliberate -- the Phase 0 numbers
 * describe this screen, and changing it would strand them.
 *
 * THE REFERENCE SCREEN. One committed file, used by experiment 0.1 (payload), 0.2
 * (composition and recomposition), and 0.3 (batch). It is written once and reused verbatim,
 * per the Phase 0 harness appendix in roadmap.md. Do not tune it to make a number look
 * better; changing it invalidates every comparison against previously recorded results.
 *
 * Structure, straight from the appendix:
 *   - a header block: image placeholder box, title, subtitle, price row
 *   - a plain Column of rows (LazyColumn does not exist in the guest), each row being
 *     Row(image-box, Column(Text, Text), Text)
 *   - a footer with buttons and a selectable chip row of eight
 *   - twenty-four mutableStateOf holders: row selection x20, quantity, promo visibility,
 *     total, loading flag
 *   - modifier chains of two to four elements on every container
 *   - one event handler per row plus three on the footer
 *
 * Widget-node arithmetic (nodes that produce a `Create` change):
 *   root Column                                              1
 *   header: Box + Text + Text + Row + Text + Text            6
 *   list Column                                              1
 *   each row: Row + Box + Column + Text + Text + Text        6 x rows
 *   footer: Column + Row + 3 buttons + Row + 8 badges       14
 *   => 22 + 6 x rows
 * At rows = 23 that is exactly 160 widget nodes, which is the "~160 nodes" the appendix
 * specifies. See adrs/layer-4/ADR-005 for why the row count is 23 and not 50.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import dev.dogwood.compose.Badge
import dev.dogwood.compose.Box
import dev.dogwood.compose.Column
import dev.dogwood.compose.Divider
import dev.dogwood.compose.DogwoodModifier
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.Row
import dev.dogwood.compose.Text
import dev.dogwood.compose.alpha
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.padding
import dev.dogwood.compose.size
import dev.dogwood.compose.weight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The row count that yields the appendix's ~160-node reference screen. */
const val REFERENCE_ROWS: Int = 23

/**
 * The screen's state. Exactly twenty-four `mutableStateOf` holders, no more and no fewer.
 *
 * The chip row deliberately derives its selection from [quantity] rather than owning a
 * twenty-fifth holder, because the appendix enumerates the twenty-four and a chip-selection
 * holder is not among them.
 */
class SliceState {
  /** Row selection. Twenty holders; rows beyond the twentieth share them, modulo twenty. */
  val rowSelected = List(20) { mutableStateOf(false) }

  var quantity by mutableStateOf(1)
  var promoVisible by mutableStateOf(false)
  var total by mutableStateOf("$0.00")
  var loading by mutableStateOf(false)

  /** Sanity check used by the harness so the count cannot silently drift. */
  val holderCount: Int get() = rowSelected.size + 4

  fun toggleRow(index: Int) {
    val holder = rowSelected[index % rowSelected.size]
    holder.value = !holder.value
  }
}

@Composable
fun SliceScreen(state: SliceState, rows: Int = REFERENCE_ROWS) {
  Column(modifier = DogwoodModifier.fillMaxWidth().padding(16)) {
    Header(state)
    ItemList(state, rows)
    Footer(state)
  }
}

@Composable
private fun Header(state: SliceState) {
  // Image placeholder box.
  Box(modifier = DogwoodModifier.fillMaxWidth().size(180).padding(8))
  Text("Dogwood Reference Product", modifier = DogwoodModifier.padding(4).fillMaxWidth())
  Text("A product-detail-like screen used only for measurement.", modifier = DogwoodModifier.padding(4))
  Row(modifier = DogwoodModifier.fillMaxWidth().padding(4).alpha(1.0f)) {
    Text("Total", modifier = DogwoodModifier.padding(2).weight(1.0f))
    // Read one: the two-node diff the appendix asks for mutates this and the footer button.
    Text(state.total, modifier = DogwoodModifier.padding(2))
  }
}

@Composable
private fun ItemList(state: SliceState, rows: Int) {
  Column(modifier = DogwoodModifier.fillMaxWidth().padding(2)) {
    for (index in 0 until rows) {
      ItemRow(state, index)
    }
  }
}

@Composable
private fun ItemRow(state: SliceState, index: Int) {
  val selected by state.rowSelected[index % state.rowSelected.size]
  Row(
    modifier = DogwoodModifier.fillMaxWidth().padding(8).alpha(1.0f),
    onClick = { state.toggleRow(index) },
  ) {
    Box(modifier = DogwoodModifier.size(48).padding(4))
    Column(modifier = DogwoodModifier.weight(1.0f).padding(4)) {
      Text("Item $index", modifier = DogwoodModifier.padding(2))
      Text("Stock keeping unit DGW-$index", modifier = DogwoodModifier.padding(2))
    }
    // The one-node diff: only this Text depends on the row's selection state.
    Text(if (selected) "Selected" else "", modifier = DogwoodModifier.padding(2))
  }
}

@Composable
private fun Footer(state: SliceState) {
  Column(modifier = DogwoodModifier.fillMaxWidth().padding(8).alpha(1.0f)) {
    Row(modifier = DogwoodModifier.fillMaxWidth().padding(4)) {
      PrimaryButton("Add to cart", modifier = DogwoodModifier.weight(1.0f).padding(4)) {
        state.quantity += 1
      }
      // Read two: the footer button label also depends on the total.
      PrimaryButton("Pay ${state.total}", modifier = DogwoodModifier.weight(1.0f).padding(4)) {
        state.loading = !state.loading
      }
      PrimaryButton("Promo", modifier = DogwoodModifier.padding(4)) {
        state.promoVisible = !state.promoVisible
      }
    }
    if (state.promoVisible) {
      // Structural change, deliberately outside the measured recomposition paths.
      Badge("Promotion applied", selected = true, modifier = DogwoodModifier.padding(4))
    }
    if (state.loading) {
      Divider(modifier = DogwoodModifier.fillMaxWidth().padding(2))
    }
    Row(modifier = DogwoodModifier.fillMaxWidth().padding(4).alpha(1.0f)) {
      for (chip in 1..8) {
        Badge(
          text = "$chip",
          selected = chip == state.quantity,
          modifier = DogwoodModifier.padding(2).size(32),
        )
      }
    }
  }
}
