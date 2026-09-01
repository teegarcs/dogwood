/*
 * Project Dogwood -- the build-time dictionary check (Layer 1).
 *
 * Tags are permanent. A component's widget tag, and each of its property, slot and event tags,
 * must never change once published, because a client one dictionary version behind resolves tags
 * numerically: a renumbered tag does not fail to render, it renders **the wrong thing**, which is
 * the worst failure mode available to this architecture.
 *
 * Declaration order enforces that rule only as long as nobody reorders the surface. This check
 * enforces it mechanically, by comparing every build against a committed lock file.
 */
package dev.dogwood.codegen

import java.io.File

sealed class LockResult {
  object Unchanged : LockResult()
  data class Updated(val added: List<String>) : LockResult()
  data class Violated(val problems: List<String>) : LockResult()
}

/**
 * Compares a freshly generated dictionary against the committed lock.
 *
 * Additions are allowed and update the lock. Renumberings and removals are violations: a removed
 * component's tag must stay retired rather than be reused by the next component to be added.
 */
fun checkAgainstLock(dictionary: Dictionary, lockFile: File): LockResult {
  if (!lockFile.exists()) {
    lockFile.parentFile?.mkdirs()
    lockFile.writeText(dictionary.encode())
    return LockResult.Updated(dictionary.components.map { it.name })
  }

  val locked = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    .decodeFromString(Dictionary.serializer(), lockFile.readText())

  val problems = mutableListOf<String>()
  val added = mutableListOf<String>()

  for (lockedEntry in locked.components) {
    val current = dictionary.components.firstOrNull { it.name == lockedEntry.name }
    if (current == null) {
      problems += "${lockedEntry.name} was removed; its tag ${lockedEntry.localTag} must stay retired, not be reused"
      continue
    }
    if (current.localTag != lockedEntry.localTag) {
      problems += "${lockedEntry.name} moved from tag ${lockedEntry.localTag} to ${current.localTag}"
    }
    for ((name, tag) in lockedEntry.properties) {
      val now = current.properties[name]
      if (now == null) {
        problems += "${lockedEntry.name}.$name was removed; parameters may only be added"
      } else if (now != tag) {
        problems += "${lockedEntry.name}.$name moved from property tag $tag to $now"
      }
    }
    for ((name, tag) in lockedEntry.events) {
      val now = current.events[name]
      if (now != null && now != tag) {
        problems += "${lockedEntry.name}.$name moved from event tag $tag to $now"
      }
    }
  }

  for (entry in dictionary.components) {
    if (locked.components.none { it.name == entry.name }) added += entry.name
  }

  return when {
    problems.isNotEmpty() -> LockResult.Violated(problems)
    added.isNotEmpty() -> {
      lockFile.writeText(dictionary.encode())
      LockResult.Updated(added)
    }
    else -> {
      lockFile.writeText(dictionary.encode())
      LockResult.Unchanged
    }
  }
}
