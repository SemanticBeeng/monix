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

import monix.execution.Ack
import monix.execution.cancelables.{BooleanCancelable, RefCountCancelable}
import monix.execution.Ack.{Cancel, Continue}
import monix.streams.{OverflowStrategy, Observer, Observable}
import monix.streams.exceptions.CompositeException
import monix.streams.internal._
import monix.streams.observers.{SyncObserver, BufferedSubscriber}
import scala.collection.mutable
import scala.concurrent.Promise

private[monix] object flatten {
  /**
    * Implementation for [[Observable.merge]].
    */
  def merge[T,U](source: Observable[T])
    (overflowStrategy: OverflowStrategy, onOverflow: Long => U, delayErrors: Boolean)
    (implicit ev: T <:< Observable[U]): Observable[U] = {

    Observable.unsafeCreate { subscriber =>
      import subscriber.{scheduler => s}
      val observerU = BufferedSubscriber(subscriber, overflowStrategy, onOverflow)

      source.unsafeSubscribeFn(new SyncObserver[T] {
        private[this] val streamActivity =
          BooleanCancelable()
        private[this] val errors = if (delayErrors)
          mutable.ArrayBuffer.empty[Throwable] else null

        private[this] val refCount = RefCountCancelable {
          if (delayErrors)
            errors.synchronized {
              if (errors.nonEmpty)
                observerU.onError(CompositeException(errors))
              else
                observerU.onComplete()
            }
          else
            observerU.onComplete()
        }

        def onNext(childObservable: T) = {
          val refID = refCount.acquire()

          childObservable.unsafeSubscribeFn(new Observer[U] {
            def onNext(elem: U) = {
              if (streamActivity.isCanceled)
                Cancel
              else
                observerU.onNext(elem)
                  .ifCanceledDoCancel(streamActivity)
            }

            def onError(ex: Throwable): Unit = {
              if (delayErrors) errors.synchronized {
                errors += ex
                refID.cancel()
              }
              else {
                streamActivity.cancel()
                observerU.onError(ex)
              }
            }

            def onComplete(): Unit = {
              // NOTE: we aren't sending this onComplete signal downstream to our observerU
              // we will eventually do that after all of them are complete
              refID.cancel()
            }
          })

          if (streamActivity.isCanceled)
            Cancel
          else
            Continue
        }

        def onError(ex: Throwable) = {
          if (delayErrors) errors.synchronized {
            errors += ex
            refCount.cancel()
          }
          else {
            // oops, error happened on main thread,
            // piping that along should cancel everything
            observerU.onError(ex)
          }
        }

        def onComplete() = {
          refCount.cancel()
        }
      })
    }
  }
}