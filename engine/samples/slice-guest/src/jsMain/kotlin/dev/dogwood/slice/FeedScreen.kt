/*
 * Project Dogwood -- ten thousand rows, and about twenty of them cross.
 *
 * The third entry point exists to make windowing visible. Before it, the sample's lists were six
 * items long, which is exactly the size at which the difference between "lazy on the host" and
 * "lazy on the boundary" cannot be seen.
 *
 * Watch Logcat while scrolling: the window follows the viewport, and the number of nodes the guest
 * is composing stays flat no matter how far down the list the user goes.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import dev.dogwood.compose.AsyncImage
import dev.dogwood.compose.Box
import dev.dogwood.compose.Card
import dev.dogwood.compose.Color
import dev.dogwood.compose.Column
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.Formats
import dev.dogwood.compose.LazyVerticalList
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.Row
import dev.dogwood.compose.Spacer
import dev.dogwood.compose.StarRating
import dev.dogwood.compose.Text
import dev.dogwood.compose.background
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.height
import dev.dogwood.compose.padding
import dev.dogwood.compose.rememberLazyListState
import dev.dogwood.compose.services
import dev.dogwood.compose.size
import dev.dogwood.compose.width

private const val TAG = "feed"

private const val ROWS = 10_000

private val cities = listOf(
  "Kyoto", "Osaka", "Sapporo", "Fukuoka", "Naha", "Sendai", "Kanazawa", "Nagoya",
)

private val photos = listOf(
  "photo-1478436127897-769e1b3f0f36",
  "photo-1462993340984-49bd9e0f32dd",
  "photo-1501927023255-9063be98970c",
  "photo-1512017615494-fdf6146235ff",
)

private data class Row(val index: Int) {
  val city: String get() = cities[index % cities.size]
  val priceMinor: Long get() = 7_000L + (index % 400) * 137L
  val rating: Float get() = 3.5f + (index % 15) / 10f
  val image: String get() = photos[index % photos.size]
}

@Composable
fun FeedScreen() {
  val host = services()
  val listState = rememberLazyListState()
  val rows = remember10k()

  // Logged so the windowing is observable without a profiler: scroll to row nine thousand and
  // these numbers move while the amount of work does not.
  host.info(
    TAG,
    "viewport ${listState.firstVisibleItemIndex}..${listState.lastVisibleItemIndex} of $ROWS",
  )

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth().padding(12)) {
      Text("$ROWS stays", style = "titleMedium")
      Spacer(modifier = Modifier.size(12))
      PrimaryButton(
        label = "Jump to 9,000",
        // A declared target into content the host has never laid out. The list is genuinely ten
        // thousand items long host-side, so this works without the guest composing anything in
        // between.
        onClick = { listState.animateScrollToItem(9_000) },
      )
    }

    LazyVerticalList(
      items = rows,
      modifier = Modifier.fillMaxWidth(),
      state = listState,
      spacingDp = 8,
      contentPaddingDp = 12,
      // The template, composed once. The host repeats it for every one of the roughly nine
      // thousand nine hundred rows the guest has not sent.
      placeholder = {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(96)
            .background(Color.token("canvasContrast")),
        )
      },
    ) { index, row ->
      FeedRow(index, row)
    }
  }
}

@Composable
private fun FeedRow(index: Int, row: Row) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth().padding(8)) {
      AsyncImage(
        url = "https://images.unsplash.com/${row.image}?w=200&h=200&fit=crop",
        contentDescription = null,
        modifier = Modifier.size(72),
        cornerRadiusDp = 8,
      )
      Spacer(modifier = Modifier.size(12))
      Column(modifier = Modifier.weight(1.0f)) {
        Text("#$index · ${row.city}", style = "titleSmall")
        StarRating(rating = row.rating)
        Text(Formats.currency(row.priceMinor, "USD"), style = "bodyMedium")
      }
    }
  }
}

/**
 * Ten thousand rows, built once.
 *
 * They live in the guest's heap, which is the point: the *list* is guest data and only the window
 * crosses. A design that sent the list would have moved the problem rather than solved it.
 */
@Composable
private fun remember10k(): List<Row> =
  androidx.compose.runtime.remember { List(ROWS) { Row(it) } }
