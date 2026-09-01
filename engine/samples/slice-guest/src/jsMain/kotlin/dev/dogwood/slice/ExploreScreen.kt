/*
 * Project Dogwood -- the sample experience.
 *
 * Ordinary Kotlin Compose. It is compiled to JavaScript, delivered over the air, and executed
 * inside QuickJS; nothing in this file knows that. That is the whole claim of the architecture,
 * and this file is where it either reads as ordinary Compose or it does not.
 *
 * The components come from the registered design-system segment, modelled on Skyscanner Backpack
 * (see the audit in adrs/layer-5/ADR-008). Note what is NOT here: no image loader, no scroll
 * state, no interaction source, no painter, **no HTTP client, and no clock**. Those all live
 * host-side, which is precisely why the sandbox is a sandbox: this screen's data arrives through
 * the host's network service or it does not arrive at all.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.dogwood.compose.AsyncImage
import dev.dogwood.compose.Badge
import dev.dogwood.compose.Box
import dev.dogwood.compose.Card
import dev.dogwood.compose.Chip
import dev.dogwood.compose.Column
import dev.dogwood.compose.Divider
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.HostServices
import dev.dogwood.compose.HorizontalList
import dev.dogwood.compose.LocalHostEnvironment
import dev.dogwood.compose.rememberLazyListState
import dev.dogwood.compose.Price
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.Row
import dev.dogwood.compose.SectionHeader
import dev.dogwood.compose.Spacer
import dev.dogwood.compose.StarRating
import dev.dogwood.compose.Text
import dev.dogwood.compose.VerticalList
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.Color
import dev.dogwood.compose.Formats
import dev.dogwood.compose.Icon
import dev.dogwood.compose.LocalStringTable
import dev.dogwood.compose.Shape
import dev.dogwood.compose.StringTable
import dev.dogwood.compose.TextField
import dev.dogwood.compose.rememberTextFieldState
import dev.dogwood.compose.strings
import dev.dogwood.compose.background
import dev.dogwood.compose.clip
import dev.dogwood.compose.padding
import dev.dogwood.compose.height
import dev.dogwood.compose.services
import dev.dogwood.compose.size
import dev.dogwood.compose.width
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.WidthClass
import dev.dogwood.protocol.widthClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "explore"

/**
 * Money arrives as an integer and a currency code, not as a rendered string.
 *
 * That is the whole point of the formatting recipes. A server that sent "$612" would have decided
 * the currency symbol, the decimal separator, the grouping separator and the number of decimal
 * places on behalf of every device it would ever reach -- and would have got all four wrong for
 * most of them. The number crosses; the host renders it in the locale it is in.
 */
@Serializable
data class Destination(
  val name: String,
  val country: String,
  val priceMinor: Long,
  val wasMinor: Long,
  val currency: String,
  val image: String,
)

@Serializable
data class Stay(
  val name: String,
  val area: String,
  val rating: Float,
  val reviews: String,
  val priceMinor: Long,
  val currency: String,
  val badge: String? = null,
  val image: String,
)

@Serializable
data class ExploreFeed(
  val destinations: List<Destination> = emptyList(),
  val stays: List<Stay> = emptyList(),
)

private val feedJson = Json { ignoreUnknownKeys = true }

/**
 * Real photographs over Hypertext Transfer Protocol Secure (HTTPS), from a public image content
 * delivery network. The identifiers come from the feed; this only builds the sized URL.
 *
 * The guest sends this string and nothing else. No bitmap, no painter, no asset handle crosses the
 * boundary, which is exactly why an image component is bindable at all.
 */
private fun photo(id: String, width: Int, height: Int) =
  "https://images.unsplash.com/$id?w=$width&h=$height&fit=crop"

private val filters = listOf("Any dates", "1 room", "Under $200", "4+ stars", "Free cancellation")

/** Where the screen is in its one asynchronous transition. Deliberately explicit, not nullable. */
private sealed interface FeedState {
  data object Loading : FeedState
  data class Ready(val feed: ExploreFeed) : FeedState
  data class Failed(val reason: String) : FeedState
}

