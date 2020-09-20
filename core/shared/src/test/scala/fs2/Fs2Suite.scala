/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import cats.effect.{IO, Sync, SyncIO}
import cats.effect.unsafe.{IORuntime, Scheduler, UnsafeRun}
import cats.effect.testkit.TestContext

import cats.syntax.all._

import munit.{Location, ScalaCheckEffectSuite}
import org.typelevel.discipline.Laws

abstract class Fs2Suite extends ScalaCheckEffectSuite with TestPlatform with Generators {

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 25 else 5)
      .withWorkers(1)

  override def munitFlakyOK = true

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val unsafeRunForIO: UnsafeRun[IO] = ioRuntime.unsafeRunForIO

  override val munitExecutionContext: ExecutionContext = ExecutionContext.global

  /** Provides various ways to make test assertions on an `F[A]`. */
  implicit class Asserting[F[_], A](private val self: F[A]) {

    /**
      * Asserts that the `F[A]` fails with an exception of type `E`.
      */
    def assertThrows[E <: Throwable](implicit
        F: Sync[F],
        ct: reflect.ClassTag[E],
        loc: Location
    ): F[Unit] =
      self.attempt.flatMap {
        case Left(_: E) => F.pure(())
        case Left(t) =>
          F.delay(
            fail(
              s"Expected an exception of type ${ct.runtimeClass.getName} but got an exception: $t"
            )
          )
        case Right(a) =>
          F.delay(
            fail(s"Expected an exception of type ${ct.runtimeClass.getName} but got a result: $a")
          )
      }
  }

  implicit class Deterministically[F[_], A](private val self: IO[A]) {

    /**
      * Allows to run an IO deterministically through TextContext.
      * Assumes you want to run the IO to completion, if you need to step through execution,
      * you will have to do it manually, starting from `createDeterministicRuntime`
      */
    def ticked: Deterministic[A] = Deterministic(self)
  }

  case class Deterministic[A](fa: IO[A])

  /* Creates a new environment for deterministic tests which require stepping through */
  protected def createDeterministicRuntime: (TestContext, IORuntime) = {
    val ctx = TestContext()

    val scheduler = new Scheduler {
      def sleep(delay: FiniteDuration, action: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, action)
        new Runnable { def run() = cancel() }
      }

      def nowMillis() = ctx.now().toMillis
      def monotonicNanos() = ctx.now().toNanos
    }

    val runtime = IORuntime(ctx, ctx, scheduler, () => ())

    (ctx, runtime)
  }

  /** Returns a stream that has a 10% chance of failing with an error on each output value. */
  protected def spuriousFail[F[_]: RaiseThrowable, O](s: Stream[F, O]): Stream[F, O] =
    Stream.suspend {
      val counter = new java.util.concurrent.atomic.AtomicLong(0L)
      s.flatMap { o =>
        val i = counter.incrementAndGet
        if (i % (math.random() * 10 + 1).toInt == 0L) Stream.raiseError[F](new Err)
        else Stream.emit(o)
      }
    }

  protected def group(name: String)(thunk: => Unit): Unit = {
    val countBefore = munitTestsBuffer.size
    val _ = thunk
    val countAfter = munitTestsBuffer.size
    val countRegistered = countAfter - countBefore
    val registered = munitTestsBuffer.toList.drop(countBefore)
    (0 until countRegistered).foreach(_ => munitTestsBuffer.remove(countBefore))
    registered.foreach(t => munitTestsBuffer += t.withName(s"$name - ${t.name}"))
  }

  protected def checkAll(name: String, ruleSet: Laws#RuleSet): Unit =
    for ((id, prop) <- ruleSet.all.properties)
      property(s"${name}.${id}")(prop)

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(
      munitIOTransform,
      munitSyncIOTransform,
      munitDeterministicIOTransform
    )

  private val munitIOTransform: ValueTransform =
    new ValueTransform("IO", { case e: IO[_] => e.unsafeToFuture() })

  private val munitSyncIOTransform: ValueTransform =
    new ValueTransform(
      "SyncIO",
      { case e: SyncIO[_] => Future(e.unsafeRunSync())(munitExecutionContext) }
    )

  private val munitDeterministicIOTransform: ValueTransform =
    new ValueTransform(
      "Deterministic IO",
      { case e: Deterministic[_] =>
        val (ctx, runtime) = createDeterministicRuntime
        val r = e.fa.unsafeToFuture()(runtime)
        ctx.tickAll(3.days)
        r
      }
    )
}
