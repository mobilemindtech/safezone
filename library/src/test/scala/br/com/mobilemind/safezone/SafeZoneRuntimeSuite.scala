package br.com.mobilemind.safezone

import scala.language.experimental.captureChecking

import scala.scalanative.runtime.Intrinsics.{castObjectToRawPtr, castRawPtrToLong}

final class Small(val v: Long)
final class Payload(val data: Array[Int])
final class Holder(val p: Payload)

/** Runs on the Native target and checks that `SafeZone` really allocates in
  * scala-native's zone arena, not on the GC heap.
  *
  * Allocated objects are passed through the `@noinline` [[addr]] on purpose:
  * the Native optimizer removes allocations that never escape, which would
  * make the memory measurements below meaningless.
  */
class SafeZoneRuntimeSuite extends munit.FunSuite:

  @noinline private def addr(o: AnyRef^): Long =
    castRawPtrToLong(castObjectToRawPtr(o))

  private def isLinux: Boolean =
    sys.props.get("os.name").exists(_.toLowerCase.contains("linux"))

  private def rssKiB(): Long =
    scala.io.Source
      .fromFile("/proc/self/status")
      .getLines()
      .find(_.startsWith("VmRSS"))
      .get
      .split("\\s+")(1)
      .toLong

  test("objects are readable inside the zone") {
    val sum = SafeZone { sz ?=>
      val a = sz.alloc(new Small(20))
      val b = sz.alloc(new Small(22))
      a.v + b.v
    }
    assertEquals(sum, 42L)
  }

  test("the `safezone` alias package allocates in the same arena") {
    val sum = _root_.safezone.SafeZone { sz ?=>
      val a = sz.alloc(new Small(20))
      val b = sz.alloc(new Small(22))
      a.v + b.v
    }
    assertEquals(sum, 42L)
  }

  test("a closed zone's page is handed back out to the next zone") {
    val firsts = (1 to 3).map { _ =>
      SafeZone { sz ?=> addr(sz.alloc(new Small(1))) }
    }
    assertEquals(firsts.distinct.size, 1, firsts.map(_.toHexString))
  }

  test("a GC object referenced from the zone survives a collection") {
    val ok = SafeZone { sz ?=>
      val h = sz.alloc(new Holder(new Payload(Array.tabulate(1000)(identity))))
      var i = 0
      while i < 200000 do
        val junk = new Array[Int](100)
        junk(0) = i
        i += 1
      System.gc()
      h.p.data.sum == (0 until 1000).sum
    }
    assert(ok)
  }

  test("memory grows while a zone stays open") {
    assume(isLinux, "reads /proc/self/status")
    val before = rssKiB()
    val grown = SafeZone { sz ?=>
      var i = 0
      var acc = 0L
      while i < 2000000 do
        acc += addr(sz.alloc(new Small(i))) & 1
        i += 1
      rssKiB() - before
    }
    // 2M objects of 16 bytes ~= 31 MiB
    assert(grown > 20 * 1024, s"RSS grew only $grown KiB")
  }

  test("memory is reclaimed when zones close") {
    assume(isLinux, "reads /proc/self/status")
    val before = rssKiB()
    var round = 0
    while round < 100 do
      SafeZone { sz ?=>
        var i = 0
        var acc = 0L
        while i < 100000 do
          acc += addr(sz.alloc(new Small(i))) & 1
          i += 1
      }
      round += 1
    val grown = rssKiB() - before
    // 10M objects ~= 160 MB in total; only about one zone's worth (~1.6 MB)
    // should stay resident if pages are actually reused.
    assert(grown < 16 * 1024, s"RSS grew $grown KiB")
  }
