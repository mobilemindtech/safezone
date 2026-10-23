package scala.scalanative.memory

import scala.language.experimental.captureChecking

import scala.scalanative.runtime.{RawPtr, RawSize, SafeZoneAllocator}

/** Real arena-backed zone, wired to scala-native's own dormant
  * `scala.scalanative.memory.SafeZone` trait (`nativelib`'s stable,
  * non-`scala-next` source set — a `private[scalanative]` placeholder kept
  * around only "to avoid linking error when using scala versions other
  * than scala-next", per its own doc comment). Extending it from here
  * activates codegen's virtual dispatch to `allocImpl` for real, because
  * this file lives inside package `scala.scalanative.memory` and can
  * therefore name that otherwise-inaccessible trait.
  *
  * `alloc` returns a capturing type `T^{this}`: escape-safety is enforced
  * by Scala 3's own (experimental) capture checker.
  */
final class RealSafeZone private[scalanative] (
    private[scalanative] val handle: RawPtr
) extends SafeZone {

  private var openFlag = true

  /** Zones are confined to the thread that opened them: the arena's bump
    * pointer isn't synchronized. Different threads can use their own zones
    * concurrently.
    */
  private val owner: Thread = Thread.currentThread()

  private[scalanative] def isOpen: Boolean = openFlag

  private[scalanative] def close(): Unit = {
    if (!openFlag)
      throw new IllegalStateException(s"SafeZone $this is already closed.")
    openFlag = false
    SafeZoneAllocator.Impl.close(handle)
  }

  /** Invoked virtually by codegen's `classalloc`/`arrayalloc` lowering, not
    * called directly from Scala source.
    */
  override def allocImpl(cls: RawPtr, size: RawSize): RawPtr = {
    if (!openFlag)
      throw new IllegalStateException(s"SafeZone $this is already closed.")
    if (Thread.currentThread() ne owner)
      throw new IllegalStateException(
        s"SafeZone $this was opened by thread ${owner.getName} and can't be used from ${Thread.currentThread().getName}."
      )
    SafeZoneAllocator.Impl.alloc(handle, cls, size)
  }

  /** Allocates `obj` in this zone's arena. `obj` must be an instance
    * creation expression (`new T(...)`); see `SafeZoneAllocator.allocate`.
    */
  inline infix def alloc[T <: AnyRef](inline obj: T): T^{this} =
    SafeZoneAllocator.allocate(this, obj)
}

object RealSafeZone {

  /** Opens a fresh arena for the duration of `f` and reclaims it afterwards.
    * The capability `sz` (and anything allocated through it) cannot escape
    * `f`'s scope: the capture checker rejects any attempt to return it, or a
    * value derived from it, as part of `T`.
    */
  def apply[T](f: (RealSafeZone^) ?=> T): T = {
    val sz: RealSafeZone^ = new RealSafeZone(SafeZoneAllocator.Impl.open())
    try f(using sz)
    finally sz.close()
  }
}
