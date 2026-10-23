package scala.scalanative.runtime

import scala.scalanative.unsafe._

/** Bridge into scala-native's own safe-zone arena machinery.
  *
  * `nscplugin` recognizes `allocate` purely by its fully-qualified name
  * (`NirDefinitions.RuntimeSafeZoneAllocator_allocate`, an `optional(...)`
  * classpath lookup) and, when found, lowers calls to it into
  * `classalloc`/`arrayalloc` NIR ops carrying a zone operand
  * (`NirGenExpr.genSafeZoneAlloc`). Codegen later turns that operand into a
  * virtual call to `SafeZone#allocImpl` (see `RealSafeZone`), which is why
  * this object needs to exist under this exact package/name even though it
  * ships in our library rather than in scala-native's own `nativelib` for
  * this Scala version.
  *
  * `Impl` binds this library's own thread-safe arena
  * (`resources/scala-native/safezone/arena.c`) rather than scala-native's
  * `scalanative_zone_*` runtime, whose shared page pool has no
  * synchronization.
  */
object SafeZoneAllocator {

  /** The argument `obj` must be a `new T(...)` (or `new Array(...)`)
    * expression, syntactically, at the call site: the plugin pattern-matches
    * on that shape. `RealSafeZone#alloc` is `inline` so that a source call
    * like `sz.alloc(new A(1))` still has that shape after inlining.
    */
  def allocate[T](sz: scala.scalanative.memory.SafeZone, obj: T): T = intrinsic

  @extern @define("__SAFEZONE_ARENA") object Impl {
    @name("safezone_arena_open")
    def open(): RawPtr = extern

    @name("safezone_arena_alloc")
    def alloc(rawzone: RawPtr, rawty: RawPtr, size: RawSize): RawPtr = extern

    @name("safezone_arena_close")
    def close(rawzone: RawPtr): Unit = extern

    /** Number of pages handed out to zones so far (each one took the pool
      * lock once). For tests and diagnostics.
      */
    @name("safezone_arena_page_claims")
    def pageClaims(): CSize = extern
  }
}
