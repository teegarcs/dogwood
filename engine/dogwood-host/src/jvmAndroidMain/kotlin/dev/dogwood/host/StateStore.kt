/*
 * Project Dogwood -- saved state that outlives the process.
 *
 * The shell already snapshots an experience on eviction and restores it on return, and a code
 * update already carries state from the outgoing guest into its replacement. Both live entirely in
 * memory, which is correct for what they do and useless for the case a user actually notices:
 * Android reclaims a backgrounded application whenever it likes, and everything above dies with it.
 * A user who backgrounds a half-typed address and comes back an hour later finds an empty form, and
 * nothing about that is distinguishable from a bug.
 *
 * **Persisting a snapshot changes what a snapshot is.** Until now one lived microseconds inside a
 * process and never left memory. On disk it is user data at rest, and it contains whatever the
 * guest declared saveable -- measured, for a card-number field, as the digits in plain text. So:
 *
 *  - it is written to the application's private storage, which Android isolates per application and
 *    which is covered by device encryption, and never to shared or external storage;
 *  - it is bounded in size and in age, because state nobody has come back to in a day is not worth
 *    keeping and a snapshot that grows without limit is a disk leak;
 *  - it is deleted the moment it is consumed, so it exists only across the gap it is for;
 *  - and a guest can keep a field out of it entirely -- `rememberTextFieldState(sensitive = true)`
 *    saves the field's shape and not its contents.
 *
 * The last one matters most. Nothing here can decide whether a particular field is too sensitive to
 * survive a process; only the code that declared it can, so this is a seam rather than a policy.
 */
package dev.dogwood.host

import dev.dogwood.protocol.StateSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

@Serializable
private class PersistedState(
  val writtenAtEpochMillis: Long,
  val states: Map<String, StateSnapshot>,
)

/**
 * Holds one process's worth of experience state across a death and a restart.
 *
 * @param maxBytes refuse to write a snapshot larger than this. A snapshot is normally tiny -- tens
 *   of bytes for a realistic screen -- so a large one means a guest is saving something it should
 *   not, and silently writing it would turn a guest bug into a disk problem.
 * @param maxAgeMillis ignore state older than this on read. Returning to a form a day later and
 *   finding it half-filled is stranger than finding it empty.
 */
class DogwoodStateStore(
  private val file: Path,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
  private val maxBytes: Long = 256L * 1024,
  private val maxAgeMillis: Long = 24L * 60 * 60 * 1000,
  private val onProblem: (String) -> Unit = {},
) {
  private val json = Json { ignoreUnknownKeys = true }

  /** Writes [states], replacing anything already stored. An empty map clears the store. */
  fun write(states: Map<String, StateSnapshot>, nowEpochMillis: Long) {
    if (states.isEmpty()) {
      clear()
      return
    }
    val encoded = runCatching {
      json.encodeToString(
        PersistedState.serializer(),
        PersistedState(nowEpochMillis, states),
      )
    }.getOrElse {
      onProblem("could not encode saved state: ${it.message}")
      return
    }
    if (encoded.length > maxBytes) {
      // Deliberately not truncated. A partial snapshot restores a screen into a state its guest
      // never composed, which is worse than restoring nothing.
      onProblem("saved state is ${encoded.length} bytes, over the ${maxBytes} byte cap; dropped")
      clear()
      return
    }
    runCatching {
      file.parent?.let { fileSystem.createDirectories(it) }
      fileSystem.write(file) { writeUtf8(encoded) }
    }.onFailure { onProblem("could not write saved state: ${it.message}") }
  }

  /**
   * Reads and **consumes** the stored state.
   *
   * Consuming rather than merely reading, because this state exists to cross exactly one gap. A
   * snapshot left in place would be restored again after the next clean exit, putting a user back
   * into a form they had already finished with.
   */
  fun consume(nowEpochMillis: Long): Map<String, StateSnapshot> {
    if (!fileSystem.exists(file)) return emptyMap()
    val text = runCatching { fileSystem.read(file) { readUtf8() } }.getOrElse {
      onProblem("could not read saved state: ${it.message}")
      clear()
      return emptyMap()
    }
    clear()
    val decoded = runCatching { json.decodeFromString(PersistedState.serializer(), text) }
      .getOrElse {
        // A snapshot written by a build whose format has since changed. Skew, not a crash.
        onProblem("could not decode saved state: ${it.message}")
        return emptyMap()
      }
    val age = nowEpochMillis - decoded.writtenAtEpochMillis
    if (age !in 0..maxAgeMillis) {
      onProblem("saved state is ${age}ms old, past the ${maxAgeMillis}ms limit; ignored")
      return emptyMap()
    }
    return decoded.states
  }

  fun clear() {
    runCatching { fileSystem.delete(file, mustExist = false) }
      .onFailure { onProblem("could not clear saved state: ${it.message}") }
  }
}
