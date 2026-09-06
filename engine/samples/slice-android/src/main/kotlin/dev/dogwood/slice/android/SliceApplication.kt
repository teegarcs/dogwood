/*
 * Project Dogwood -- where a host registers the segments it can render.
 *
 * `Application.onCreate` rather than an activity's, and the reason is not tidiness. This sample has
 * three entry points -- `SliceActivity`, `TabsActivity`, `RenderBenchActivity` -- and registering
 * in one of them means the other two render a product's components as **placeholders**: empty
 * boxes, no error, no crash, and a screen that is merely missing something. That is precisely what
 * happened the first time this was wired, and it is the failure mode registration has in general,
 * so it is worth the file.
 *
 * Registration is once per process, before anything renders. `DogwoodRegistry` refuses a second
 * registration of the same segment, which turns "registered in two places" from a subtle
 * double-dispatch into a message.
 */
package dev.dogwood.slice.android

import android.app.Application
import dev.acme.design.AcmeDesignSystemBinding
import dev.dogwood.host.DogwoodRegistry

class SliceApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    // The entirety of what a host does to gain a product's design system: one call, with an object
    // the generator emitted from that product's own surface. See `samples/product-design-system`.
    DogwoodRegistry.register(AcmeDesignSystemBinding)
  }
}
