package dev.dogwood.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

actual fun nanoTime(): Long = System.nanoTime()

actual fun readFileBytes(path: String): ByteArray = File(path).readBytes()

actual fun writeTextFile(path: String, text: String) {
  val file = File(path)
  file.parentFile?.mkdirs()
  file.writeText(text)
}

actual fun platformDescription(): String =
  "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}"

actual fun gzippedSizeOrNull(bytes: ByteArray): Int? {
  val out = ByteArrayOutputStream()
  GZIPOutputStream(out).use { it.write(bytes) }
  return out.size()
}
