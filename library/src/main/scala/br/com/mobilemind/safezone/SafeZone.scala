package br.com.mobilemind.safezone

import scala.language.experimental.captureChecking

/** Arena-backed allocation zone.
  *
  *   - Real region/arena allocation (scala-native's `scalanative_zone_*` C
  *     runtime) instead of ordinary GC allocation.
  *   - Escape-safety enforced by Scala 3's own experimental capture
  *     checker (given `import scala.language.experimental.captureChecking`
  *     at the call site).
  *   - Requires a Scala 3 version where capture checking works outside a
  *     nightly/snapshot compiler (verified on 3.8.4 and 3.10.0; 3.3.8 refuses to
  *     compile the feature at all).
  *
  * See `scala.scalanative.memory.RealSafeZone` for the implementation.
  */
type SafeZone = scala.scalanative.memory.RealSafeZone
val SafeZone = scala.scalanative.memory.RealSafeZone
