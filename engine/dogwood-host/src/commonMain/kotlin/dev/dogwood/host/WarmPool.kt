/*
 * Project Dogwood -- which experiences stay warm.
 *
 * The policy half of the host shell, separated from the machinery so it can be reasoned about and
 * tested without a QuickJS instance in the room. What it decides is genuinely a policy question --
 * how many interpreters a product is willing to hold to make switching instant -- and policy that
 * lives inside an effect is policy nobody can check.
 *
 * The rule is least-recently-used, with one refinement that matters on a phone: the **active**
 * entry is never a candidate, whatever the cap or the memory pressure. Evicting what the user is
 * looking at would be a cold start in front of them, which is the exact thing the shell exists to
 * avoid.
 */
package dev.dogwood.host

/**
 * Tracks which keys are warm and in what order they were last used.
 *
 * Holds no experiences and no memory of its own; the shell maps its answers onto real sessions.
 */
class WarmPool(
  /**
   * How many entries may be warm at once, including the active one.
   *
   * Three is the default because it covers the shape products actually have -- the screen you are
   * on, the one you came from, and the one you keep going back to -- without pretending memory is
   * free. Each warm entry is a whole interpreter.
   */
  val capacity: Int = 3,
) {
  init {
    require(capacity >= 1) { "a shell must keep at least the active experience warm" }
  }

  /** Most recently used first. */
  private val order = mutableListOf<String>()

  val warm: List<String> get() = order.toList()

  val active: String? get() = order.firstOrNull()

  fun isWarm(key: String): Boolean = key in order

  /**
   * Marks [key] active, and returns the keys that must be evicted to stay within [capacity].
   *
   * Returned rather than acted on, because evicting is suspending work -- a snapshot has to cross
   * to the guest thread first -- and a policy that suspended could not be tested as a policy.
   */
  fun touch(key: String): List<String> {
    order.remove(key)
    order.add(0, key)
    return trim(capacity)
  }

  /**
   * Reduces the pool to [keep] entries, returning what to evict. For memory pressure.
   *
   * Never returns the active entry, even for `keep = 0`: something has to be on screen, and a
   * shell that dropped it would answer a memory warning with a blank frame.
   */
  fun trim(keep: Int): List<String> {
    val floor = maxOf(keep, 1)
    if (order.size <= floor) return emptyList()
    val evicted = order.drop(floor)
    order.subList(floor, order.size).clear()
    return evicted
  }

  /** Forgets [key] entirely -- it was closed for a reason other than the cap. */
  fun forget(key: String) {
    order.remove(key)
  }

  fun clear() {
    order.clear()
  }
}