/**
 * Copy the payload carries, so changing a word does not need a store release.
 *
 * Japanese is here to make the mechanism visible rather than because the sample is translated:
 * switch the device to `ja-JP` and these three strings change with it, alongside the currency
 * formatting the host does.
 */
private val exploreStrings = StringTable(
  mapOf(
    "en" to mapOf(
      "exploreTitle" to "Flights and stays",
      "returnFlight" to "return",
      "perNight" to "per night",
      "staysIn" to "Stays in",
      "filterStays" to "Filter stays",
    ),
    "ja" to mapOf(
      "exploreTitle" to "航空券と宿泊",
      "returnFlight" to "往復",
      "perNight" to "1泊あたり",
      "staysIn" to "宿泊先:",
      "filterStays" to "宿泊先を絞り込む",
    ),
  ),
)

@Composable
fun ExploreScreen(params: ExploreParams) {
  CompositionLocalProvider(LocalStringTable provides exploreStrings) {
    ExploreContent(params)
  }
}

@Composable
private fun ExploreContent(params: ExploreParams) {
  // Saveable, so a code update published while someone is mid-browse does not reset them.
  var selectedFilter by rememberSaveable(key = "filter", stateSaver = autoSaver()) {
    mutableStateOf(0)
  }
  var savedStays by rememberSaveable(key = "saved", stateSaver = autoSaver()) { mutableStateOf(0) }

  // The host environment. This composition cannot see the device: it has no display metrics, no
  // resources, and QuickJS ships no `Intl`. Everything it knows about where it is running arrived
  // through this one value, and it is snapshot state, so a rotation, a window resize, or a switch
  // to dark mode recomposes only what actually reads it.
  val environment = LocalHostEnvironment.current
  val host = services()

  var state by remember { mutableStateOf<FeedState>(FeedState.Loading) }
  var attempt by remember { mutableStateOf(0) }

  // The list position is host-owned -- scroll offset changes every frame and the guest may not
  // hold per-frame state -- but it is *saveable*, so publishing new code while somebody is
  // halfway down the page brings them back to where they were rather than to the top.
  val listState = rememberLazyListState()

  // The one place this screen leaves the sandbox. `fetch` suspends rather than blocks, because
  // the guest is single-threaded: a blocking call here would stop composition, the frame clock,
  // and every pending event until the network answered.
  LaunchedEffect(params.apiBaseUrl, attempt) {
    state = FeedState.Loading
    state = load(host, params)
  }

  VerticalList(
    modifier = Modifier.fillMaxWidth(),
    spacingDp = 16,
    contentPaddingDp = 16,
    state = listState,
  ) {
    Row(modifier = Modifier.padding(2)) {
      // An icon by name. The guest has no painter, no asset and no resource identifier; the host
      // owns the icon set and resolves the name, exactly as it resolves a colour token.
      Icon(name = "flight", contentDescription = null, sizeDp = 20, tint = "primary")
      Spacer(modifier = Modifier.size(8))
      Text(strings("exploreTitle"), style = "labelLarge")
    }

    SectionHeader(
      title = "Explore ${params.country}",
      // The visible range is a report from the host, not a measurement: it arrives when it
      // changes by an item, never by a pixel. Rendering it is exactly what it is for.
      description = if (listState.lastVisibleItemIndex >= 0) {
        "Return flights, next 3 months · showing ${listState.firstVisibleItemIndex + 1}–${listState.lastVisibleItemIndex + 1}"
      } else {
        "Return flights, next 3 months"
      },
    )

    when (val current = state) {
      FeedState.Loading -> Text("Loading…")

      is FeedState.Failed -> {
        // A refusal is an ordinary outcome, not an exception: the host may simply not allow this
        // payload to reach that address, and a screen has to be able to say so.
        Text(current.reason)
        PrimaryButton(
          label = "Try again",
          modifier = Modifier.fillMaxWidth().padding(4),
          onClick = { attempt += 1 },
        )
      }

      is FeedState.Ready -> {
        val feed = current.feed
        // The field's text lives host-side, so typing never waits for a boundary crossing. What
        // does cross is the value, once per keystroke -- and the list below recomposes from it.
        // That is the right split: a stutter in a filtered list is a slow list, a stutter in a
        // text field is a broken keyboard.
        val query = rememberTextFieldState()
        val matches = if (query.text.isBlank()) {
          feed.stays
        } else {
          feed.stays.filter {
            it.name.contains(query.text, ignoreCase = true) ||
              it.area.contains(query.text, ignoreCase = true)
          }
        }

        HorizontalList(spacingDp = 12) {
          for (destination in feed.destinations) {
            DestinationCard(
              destination = destination,
              widthDp = destinationCardWidthDp(environment),
              // A flag the host resolved before this screen started. Flipping it is a server
              // decision, not a release.
              showWasPrice = host.flagEnabled("explore.showWasPrice", default = true),
            )
          }
        }

        HorizontalList(spacingDp = 8) {
          filters.forEachIndexed { index, label ->
            Chip(
              text = label,
              selected = index == selectedFilter,
              onSelectedChange = { nowSelected ->
                if (nowSelected) {
                  selectedFilter = index
                  host.track("explore.filter", mapOf("filter" to label))
                }
              },
            )
          }
        }

        Divider(modifier = Modifier.fillMaxWidth())

        SectionHeader(
          title = "${strings("staysIn")} ${params.city}",
          description = if (savedStays == 0) {
            "${matches.size} properties"
          } else {
            "${matches.size} properties · $savedStays saved"
          },
        )

        TextField(
          state = query,
          modifier = Modifier.fillMaxWidth(),
          label = strings("filterStays"),
          singleLine = true,
        )

        for (stay in matches) {
          StayCard(
            stay,
            // Wider rooms get a larger thumbnail. The guest decides this, not the host, because
            // it is a composition decision -- which is the point of sending the environment
            // across rather than letting the host silently adapt what the guest emitted.
            thumbnailDp = if (environment.widthClass == WidthClass.Compact) 96 else 128,
            onSave = {
              savedStays += 1
              host.track(
                "explore.save",
                mapOf("stay" to stay.name, "at" to (host.nowEpochMillis()?.toString() ?: "")),
              )
            },
          )
        }

        PrimaryButton(
          label = "See all ${matches.size} stays",
          modifier = Modifier.fillMaxWidth().padding(4),
          onClick = { savedStays = 0 },
        )

        // A declared target, not a command: the guest says where it wants to be and the host
        // gets there. Tapping it twice is two requests, which is why the holder carries a
        // sequence number rather than a flag.
        PrimaryButton(
          label = "Back to top",
          modifier = Modifier.fillMaxWidth().padding(4),
          onClick = { listState.animateScrollToItem(0) },
        )
      }
    }

    // The home indicator, the gesture bar, whatever this device puts at the bottom of its screen.
    // The guest cannot measure it and cannot convert pixels to density-independent pixels on its
    // own, so the host reports it already converted and the guest simply obeys.
    if (environment.safeAreaBottomDp > 0) {
      Spacer(modifier = Modifier.height(environment.safeAreaBottomDp))
    }
  }
}

