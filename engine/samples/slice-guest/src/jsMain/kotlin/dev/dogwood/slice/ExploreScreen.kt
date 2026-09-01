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
import dev.dogwood.compose.DogwoodModifier
import dev.dogwood.compose.GuestServices
import dev.dogwood.compose.HorizontalList
import dev.dogwood.compose.LocalDogwoodConfiguration
import dev.dogwood.compose.Price
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.Row
import dev.dogwood.compose.SectionHeader
import dev.dogwood.compose.Spacer
import dev.dogwood.compose.StarRating
import dev.dogwood.compose.Text
import dev.dogwood.compose.VerticalList
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.Colors
import dev.dogwood.compose.Shapes
import dev.dogwood.compose.background
import dev.dogwood.compose.clip
import dev.dogwood.compose.padding
import dev.dogwood.compose.height
import dev.dogwood.compose.services
import dev.dogwood.compose.size
import dev.dogwood.compose.width
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.WidthClass
import dev.dogwood.protocol.widthClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "explore"

@Serializable
data class Destination(
  val name: String,
  val country: String,
  val price: String,
  val was: String,
  val image: String,
)

@Serializable
data class Stay(
  val name: String,
  val area: String,
  val rating: Float,
  val reviews: String,
  val price: String,
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

@Composable
fun ExploreScreen(params: ExploreParams) {
  // Saveable, so a code update published while someone is mid-browse does not reset them.
  var selectedFilter by rememberSaveable(key = "filter", stateSaver = autoSaver()) {
    mutableStateOf(0)
  }
  var savedStays by rememberSaveable(key = "saved", stateSaver = autoSaver()) { mutableStateOf(0) }

  // The host environment. This composition cannot see the device: it has no display metrics, no
  // resources, and QuickJS ships no `Intl`. Everything it knows about where it is running arrived
  // through this one value, and it is snapshot state, so a rotation, a window resize, or a switch
  // to dark mode recomposes only what actually reads it.
  val environment = LocalDogwoodConfiguration.current
  val host = services()

  var state by remember { mutableStateOf<FeedState>(FeedState.Loading) }
  var attempt by remember { mutableStateOf(0) }

  // The one place this screen leaves the sandbox. `fetch` suspends rather than blocks, because
  // the guest is single-threaded: a blocking call here would stop composition, the frame clock,
  // and every pending event until the network answered.
  LaunchedEffect(params.apiBaseUrl, attempt) {
    state = FeedState.Loading
    state = load(host, params)
  }

  VerticalList(
    modifier = DogwoodModifier.fillMaxWidth(),
    spacingDp = 16,
    contentPaddingDp = 16,
  ) {
    SectionHeader(
      title = "Explore ${params.country}",
      description = "Return flights, next 3 months",
    )

    when (val current = state) {
      FeedState.Loading -> Text("Loading…")

      is FeedState.Failed -> {
        // A refusal is an ordinary outcome, not an exception: the host may simply not allow this
        // payload to reach that address, and a screen has to be able to say so.
        Text(current.reason)
        PrimaryButton(
          label = "Try again",
          modifier = DogwoodModifier.fillMaxWidth().padding(4),
          onClick = { attempt += 1 },
        )
      }

      is FeedState.Ready -> {
        val feed = current.feed

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

        Divider(modifier = DogwoodModifier.fillMaxWidth())

        SectionHeader(
          title = "Stays in ${params.city}",
          description = if (savedStays == 0) {
            "${feed.stays.size} properties"
          } else {
            "${feed.stays.size} properties · $savedStays saved"
          },
        )

        for (stay in feed.stays) {
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
          label = "See all ${feed.stays.size} stays",
          modifier = DogwoodModifier.fillMaxWidth().padding(4),
          onClick = { savedStays = 0 },
        )
      }
    }

    // The home indicator, the gesture bar, whatever this device puts at the bottom of its screen.
    // The guest cannot measure it and cannot convert pixels to density-independent pixels on its
    // own, so the host reports it already converted and the guest simply obeys.
    if (environment.safeAreaBottomDp > 0) {
      Spacer(modifier = DogwoodModifier.height(environment.safeAreaBottomDp))
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
private suspend fun load(host: GuestServices, params: ExploreParams): FeedState {
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
private fun destinationCardWidthDp(environment: DogwoodConfiguration): Int =
  when (environment.widthClass) {
    WidthClass.Compact -> 220
    WidthClass.Medium -> 280
    WidthClass.Expanded -> 340
  }

@Composable
private fun DestinationCard(destination: Destination, widthDp: Int, showWasPrice: Boolean) {
  Card(modifier = DogwoodModifier.width(widthDp)) {
    Column(modifier = DogwoodModifier.padding(8)) {
      AsyncImage(
        url = photo(destination.image, 400, 300),
        contentDescription = "${destination.name}, ${destination.country}",
        modifier = DogwoodModifier.fillMaxWidth().height(130),
        cornerRadiusDp = 12,
      )
      // Deferred expressions. Neither of these arguments is a value the guest could construct:
      // the shape and the colour are built host-side from recipes. The colour is named rather
      // than literal, so it follows the host's theme -- which a literal could not.
      Box(
        modifier = DogwoodModifier
          .fillMaxWidth()
          .height(3)
          .clip(Shapes.roundedCorner(2))
          .background(Colors.token("primary")),
      )
      Spacer(modifier = DogwoodModifier.size(8))
      Text(destination.name, modifier = DogwoodModifier.padding(2))
      Text(destination.country, modifier = DogwoodModifier.padding(2))
      Price(
        price = destination.price,
        previousPrice = if (showWasPrice) destination.was else null,
        trailingText = "return",
        modifier = DogwoodModifier.padding(2),
      )
    }
  }
}

@Composable
private fun StayCard(stay: Stay, thumbnailDp: Int, onSave: () -> Unit) {
  Card(modifier = DogwoodModifier.fillMaxWidth()) {
    Row(modifier = DogwoodModifier.fillMaxWidth().padding(8), onClick = onSave) {
      AsyncImage(
        url = photo(stay.image, 300, 300),
        contentDescription = stay.name,
        modifier = DogwoodModifier.size(thumbnailDp),
        cornerRadiusDp = 8,
      )
      Spacer(modifier = DogwoodModifier.size(12))
      Column(modifier = DogwoodModifier.weight(1.0f).padding(2)) {
        Text(stay.name, modifier = DogwoodModifier.padding(1))
        Text(stay.area, modifier = DogwoodModifier.padding(1))
        StarRating(
          rating = stay.rating,
          label = stay.reviews,
          modifier = DogwoodModifier.padding(1),
        )
        if (stay.badge != null) {
          Badge(text = stay.badge, selected = true, modifier = DogwoodModifier.padding(1))
        }
        Price(
          price = stay.price,
          trailingText = "per night",
          modifier = DogwoodModifier.padding(1),
        )
      }
    }
  }
}
