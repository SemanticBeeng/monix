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

package monix.streams.internal.operators2

import monix.execution.Cancelable
import monix.execution.cancelables.AssignableCancelable
import monix.execution.cancelables.MultiAssignmentCancelable.State
import monix.execution.cancelables.MultiAssignmentCancelable.State._

private[monix]
final class OnErrorCancelable extends AssignableCancelable {
  private def underlying: Cancelable = state match {
    case Cancelled => Cancelable.empty
    case Active(c) => c
  }

  private[this] var state: State =
    Active(Cancelable.empty)

  override def isCanceled: Boolean =
    state eq Cancelled

  override def cancel(): Unit = {
    val oldState = state
    state = Cancelled
    if (oldState ne Cancelled)
      oldState.asInstanceOf[Active].s.cancel()
  }

  override def `:=`(value: Cancelable): this.type = {
    val ref = value match {
      case c: OnErrorCancelable => c.underlying
      case _ => value
    }

    if (state eq Cancelled)
      value.cancel()
    else
      state = Active(ref)

    this
  }
}