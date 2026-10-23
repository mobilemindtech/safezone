package safezone

import scala.language.experimental.captureChecking

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import scala.scalanative.runtime.Intrinsics.{castObjectToRawPtr, castRawPtrToLong}
import scala.scalanative.runtime.SafeZoneAllocator

class SafeZoneThreadingSuite extends munit.FunSuite:

  @noinline private def addr(o: AnyRef^): Long =
    castRawPtrToLong(castObjectToRawPtr(o))

  /** Runs `body(threadIndex)` on `n` threads started together; returns the
    * exceptions they threw. A corrupted page pool tends to hang (a cycle in
    * the free list) rather than crash, so threads are daemons and get a
    * deadline instead of being joined forever.
    */
  private def runConcurrently(n: Int)(body: Int => Unit): List[Throwable] =
    val start = new CountDownLatch(1)
    val errors = new ConcurrentLinkedQueue[Throwable]()
    val threads = (0 until n).map { t =>
      val th = new Thread(new Runnable:
        def run(): Unit =
          start.await()
          try body(t)
          catch case e: Throwable => errors.add(e): Unit
      )
      th.setDaemon(true)
      th.start()
      th
    }
    start.countDown()
    val deadline = System.currentTimeMillis() + 60000
    threads.foreach(th => th.join(math.max(1, deadline - System.currentTimeMillis())))
    val stuck = threads.count(_.isAlive)
    if stuck > 0 then fail(s"$stuck of $n threads still running after 60s")
    errors.toArray.toList.map(_.asInstanceOf[Throwable])

  test("zones on different threads never share memory") {
    val threads = 8
    val rounds = 200
    val perZone = 3000 // ~47 KiB per zone: several page claims each round
    val errors = runConcurrently(threads) { t =>
      val tag = t.toLong << 32
      var round = 0
      while round < rounds do
        SafeZone { sz ?=>
          val objs = new Array[Small](perZone)
          var i = 0
          while i < perZone do
            val s = sz.alloc(new Small(tag | i))
            objs(i) = caps.unsafe.unsafeAssumePure(s)
            // some GC pressure so collections run while zones are open
            if i % 100 == 0 then new Array[Long](64)
            i += 1
          Thread.`yield`()
          i = 0
          while i < perZone do
            val v = objs(i).v
            if v != (tag | i) then
              throw new AssertionError(
                s"thread $t round $round: object $i holds 0x${v.toHexString}"
              )
            i += 1
        }
        round += 1
    }
    assertEquals(errors, Nil)
  }

  test("many threads opening and closing tiny zones") {
    val errors = runConcurrently(16) { t =>
      var i = 0
      while i < 20000 do
        val v = SafeZone { sz ?=> sz.alloc(new Small(i)).v }
        if v != i then throw new AssertionError(s"thread $t: $v != $i")
        i += 1
    }
    assertEquals(errors, Nil)
  }

  test("allocating from a thread that didn't open the zone is rejected") {
    val errors = SafeZone { sz ?=>
      val shared = caps.unsafe.unsafeAssumePure(sz)
      runConcurrently(1) { _ =>
        addr(shared.alloc(new Small(1)))
      }
    }
    assertEquals(errors.size, 1)
    assert(errors.head.isInstanceOf[IllegalStateException], errors.head)
    assert(errors.head.getMessage.contains("can't be used from"), errors.head)
  }

  test("filling a page takes the pool lock once") {
    val claims = SafeZone { sz ?=>
      val before = SafeZoneAllocator.Impl.pageClaims()
      var i = 0
      var acc = 0L
      // 300 objects fit in one 8 KiB page even with the extra lock word
      // objects carry when multithreading is enabled (24 bytes each)
      while i < 300 do
        acc += addr(sz.alloc(new Small(i))) & 1
        i += 1
      SafeZoneAllocator.Impl.pageClaims() - before
    }
    assertEquals(claims.toLong, 1L)
  }

  test("objects bigger than a page get their own memory") {
    val sum = SafeZone { sz ?=>
      val big = sz.alloc(new Array[Long](4096)) // 32 KiB
      var i = 0
      while i < big.length do
        big(i) = i
        i += 1
      big.sum
    }
    assertEquals(sum, (0L until 4096L).sum)
  }