/**
 * Fetches the feed, turning every failure into a state rather than an exception.
 *
 * Three distinct things can go wrong and a screen has to distinguish them: the host offers no
 * network at all, the host refused this address, or the address answered with something this
 * payload cannot read. Collapsing them into "something went wrong" would leave whoever is
 * debugging with nothing.
 */
private suspend fun load(host: HostServices, params: ExploreParams): FeedState {
  if (params.apiBaseUrl.isEmpty()) {
    return FeedState.Failed("This client did not say where its data lives (no apiBaseUrl in the launch parameters).")
  }
  val url = "${params.apiBaseUrl.trimEnd('/')}/explore.json"
  host.info(TAG, "fetching $url")

  val response = host.fetch(HttpRequest(url = url))
  if (!response.isSuccessful) {
    val reason = response.failure ?: "HTTP ${response.code}"
    host.error(TAG, "fetch failed: $reason")
    return FeedState.Failed("Could not load from $url\n$reason")
  }

  return runCatching { feedJson.decodeFromString(ExploreFeed.serializer(), response.body) }
    .fold(
      onSuccess = { feed ->
        host.info(TAG, "loaded ${feed.destinations.size} destinations and ${feed.stays.size} stays")
        FeedState.Ready(feed)
      },
      onFailure = { failure ->
        host.error(TAG, "could not read the feed: ${failure.message}")
        FeedState.Failed("The feed at $url was not something this payload could read.")
      },
    )
}

