# safezone

Real arena allocation for Scala Native classes, with escape-safety checked
by Scala 3's capture checker. It reproduces the surface API from
[scala-native/scala-native#3120](https://github.com/scala-native/scala-native/pull/3120)
("Safe Zone: Memory-Safe Zone with Capture Checking and GC") on a stable
Scala Native + Scala 3 toolchain, without forking either.

```scala
import scala.language.experimental.captureChecking
import br.com.mobilemind.safezone.SafeZone

val sum = SafeZone { sz ?=>
  val a = sz.alloc(new Point(1, 2))   // allocated in the zone's arena
  val b = sz.alloc(new Point(3, 4))
  a.x + a.y + b.x + b.y               // fine: plain values leave the zone
}                                     // arena reclaimed here
```

Letting `a`/`b` (or anything derived from them) leak out of the block is a
**compile error**:

```
Capability `sz` outlives its scope: it leaks into outer capture set 's1
which is owned by method leaks.
```

## Usage

Requirements: Scala Native 0.5.x (tested with 0.5.12) and Scala 3.8 or
newer (tested with 3.10.0). Not published to a repository yet; run
`sbt library/publishLocal` and then:

```scala
// build.sbt of a Scala Native project
libraryDependencies += "br.com.mobilemind" %%% "safezone-library" % "0.1.0-SNAPSHOT"
```

Every file that uses zones needs
`import scala.language.experimental.captureChecking`.

The API lives in `br.com.mobilemind.safezone`; the short package `safezone`
re-exports it, so `import safezone.*` (or `import safezone.SafeZone`) works
too.

- `SafeZone { sz ?=> ... }` opens a zone, runs the block and frees
  everything allocated in it when the block ends, normally or by exception.
- `sz.alloc(new T(...))` allocates a class instance in the zone;
  `sz.alloc(new Array[T](n))` allocates an array. The memory is zeroed
  before the constructor runs, like a regular `new`.
- Zones can be nested, and objects from an outer zone can be used inside an
  inner one.
- The block's result can be anything that doesn't capture `sz`: primitives,
  strings, or GC objects built from data read out of the zone.
- Zone objects can reference GC objects (they're kept alive), but GC objects
  must not keep references to zone objects past the zone's end.

## How it works

Both halves already exist in the stable toolchain:

- **The `new` → zone lowering.** `nscplugin` and codegen already ship the
  NIR plumbing (`classalloc`/`arrayalloc` with a zone operand,
  `genSafeZoneAlloc`) unconditionally in the published toolchain. It stays
  dormant because the public Scala API for it only exists in scala-native's
  unreleased `scala-next` source set. This library activates it:
  - `scala.scalanative.runtime.SafeZoneAllocator.allocate` is defined at
    the exact fully-qualified name `nscplugin` looks up. When it's on the
    classpath, `sz.alloc(new T(...))` is lowered to a zone allocation.
  - `scala.scalanative.memory.RealSafeZone` extends scala-native's own
    `private[scalanative]` `SafeZone` trait; codegen calls its `allocImpl`
    for every zone allocation.
- **The arena.** `allocImpl` uses this library's own allocator
  (`library/src/main/resources/scala-native/safezone/arena.c`), not
  scala-native's `scalanative_zone_*` runtime, whose global page pool has
  no synchronization. Each zone bump-allocates into its own 8 KiB page
  without locking; only taking pages from and returning them to the shared
  pool takes a mutex. Pages in use are registered as GC roots, so GC objects
  referenced from zone objects stay alive.
- **The safety check.** `alloc` returns `T^{this}`, so Scala 3's experimental
  capture checker (`-language:experimental.captureChecking`, usable without
  a nightly compiler since the 3.8.x line) rejects escapes.

## Caveats

- **A zone belongs to the thread that opened it.** Different threads can
  use their own zones concurrently, but allocating through a zone from
  another thread throws `IllegalStateException`. The capture checker knows
  nothing about threads, though: reading zone objects from another thread
  after the zone closed is still a use-after-free.
- **Native-only.** There's no JVM equivalent of the C-runtime arena.
- **Requires `import scala.language.experimental.captureChecking`** at
  every call site.
- **The argument to `alloc` must be a `new T(...)` (or `new Array(...)`)
  expression** at the call site. `nscplugin` matches that shape, and
  `alloc` is `inline` so that it survives. Anything else, like
  `sz.alloc(makeT())`, is a compile error
  (`Unexpected tree in scala.scalanative.runtime.SafeZoneAllocator.allocate`),
  not a silent fallback to the GC.
- **The page pool never shrinks.** Memory freed by closed zones is kept for
  reuse by later zones, so resident memory stays at the peak of zone memory
  in use at the same time.
- **Windows is untested.** `arena.c` has a Windows branch (`SRWLOCK`), but
  it has never been compiled; only Linux has been tested.
- **Closures are not checked.** A closure that captures a zone-bound value
  and escapes the block currently compiles cleanly (see the "KNOWN GAP" test
  in `tests/`).
- **Relies on undocumented internals**, not a supported scala-native
  contract: the magic `SafeZoneAllocator.allocate` name and the
  `private[scalanative]` trait (the arena itself is this library's own).
  Either can change in a future scala-native release without notice;
  `sbt library/test` fails if allocations stop going through the arena.

## Building and testing

```
sbt library/test   # runtime + multithreading tests on Native (RSS checks are Linux-only)
sbt tests/test     # capture-checking pos/neg tests (compiles snippets against the Native library)
sbt example/run    # builds and runs the native example binary
```

Tested with Scala 3.10.0 and the stock sbt-scala-native 0.5.12 from Maven
Central (no custom scala-native build needed).
