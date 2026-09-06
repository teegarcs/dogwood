/*
 * Project Dogwood -- the guest runtime's seam for generated code.
 *
 * A component's guest stub has to reach the recording machinery: allocate a node, write a property
 * against the recorder, install an event handler, open a children slot. Those were `internal` to
 * `dogwood-compose`, which was correct while every stub the generator produced landed in
 * `dev.dogwood.compose` — and became wrong the moment a **product** could have its own segment,
 * because a product's stubs are generated into a product's module and `internal` does not reach
 * across one.
 *
 * The finding is worth recording rather than just fixing: nothing about the registration mechanism
 * was visibly one-caller-shaped, and this is where the assumption had actually been made.
 *
 * So the seam is public and opt-in. `@RequiresOptIn` is the right shape because the audience is not
 * "nobody" — it is *generated code*, which is exactly one caller that should not have to ask
 * permission and a thousand that should. A guest author who writes `recording.recorder.property(…)`
 * by hand gets an error naming the reason; the generator emits the opt-in and does not.
 */
package dev.dogwood.compose

/**
 * Marks the parts of the guest runtime that exist for **generated stubs**, not for guest code.
 *
 * Opting in by hand means writing the boundary yourself: allocating node identifiers, choosing
 * property tags, keeping them stable across releases. All of that is what the dictionary and its
 * lock exist to own, and hand-written calls are outside both — a hand-picked tag that collides
 * with a generated one does not fail to render, it renders the wrong widget.
 *
 * If you find yourself wanting this, the thing you want is a component on your surface.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This is the guest runtime's seam for generated stubs. Add a component to your " +
    "surface and let the generator emit it; hand-written recording is outside the dictionary " +
    "lock, and a hand-picked tag that collides with a generated one renders the wrong widget.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class DogwoodGeneratedApi
