/*
 * Umbra's Worker entry point: the entry-point list, and one call.
 *
 * Compare `:guest`'s `main`, which binds the same `DogwoodGuest` to a Zipline service. The screen
 * is the same file in both; only this function differs, and it is one line because the transport
 * is a library rather than something this build carries.
 */
package dev.umbra.webguest

import dev.dogwood.compose.DogwoodGuest
import dev.dogwood.compose.runInWorker
import dev.umbra.guest.HomeScreen

fun main() = runInWorker(
  DogwoodGuest(
    "home" to { _ -> HomeScreen() },
  ),
)
