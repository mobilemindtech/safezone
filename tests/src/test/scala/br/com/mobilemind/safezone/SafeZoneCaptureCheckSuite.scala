package br.com.mobilemind.safezone

import dotty.tools.dotc.Driver
import dotty.tools.dotc.reporting.{Reporter, StoreReporter}

import java.nio.file.Files

/** Compiles small snippets against the Native build of `library` and checks
  * that Scala 3's experimental capture checker accepts legitimate zone usage
  * and rejects escapes of zone-bound values, the same way
  * `example/src/main/scala/Main.scala` uses it.
  */
class SafeZoneCaptureCheckSuite extends munit.FunSuite:

  private val classpath: String =
    sys.props("safezone.test.classpath")

  private def compile(source: String): Reporter =
    val tmpDir = Files.createTempDirectory("safezone-arena-test")
    val srcFile = tmpDir.resolve("Snippet.scala")
    Files.writeString(srcFile, source)
    val outDir = Files.createDirectory(tmpDir.resolve("out"))

    val reporter = new StoreReporter(null, false)
    val args = Array(
      "-d",
      outDir.toString,
      "-classpath",
      classpath,
      "-nowarn:false",
      srcFile.toString
    )
    new Driver().process(args, reporter, null)
    reporter

  private def wrap(body: String, imports: String = "br.com.mobilemind.safezone.SafeZone"): String =
    s"""import scala.language.experimental.captureChecking
       |import $imports
       |
       |final class Vec3(val x: Int, val y: Int, val z: Int)
       |
       |object Snippet:
       |$body
       |""".stripMargin

  test("pos: extracting primitives out of the arena zone compiles cleanly") {
    val reporter = compile(wrap("""
      |  def run(): Int =
      |    SafeZone { sz ?=>
      |      val p = sz.alloc(new Vec3(1, 2, 3))
      |      p.x + p.y + p.z
      |    }
      |""".stripMargin))
    assert(!reporter.hasErrors, reporter.allErrors.map(_.message).mkString("\n"))
  }

  test("pos: reading an outer, still-open zone's value from a nested zone compiles cleanly") {
    val reporter = compile(wrap("""
      |  def run(): Int =
      |    SafeZone { outer ?=>
      |      val a = outer.alloc(new Vec3(1, 1, 1))
      |      SafeZone { inner ?=>
      |        val b = inner.alloc(new Vec3(2, 2, 2))
      |        a.x + b.x
      |      }
      |    }
      |""".stripMargin))
    assert(!reporter.hasErrors, reporter.allErrors.map(_.message).mkString("\n"))
  }

  test("neg: returning a zone-bound value as the block's result is rejected") {
    val reporter = compile(wrap("""
      |  def run(): Vec3 =
      |    SafeZone { sz ?=>
      |      val p = sz.alloc(new Vec3(1, 2, 3))
      |      p
      |    }
      |""".stripMargin))
    assert(reporter.hasErrors)
    assert(reporter.allErrors.exists(_.message.contains("outlives its scope")))
  }

  test("pos: the `safezone` alias package exposes the same API") {
    val reporter = compile(wrap("""
      |  def run(): Int =
      |    SafeZone { sz ?=>
      |      val p = sz.alloc(new Vec3(1, 2, 3))
      |      p.x + p.y + p.z
      |    }
      |""".stripMargin, imports = "safezone.*"))
    assert(!reporter.hasErrors, reporter.allErrors.map(_.message).mkString("\n"))
  }

  test("neg: escapes are still rejected through the `safezone` alias package") {
    val reporter = compile(wrap("""
      |  def run(): Vec3 =
      |    SafeZone { sz ?=>
      |      val p = sz.alloc(new Vec3(1, 2, 3))
      |      p
      |    }
      |""".stripMargin, imports = "safezone.*"))
    assert(reporter.hasErrors)
    assert(reporter.allErrors.exists(_.message.contains("outlives its scope")))
  }

  test("neg: assigning a zone-bound value to an outer var is rejected") {
    val reporter = compile(wrap("""
      |  var leaked: Vec3 = null
      |  def run(): Unit =
      |    SafeZone { sz ?=>
      |      val p = sz.alloc(new Vec3(1, 2, 3))
      |      leaked = p
      |    }
      |""".stripMargin))
    assert(reporter.hasErrors)
    // Different diagnostic shape than the direct-return case ("cannot flow
    // into capture set {}" rather than "outlives its scope"), since here the
    // failure is a capture-set mismatch against `leaked`'s plain (non-capturing)
    // declared type rather than an existential-avoidance failure at a generic
    // return boundary.
    assert(reporter.allErrors.exists(_.message.contains("cannot flow into capture set")))
  }

  // NOT a real safety guarantee: empirically, a closure that captures a
  // zone-bound value and escapes (by return, or by being called after the
  // zone closes) compiles cleanly under 3.10.0's (and 3.8.4's) capture checker as configured
  // here. Verified for: returning `() => p.x` directly, annotating it with
  // the "pure" arrow type `() -> Int`, and calling the escaped closure after
  // `SafeZone` has returned (i.e. after the underlying arena is closed) --
  // none of these get flagged. This is a genuine unsoundness gap in this
  // project, not a test bug: unlike returning the zone-bound
  // value itself (rejected, see above), escape *through a closure* is not
  // currently caught. Flagging this in case a future scala3-compiler version
  // starts catching it (this test would then start failing, prompting a
  // second look), or in case someone assumes the closure case is covered
  // because the direct-value case is.
  test("neg (KNOWN GAP, currently unenforced): a closure capturing a zone-bound value and escaping via return is NOT rejected") {
    val reporter = compile(wrap("""
      |  def run(): () => Int =
      |    SafeZone { sz ?=>
      |      val p = sz.alloc(new Vec3(1, 2, 3))
      |      val f = () => p.x
      |      f
      |    }
      |""".stripMargin))
    assert(!reporter.hasErrors, reporter.allErrors.map(_.message).mkString("\n"))
  }
