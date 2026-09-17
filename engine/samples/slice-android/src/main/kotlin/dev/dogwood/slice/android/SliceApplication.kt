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
    /*
     * The Material 3 tier (plans/generator-v2.md). Explicit rather than built in, so a host can
     * leave it out; here, in `Application.onCreate`, for the reason Acme's is here.
     *
     * Behind a build flag because "a host that does not have the tier" is a thing a drill has to be
     * able to produce: the payload declares `androidx.material3`, and a client without it must
     * refuse the release before `start` rather than render a screen of placeholders (claim `B6`).
     * `-PdogwoodMaterial3=false` builds that client. The default is the ordinary product build.
     */
    if (BuildConfig.DOGWOOD_MATERIAL3) {
      DogwoodRegistry.register(dev.dogwood.material3.Material3Binding)
    }
    /*
     * The foundation, layout and ui tiers (plans/close-the-backlog.md 2.3), the same way and for
     * the same reason: three segments in one module, registered explicitly so a host can leave
     * them out. Not behind the Material 3 flag -- that flag exists to build a client with no
     * `androidx.material3`, which is a different question from whether this client has the
     * generated layout primitives.
     */
    DogwoodRegistry.register(dev.dogwood.foundation.FoundationBinding)
    DogwoodRegistry.register(dev.dogwood.foundation.FoundationLayoutBinding)
    DogwoodRegistry.register(dev.dogwood.foundation.UiBinding)
  }
}
