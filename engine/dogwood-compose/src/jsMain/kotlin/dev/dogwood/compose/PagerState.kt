/*
 * Project Dogwood -- the guest's half of a pager.
 *
 * The sixth holder shape: scroll's, over a discrete quantity. A page index needs no quantum,
 * because it is already the granularity anyone cares about -- so where `ScrollState` declares how
 * often to be told, this is simply told when the page changes.
 */
@file:OptIn(DogwoodGeneratedApi::class)

package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Which page a pager is on: what the guest asked for, and what the host last reported. */
class PagerState internal constructor(initialPage: Int) {

  /** Where the host says the pager is. */
  var currentPage: Int by mutableStateOf(initialPage)
    private set

  /**
   * How many pages the host laid out.
   *
   * Reported rather than declared, because the guest emits pages as content and the host is what
   * counts them -- and a guest that assumed its own count would be wrong the moment a page was
   * conditionally composed.
   */
  var pageCount: Int by mutableStateOf(0)
    private set

  /** True when the last change was the user's swipe rather than this guest's request landing. */
  var lastChangeByUser: Boolean by mutableStateOf(false)
    private set

  @DogwoodGeneratedApi
  var targetPage: Int by mutableStateOf(initialPage)
    private set

  @DogwoodGeneratedApi
  var targetSequence: Int by mutableStateOf(0)
    private set

  @DogwoodGeneratedApi
  var targetAnimated: Boolean by mutableStateOf(true)
    private set

  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  init {
    // A restored position is a target like any other -- the same line `ScrollState` carries, for
    // the same reason: restoring is not a special path, it is a request made at start.
    if (initialPage != 0) targetSequence = 1
  }

  /** The page a guest branches on. Reading it is what claims presence. */
  val page: Int
    get() {
      watching = true
      return currentPage
    }

  /** Declares which page the pager should be on, animating there. */
  fun animateScrollToPage(page: Int) = declare(page, animated = true)

  /** As [animateScrollToPage], but immediately -- for restoring a position, not for navigating. */
  fun scrollToPage(page: Int) = declare(page, animated = false)

  private fun declare(page: Int, animated: Boolean) {
    // Negative pages are refused here rather than sent: the host would clamp anyway, and a request
    // the guest can see is wrong is one the guest should not make.
    targetPage = if (page < 0) 0 else page
    targetAnimated = animated
    targetSequence += 1
  }

  /** Called from the host's report. */
  @DogwoodGeneratedApi
  fun report(page: Int, pageCount: Int, byUser: Boolean) {
    currentPage = page
    this.pageCount = pageCount
    lastChangeByUser = byUser
  }

  companion object {
    /** Saves the position and not the request, as every holder here does. */
    val Saver: Saver<PagerState, Any> = listSaver(
      save = { listOf(it.currentPage) },
      restore = { PagerState(it[0] as Int) },
    )
  }
}

/** Remembers a pager's position across recomposition **and across a code update**. */
@Composable
fun rememberPagerState(initialPage: Int = 0): PagerState =
  rememberSaveable(saver = PagerState.Saver) { PagerState(initialPage) }
