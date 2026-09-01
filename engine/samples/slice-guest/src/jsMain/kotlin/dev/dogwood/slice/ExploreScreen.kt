/*
 * Project Dogwood -- the sample experience.
 *
 * Ordinary Kotlin Compose. It is compiled to JavaScript, delivered over the air, and executed
 * inside QuickJS; nothing in this file knows that. That is the whole claim of the architecture,
 * and this file is where it either reads as ordinary Compose or it does not.
 *
 * The components come from the registered design-system segment, modelled on Skyscanner
 * Backpack (see the audit in adrs/layer-5/ADR-008). Note what is NOT here: no image loader, no
 * scroll state, no interaction source, no painter. Those all live host-side, which is precisely
 * why these components are bindable.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.dogwood.compose.HorizontalList
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
import dev.dogwood.compose.size
import dev.dogwood.compose.width

private data class Destination(
  val name: String,
  val country: String,
  val price: String,
  val was: String,
  val image: String,
)

private data class Stay(
  val name: String,
  val area: String,
  val rating: Float,
  val reviews: String,
  val price: String,
  val badge: String?,
  val image: String,
)

/**
 * Real photographs over Hypertext Transfer Protocol Secure (HTTPS), from a public image content
 * delivery network. Every identifier below was verified to resolve before being committed --
 * a screen that demonstrates image loading with dead URLs demonstrates nothing.
 *
 * The guest sends this string and nothing else. No bitmap, no painter, no asset handle crosses
 * the boundary, which is exactly why an image component is bindable at all.
 */
private fun photo(id: String, width: Int, height: Int) =
  "https://images.unsplash.com/$id?w=$width&h=$height&fit=crop"

private val destinations = listOf(
  Destination("Kyoto", "Japan", "$612", "$740", "photo-1478436127897-769e1b3f0f36"),
  Destination("Reykjavík", "Iceland", "$438", "$520", "photo-1462993340984-49bd9e0f32dd"),
  Destination("Lisbon", "Portugal", "$289", "$355", "photo-1501927023255-9063be98970c"),
  Destination("Queenstown", "New Zealand", "$904", "$1,120", "photo-1512017615494-fdf6146235ff"),
  Destination("Marrakesh", "Morocco", "$341", "$398", "photo-1517821115309-2c35b3906a7b"),
)

private val stays = listOf(
  Stay("The Shinjuku Warren", "Shinjuku", 4.6f, "1,204 reviews", "$186", "Free cancellation", "photo-1522177634436-e1b72e13340e"),
  Stay("Asakusa River House", "Asakusa", 4.3f, "870 reviews", "$132", null, "photo-1565356277201-8c2f9e5df911"),
  Stay("Meguro Garden Hotel", "Meguro", 4.8f, "2,551 reviews", "$254", "Breakfast included", "photo-1565708049686-61fa0faf406a"),
  Stay("Kanda Capsule Nine", "Chiyoda", 3.9f, "612 reviews", "$74", null, "photo-1480796927426-f609979314bd"),
  Stay("Ebisu Skyline", "Ebisu", 4.5f, "1,880 reviews", "$203", null, "photo-1465353471565-b77e538f34c9"),
  Stay("Yanaka Ryokan", "Taitō", 4.7f, "441 reviews", "$168", "Free cancellation", "photo-1525207934214-58e69a8f8a3e"),
)

private val filters = listOf("Any dates", "1 room", "Under $200", "4+ stars", "Free cancellation")

@Composable
fun ExploreScreen() {
  // Saveable, so a code update published while someone is mid-browse does not reset them.
  var selectedFilter by rememberSaveable(key = "filter", stateSaver = autoSaver()) {
    mutableStateOf(0)
  }
  var savedStays by rememberSaveable(key = "saved", stateSaver = autoSaver()) { mutableStateOf(0) }

  VerticalList(
    modifier = DogwoodModifier.fillMaxWidth(),
    spacingDp = 16,
    contentPaddingDp = 16,
  ) {
    SectionHeader(
      title = "Explore Japan",
      description = "Return flights, next 3 months",
    )

    HorizontalList(spacingDp = 12) {
      for (destination in destinations) {
        DestinationCard(destination)
      }
    }

    HorizontalList(spacingDp = 8) {
      filters.forEachIndexed { index, label ->
        Chip(
          text = label,
          selected = index == selectedFilter,
          onSelectedChange = { nowSelected -> if (nowSelected) selectedFilter = index },
        )
      }
    }

    Divider(modifier = DogwoodModifier.fillMaxWidth())

    SectionHeader(
      title = "Stays in Tokyo",
      description = if (savedStays == 0) {
        "${stays.size} properties"
      } else {
        "${stays.size} properties · $savedStays saved"
      },
    )

    for (stay in stays) {
      StayCard(stay, onSave = { savedStays += 1 })
    }

    PrimaryButton(
      label = "See all ${stays.size} stays",
      modifier = DogwoodModifier.fillMaxWidth().padding(4),
      onClick = { savedStays = 0 },
    )
  }
}

@Composable
private fun DestinationCard(destination: Destination) {
  Card(modifier = DogwoodModifier.width(220)) {
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
        previousPrice = destination.was,
        trailingText = "return",
        modifier = DogwoodModifier.padding(2),
      )
    }
  }
}

@Composable
private fun StayCard(stay: Stay, onSave: () -> Unit) {
  Card(modifier = DogwoodModifier.fillMaxWidth()) {
    Row(modifier = DogwoodModifier.fillMaxWidth().padding(8), onClick = onSave) {
      AsyncImage(
        url = photo(stay.image, 300, 300),
        contentDescription = stay.name,
        modifier = DogwoodModifier.size(96),
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
