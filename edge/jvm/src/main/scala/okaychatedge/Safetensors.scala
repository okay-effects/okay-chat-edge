package okaychatedge

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.UTF_8

/** A SAFETENSORS FILE in the JVM's memory: its header (a JSON object) and its tensors, read little-endian */
final class Safetensors(bytes: Array[Byte]):
  private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  private val headerLen = buf.getLong(0).toInt
  private val header = J.parse(String(bytes, 8, headerLen, UTF_8))
  private val base = 8 + headerLen

  def meta(key: String): Option[String] = header("__metadata__").get(key).flatMap(_.str)

  private def range(name: String): (Int, Int) =
    val offs = header(name)("data_offsets").arr.flatMap(_.num)
    (base + offs(0).toInt, base + offs(1).toInt)

  def u16(name: String): U16s =
    val (a, b) = range(name)
    U16s(Array.tabulate((b - a) / 2)(i => buf.getChar(a + 2 * i)))

  def f64(name: String): F64s =
    val (a, b) = range(name)
    F64s(Array.tabulate((b - a) / 8)(i => buf.getDouble(a + 8 * i)))

  def u64(name: String): Vector[Long] =
    val (a, b) = range(name)
    Vector.tabulate((b - a) / 8)(i => buf.getLong(a + 8 * i))

  /** strings stored as their UTF-8 bytes end to end, `offsets` where each starts (and the last ends) */
  def strings(offsets: String, bytesOf: String): Vector[String] =
    val offs = u64(offsets)
    val (a, _) = range(bytesOf)
    Vector.tabulate(math.max(0, offs.length - 1))(i =>
      String(bytes, a + offs(i).toInt, (offs(i + 1) - offs(i)).toInt, UTF_8))

/** a tensor of 16-bit values */
final class U16s(a: Array[Char]):
  inline def apply(i: Int): Int = a(i).toInt
  def length: Int = a.length

/** a tensor of doubles */
final class F64s(a: Array[Double]):
  inline def apply(i: Int): Double = a(i)
  def length: Int = a.length
