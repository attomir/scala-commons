package com.avsystem.commons
package rpc.akka

import monifu.concurrent.Implicits.globalScheduler
import monifu.reactive.{Ack, Observable}
import org.mockito.Mockito._
import org.scalatest.time.Span

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

/**
  * @author Wojciech Milewski
  */
trait ObservableRPCTest {this: RPCFrameworkTest =>

  import ObservableRPCTest._

  it should "successfully call observable method" in fixture { f =>
    when(f.mockRpc.stream).thenReturn(Observable.from(1, 2, 3, 4, 5))

    val result = f.rpc.stream.asFutureSeq

    whenReady(result) { value =>
      value should contain inOrderOnly(1, 2, 3, 4, 5)
    }
  }

  it should "wrap remote exception with RemoteCallException in observable" in fixture { f =>
    when(f.mockRpc.stream).thenReturn(Observable.error(new IllegalStateException))

    whenFailed(f.rpc.stream.asExistingFuture) { thrown =>
      thrown shouldBe a[RemoteCallException]
      thrown.getMessage should include("IllegalStateException")
    }
  }

  it should "return RemoteTimeoutException when no connection to the remote RPC when observable method called" in noConnectionFixture { f =>
    val span = Span.convertDurationToSpan(callTimeout.plus(1.second))
    f.rpc.stream.error.asExistingFuture.futureValue(timeout(span)) shouldBe a[RemoteTimeoutExceptionType]
  }

  it should "call remote method for each subscriber" in fixture { f =>
    when(f.mockRpc.stream).thenReturn(Observable.from(1, 2, 3, 4, 5))

    val firstCompleted = Promise[Unit]()
    val secondCompleted = Promise[Unit]()
    val thirdCompleted = Promise[Unit]()

    val observable = f.rpc.stream
    observable.subscribe(_ => Ack.Continue, _ => (), () => firstCompleted.trySuccess(Unit))
    observable.subscribe(_ => Ack.Continue, _ => (), () => secondCompleted.trySuccess(Unit))
    observable.subscribe(_ => Ack.Continue, _ => (), () => thirdCompleted.trySuccess(Unit))

    val allCompleted: Future[List[Unit]] = Future.sequence(List(firstCompleted.future, secondCompleted.future, thirdCompleted.future))

    whenReady(allCompleted) { _ =>
      verify(f.mockRpc, times(3)).stream
    }
  }

  it should "call remote observable method as many times as called on the client side" in fixture { f =>
    when(f.mockRpc.stream).thenReturn(Observable.from(1, 2, 3, 4, 5))

    val firstCompleted = Promise[Unit]()
    val secondCompleted = Promise[Unit]()
    val thirdCompleted = Promise[Unit]()

    f.rpc.stream.subscribe(_ => Ack.Continue, _ => (), () => firstCompleted.trySuccess(Unit))
    f.rpc.stream.subscribe(_ => Ack.Continue, _ => (), () => secondCompleted.trySuccess(Unit))
    f.rpc.stream.subscribe(_ => Ack.Continue, _ => (), () => thirdCompleted.trySuccess(Unit))

    val allCompleted: Future[List[Unit]] = Future.sequence(List(firstCompleted.future, secondCompleted.future, thirdCompleted.future))

    whenReady(allCompleted) { _ =>
      verify(f.mockRpc, times(3)).stream
    }
  }
}

object ObservableRPCTest {
  type RemoteTimeoutExceptionType = RemoteTimeoutException.type

  private implicit class ObservableOps[T](private val observable: Observable[T]) extends AnyVal {
    def asFutureSeq: Future[Seq[T]] = {
      observable.foldLeft(List.empty[T]) {
        case (list, elem) => elem :: list
      }.map(_.reverse).asFuture.map(_.get)
    }

    def asExistingFuture: Future[T] = {
      observable.asFuture.map(_.get)
    }

  }
}