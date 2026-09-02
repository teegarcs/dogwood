/*
 * Project Dogwood -- which experiences stay warm.
 *
 * The policy half of the host shell, separated from the machinery so it can be reasoned about and
 * tested without a QuickJS instance in the room. What it decides is genuinely a policy question --
 * how many interpreters a product is willing to hold to make switching instant -- and policy that
 * lives inside an effect is policy nobody can check.
 *
 * The rule is least-recently-used, with one refinement that matters on a phone: an entry that is
 * **on screen** is never a candidate, whatever the cap or the memory pressure. Evicting what the
 * user is looking at would be a cold start in front of them, which is the exact thing the shell
 * exists to avoid.
 *
 * "On screen" is not the same as "active". A host may compose two experiences side by side -- a
 * navigation rail from one team beside a content pane from another -- and both are visible even
 * though only one is the most recently touched. Protecting only the active one would let a cap
 * evict the rail out from under a user who was looking straight at it, which is why mounting is
 * explicit rather than inferred from recency.
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

  /** Entries a host is currently composing. Never evicted; see [mount]. */
  private val mounted = mutableSetOf<String>()

  val warm: List<String> get() = order.toList()

  val active: String? get() = order.firstOrNull()

  /** Entries the host has declared on screen. */
  val onScreen: Set<String> get() = mounted.toSet()

  fun isWarm(key: String): Boolean = key in order

  /**
   * Declares [key] on screen, so no cap or memory trim may evict it. Returns what to evict.
   *
   * Mounting also marks the entry used, because composing an experience is using it.
   *
   * **Mounted entries can push the warm set past [capacity], and that is deliberate.** The
   * alternative to overrunning the cap is tearing down a surface the user is looking at, which is
   * never the better answer; a host that mounts more experiences than it budgeted for should see
   * the memory rather than a blank pane. [warm] reports the true size, so it stays visible.
   */
  fun mount(key: String): List<String> {
    mounted.add(key)
    return touch(key)
  }

  /** Withdraws [key] from the screen. It becomes an ordinary eviction candidate again. */
  fun unmount(key: String): List<String> {
    mounted.remove(key)
    return trim(capacity)
  }

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
   * Never returns anything on screen, even for `keep = 0`: something has to be drawn, and a shell
   * that dropped it would answer a memory warning with a blank frame. If everything warm is on
   * screen, this evicts nothing and says so by returning an empty list, rather than overriding the
   * host to hit a number.
   */
  fun trim(keep: Int): List<String> {
    val floor = maxOf(keep, 1)
    val protected = buildSet {
      addAll(mounted)
      order.firstOrNull()?.let { add(it) }
    }
    // Walked from the coldest end so the least recently used goes first, and skipping anything on
    // screen rather than counting it against the floor -- a protected entry is not a slot the host
    // can reclaim, so treating it as one would evict a cold entry that was already fine.
    val evicted = mutableListOf<String>()
    var index = order.size - 1
    while (order.size > floor && index >= 0) {
      if (order[index] !in protected) evicted.add(order.removeAt(index))
      index--
    }
    // Most recently used first, matching [warm].
    return evicted.asReversed().toList()
  }

  /** Forgets [key] entirely -- it was closed for a reason other than the cap. */
  fun forget(key: String) {
    order.remove(key)
    mounted.remove(key)
  }

  fun clear() {
    order.clear()
    mounted.clear()
  }
}
