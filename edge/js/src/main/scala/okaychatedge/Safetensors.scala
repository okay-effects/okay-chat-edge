package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/**
 * A SAFETENSORS FILE in a Worker's memory: its header (a JSON object) and views of its tensors' bytes. Nothing is
 * copied but what alignment asks for: an `F16` matrix stays half-precision, read where it lies.
 */
final class Safetensors(buf: ArrayBuffer):
  private val dv = new DataView(buf)
  // a u64, little-endian: a header under 4 GB
  private val headerLen = dv.getUint32(0, true).toInt
  private val header = js.JSON.parse(Safetensors.utf8(new Uint8Array(buf, 8, headerLen)))
    .asInstanceOf[js.Dictionary[js.Dynamic]]
  private val base = 8 + headerLen

  def meta(key: String): Option[String] =
    header.get("__metadata__").flatMap(m => m.asInstanceOf[js.Dictionary[String]].get(key))

  private def range(name: String): (Int, Int) =
    val offs = header(name).data_offsets.asInstanceOf[js.Array[Double]]
    (base + offs(0).toInt, base + offs(1).toInt)

  def bytes(name: String): Uint8Array =
    val (a, b) = range(name)
    new Uint8Array(buf, a, b - a)

  /** a 16-bit tensor: in place when aligned, else copied */
  def u16(name: String): U16s =
    val (a, b) = range(name)
    U16s(if a % 2 == 0 then new Uint16Array(buf, a, (b - a) / 2) else new Uint16Array(buf.slice(a, b)))

  def f64(name: String): F64s =
    val (a, b) = range(name)
    F64s(if a % 8 == 0 then new Float64Array(buf, a, (b - a) / 8) else new Float64Array(buf.slice(a, b)))

  /** strings stored as their UTF-8 bytes end to end, `offsets` where each starts (and the last ends) */
  def strings(offsets: String, bytesOf: String): Vector[String] =
    val offs = u64(offsets)
    val all = bytes(bytesOf)
    Vector.tabulate(math.max(0, offs.length - 1))(i =>
      Safetensors.utf8(all.subarray(offs(i).toInt, offs(i + 1).toInt)))

  /** a `U64` tensor of offsets, read as doubles (each under 2^53) */
  def u64(name: String): js.Array[Double] =
    val (a, b) = range(name)
    val n = (b - a) / 8
    val out = new js.Array[Double](n)
    var i = 0
    while i < n do
      out(i) = dv.getUint32(a + 8 * i, true).toDouble + dv.getUint32(a + 8 * i + 4, true).toDouble * 4294967296.0
      i += 1
    out

object Safetensors:
  private lazy val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8")
  def utf8(b: Uint8Array): String = decoder.decode(b).asInstanceOf[String]

/** a tensor of 16-bit values, read where it lies */
final class U16s(a: Uint16Array):
  inline def apply(i: Int): Int = a(i)
  def length: Int = a.length

/** a tensor of doubles */
final class F64s(a: Float64Array):
  inline def apply(i: Int): Double = a(i)
  def length: Int = a.length
