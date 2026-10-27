import scala.language.experimental.captureChecking

import br.com.mobilemind.safezone.SafeZone

final class Vec3(val x: Int, val y: Int, val z: Int):
  override def toString: String = s"($x, $y, $z)"

@main def run(): Unit =
  val sum = SafeZone { sz ?=>
    val a = sz.alloc(new Vec3(1, 2, 3))
    val b = sz.alloc(new Vec3(4, 5, 6))
    a.x + a.y + a.z + b.x + b.y + b.z
  }
  println(s"sum of coordinates = $sum")

  SafeZone { outer ?=>
    val a = outer.alloc(new Vec3(1, 1, 1))
    SafeZone { inner ?=>
      val b = inner.alloc(new Vec3(2, 2, 2))
      // Using `a` (from the outer, still-open zone) inside the inner zone
      // is fine: `a` isn't escaping anywhere, just being read.
      println(s"nested: ${a.x + b.x}")
    }
  }
