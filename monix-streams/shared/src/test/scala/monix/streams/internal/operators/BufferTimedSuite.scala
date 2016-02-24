/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.streams.internal.operators

import monix.execution.Ack.Continue
import monix.streams.internal.operators.BufferCountedSuite._
import monix.streams.internal.operators2.BaseOperatorSuite
import monix.streams.{Observable, Observer}
import monix.streams.exceptions.DummyException
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

object BufferTimedSuite extends BaseOperatorSuite {
  val waitFirst = 1.second
  val waitNext = 1.second

  def sum(count: Int) = {
    val total = (count * 10 - 1).toLong
    total * (total + 1) / 2
  }

  def count(sourceCount: Int) = {
    sourceCount
  }

  def createObservable(sourceCount: Int) = {
    require(sourceCount > 0, "sourceCount must be strictly positive")
    Some {
      val o = Observable.intervalAtFixedRate(100.millis)
        .take(sourceCount * 10)
        .bufferTimed(1.second)
        .map(_.sum)

      Sample(o, count(sourceCount), sum(sourceCount), waitFirst, waitNext)
    }
  }

  def observableInError(sourceCount: Int, ex: Throwable) =
    Some {
      val o = createObservableEndingInError(Observable
        .intervalAtFixedRate(100.millis, 100.millis).take(sourceCount), ex)
        .bufferTimed(1.second)
        .map(_.sum)

      Sample(o, count(sourceCount/10), sum(sourceCount/10), waitFirst, waitNext)
    }

  def brokenUserCodeObservable(sourceCount: Int, ex: Throwable) =
    None

  test("should emit buffer onComplete") { implicit s =>
    val count = 157

    val obs = Observable.intervalAtFixedRate(100.millis)
      .take(count * 10)
      .bufferTimed(2.seconds)
      .map(_.sum)

    var wasCompleted = false
    var received = 0
    var total = 0L

    obs.unsafeSubscribeFn(new Observer[Long] {
      def onNext(elem: Long) = {
        received += 1
        total += elem
        Continue
      }

      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = wasCompleted = true
    })

    s.tick(waitNext + waitFirst * (count - 1))
    assertEquals(received, count / 2 + 1)
    assertEquals(total, sum(count))
    s.tick(waitFirst)
    assert(wasCompleted)
  }

  test("should throw on negative timespan") { implicit s =>
    intercept[IllegalArgumentException] {
      Observable.intervalAtFixedRate(100.millis)
        .bufferTimed(Duration.Zero - 1.second)
    }
  }

  test("should not do back-pressure for onComplete, for 1 element") { implicit s =>
    val p = Promise[Continue]()
    var wasCompleted = false

    createObservable(1) match {
      case ref @ Some(Sample(obs, count, sum, waitForFirst, waitForNext)) =>
        var onNextReceived = false

        obs.unsafeSubscribeFn(new Observer[Long] {
          def onNext(elem: Long): Future[Continue] = { onNextReceived = true; p.future }
          def onError(ex: Throwable): Unit = throw new IllegalStateException()
          def onComplete(): Unit = wasCompleted = true
        })

        s.tick(waitForFirst)
        assert(wasCompleted)
        assert(onNextReceived)
        p.success(Continue)
        s.tick(waitForNext)
    }
  }
}