/**
 * How wide a destination card should be, given the room available.
 *
 * Bucketed by width class rather than computed from the exact viewport, so that the number of
 * distinct layouts this screen can produce is three rather than one per device.
 */
private fun destinationCardWidthDp(environment: HostEnvironment): Int =
  when (environment.widthClass) {
    WidthClass.Compact -> 220
    WidthClass.Medium -> 280
    WidthClass.Expanded -> 340
  }

@Composable
private fun DestinationCard(destination: Destination, widthDp: Int, showWasPrice: Boolean) {
  Card(modifier = Modifier.width(widthDp)) {
    Column(modifier = Modifier.padding(8)) {
      AsyncImage(
        url = photo(destination.image, 400, 300),
        contentDescription = "${destination.name}, ${destination.country}",
        modifier = Modifier.fillMaxWidth().height(130),
        cornerRadiusDp = 12,
      )
      // Deferred expressions. Neither of these arguments is a value the guest could construct:
      // the shape and the colour are built host-side from recipes. The colour is named rather
      // than literal, so it follows the host's theme -- which a literal could not.
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(3)
          .clip(Shape.roundedCorner(2))
          .background(Color.token("primary")),
      )
      Spacer(modifier = Modifier.size(8))
      Text(destination.name, modifier = Modifier.padding(2))
      Text(destination.country, modifier = Modifier.padding(2))
      // Formatted host-side, in the device's locale, from an integer number of cents. Switch the
      // device to ja-JP and this becomes ￥612 with no traffic and no guest recomposition.
      Row(modifier = Modifier.padding(2)) {
        Text(
          Formats.currency(destination.priceMinor, destination.currency),
          style = "titleMedium",
        )
        Spacer(modifier = Modifier.size(6))
        Text(strings("returnFlight"), style = "bodySmall")
      }
      if (showWasPrice) {
        Text(
          Formats.currency(destination.wasMinor, destination.currency),
          modifier = Modifier.padding(2),
          style = "bodySmall",
        )
      }
    }
  }
}

@Composable
private fun StayCard(stay: Stay, thumbnailDp: Int, onSave: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth().padding(8), onClick = onSave) {
      AsyncImage(
        url = photo(stay.image, 300, 300),
        contentDescription = stay.name,
        modifier = Modifier.size(thumbnailDp),
        cornerRadiusDp = 8,
      )
      Spacer(modifier = Modifier.size(12))
      Column(modifier = Modifier.weight(1.0f).padding(2)) {
        Text(stay.name, modifier = Modifier.padding(1))
        Text(stay.area, modifier = Modifier.padding(1))
        StarRating(
          rating = stay.rating,
          label = stay.reviews,
          modifier = Modifier.padding(1),
        )
        if (stay.badge != null) {
          Badge(text = stay.badge, selected = true, modifier = Modifier.padding(1))
        }
        Row(modifier = Modifier.padding(1)) {
          Text(Formats.currency(stay.priceMinor, stay.currency), style = "titleMedium")
          Spacer(modifier = Modifier.size(6))
          Text(strings("perNight"), style = "bodySmall")
        }
      }
    }
  }
}
