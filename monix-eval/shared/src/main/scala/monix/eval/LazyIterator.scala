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

package monix.eval

import monix.eval.LazyIterator._
import scala.collection.{LinearSeq, immutable, mutable}
import scala.util.control.NonFatal

/** Interface producing [[LazyIterator async iterators]]. */
trait LazyIterable[+A] extends Serializable {
  /** Creates a [[Coeval]] that upon evaluation will start
    * an [[LazyIterator]].
    *
    * The returned task has to take care of resource management,
    * so upon cancellation, any background process has to be canceled.
    *
    * @param batchSize represents the recommended maximum batch size
    *        in case the data-source is able to produce elements in
    *        batches.
    */
  def lazyIterator(batchSize: Int): Coeval[LazyIterator[A]]
}

object LazyIterable {
  /** Converts any `scala.collection.Iterable` into
    * an async iterable.
    */
  def fromIterable[A](iterable: scala.collection.Iterable[A]): LazyIterable[A] =
    new LazyIterable[A] {
      def lazyIterator(batchSize: Int): Coeval[LazyIterator[A]] =
        LazyIterator.fromIterable(iterable, batchSize)
    }

  /** Converts any `java.lang.Iterable` into
    * an async iterable.
    */
  def fromIterable[A](iterable: java.lang.Iterable[A]): LazyIterable[A] =
    new LazyIterable[A] {
      def lazyIterator(batchSize: Int): Coeval[LazyIterator[A]] =
        LazyIterator.fromIterable(iterable, batchSize)
    }

  /** Converts a `List` into an `LazyIterable`. */
  def fromList[A](list: List[A]): LazyIterable[A] =
    new LazyIterable[A] {
      def lazyIterator(batchSize: Int): Coeval[LazyIterator[A]] =
        LazyIterator.fromList(list, batchSize)
    }
}

/** An `LazyIterator` represents a [[Coeval]] based asynchronous
  * iterator, generated by [[LazyIterable]].
  */
sealed trait LazyIterator[+A] extends Product with Serializable {
  /** Filters the `LazyIterator` by the given predicate function,
    * returning only those elements that match.
    */
  def filter(p: A => Boolean): LazyIterator[A] =
    this match {
      case ref @ Next(head, tail) =>
        try { if (p(head)) ref else Wait(tail.map(_.filter(p))) }
        catch { case NonFatal(ex) => Error(ex) }
      case NextSeq(head, tail) =>
        val rest = tail.map(_.filter(p))
        try head.filter(p) match {
          case Nil => Wait(rest)
          case filtered => NextSeq(filtered, rest)
        } catch {
          case NonFatal(ex) => Error(ex)
        }
      case Wait(rest) => Wait(rest.map(_.filter(p)))
      case Empty => Empty
      case Error(ex) => Error(ex)
    }

  /** Returns a new iterable by mapping the supplied function
    * over the elements of the source.
    */
  final def map[B](f: A => B): LazyIterator[B] = {
    this match {
      case Next(head, tail: Coeval[LazyIterator[A]]) =>
        try { Next(f(head), tail.map(_.map(f))) }
        catch { case NonFatal(ex) => Error(ex) }
      case NextSeq(head, rest) =>
        try { NextSeq(head.map(f), rest.map(_.map(f))) }
        catch { case NonFatal(ex) => Error(ex) }

      case Wait(rest) => Wait(rest.map(_.map(f)))
      case Empty => Empty
      case Error(ex) => Error(ex)
    }
  }

  /** Applies the function to the elements of the source
    * and concatenates the results.
    */
  final def flatMap[B](f: A => LazyIterator[B]): LazyIterator[B] = {
    this match {
      case Next(head, tail) =>
        try { f(head) concatCoeval tail.map(_.flatMap(f)) }
        catch { case NonFatal(ex) => Error(ex) }

      case NextSeq(list, rest) =>
        try {
          if (list.isEmpty)
            Wait(rest.map(_.flatMap(f)))
          else
            f(list.head) concatCoeval Coeval.evalAlways(NextSeq(list.tail, rest).flatMap(f))
        } catch {
          case NonFatal(ex) => Error(ex)
        }

      case Wait(rest) => Wait(rest.map(_.flatMap(f)))
      case Empty => Empty
      case Error(ex) => Error(ex)
    }
  }
  /** If the source is an async iterable generator, then
    * concatenates the generated async iterables.
    */
  final def flatten[B](implicit ev: A <:< LazyIterator[B]): LazyIterator[B] =
    flatMap(x => x)

  /** Alias for [[flatMap]]. */
  final def concatMap[B](f: A => LazyIterator[B]): LazyIterator[B] =
    flatMap(f)

  /** Alias for [[concat]]. */
  final def concat[B](implicit ev: A <:< LazyIterator[B]): LazyIterator[B] =
    flatten

  /** Appends the given iterable to the end of the source,
    * effectively concatenating them.
    */
  final def ++[B >: A](rhs: LazyIterator[B]): LazyIterator[B] =
    this match {
      case Wait(task) =>
        Wait(task.map(_ ++ rhs))
      case Next(a, lt) =>
        Next(a, lt.map(_ ++ rhs))
      case NextSeq(head, lt) =>
        NextSeq(head, lt.map(_ ++ rhs))
      case Empty => rhs
      case Error(ex) => Error(ex)
    }

  private final def concatCoeval[B >: A](rhs: Coeval[LazyIterator[B]]): LazyIterator[B] = {
    this match {
      case Wait(task) =>
        Wait(task.map(_ concatCoeval rhs))
      case Next(a, lt) =>
        Next(a, lt.map(_ concatCoeval rhs))
      case NextSeq(head, lt) =>
        NextSeq(head, lt.map(_ concatCoeval rhs))
      case Empty => Wait(rhs)
      case Error(ex) => Error(ex)
    }
  }

  /** Left associative fold using the function 'f'.
    *
    * On execution the iterable will be traversed from left to right,
    * and the given function will be called with the prior result,
    * accumulating state until the end, when the summary is returned.
    */
  def foldLeftL[S](seed: S)(f: (S,A) => S): Coeval[S] =
    this match {
      case Empty => Coeval.now(seed)
      case Error(ex) => Coeval.error(ex)
      case Wait(next) =>
        next.flatMap(_.foldLeftL(seed)(f))
      case Next(a, next) =>
        try {
          val state = f(seed, a)
          next.flatMap(_.foldLeftL(state)(f))
        } catch {
          case NonFatal(ex) => Coeval.error(ex)
        }
      case NextSeq(list, next) =>
        try {
          val state = list.foldLeft(seed)(f)
          next.flatMap(_.foldLeftL(state)(f))
        } catch {
          case NonFatal(ex) => Coeval.error(ex)
        }
    }

  /** Left associative fold with the ability to short-circuit the process.
    *
    * This fold works for as long as the provided function keeps returning `true`
    * as the first member of its result and the streaming isn't completed.
    * If the provided fold function returns a `false` then the folding will
    * stop and the generated result will be the second member
    * of the resulting tuple.
    *
    * @param f is the folding function, returning `(true, state)` if the fold has
    *          to be continued, or `(false, state)` if the fold has to be stopped
    *          and the rest of the values to be ignored.
    */
  def foldWhileL[S](seed: S)(f: (S, A) => (Boolean, S)): Coeval[S] =
    this match {
      case Empty => Coeval.now(seed)
      case Error(ex) => Coeval.error(ex)
      case Wait(next) =>
        next.flatMap(_.foldWhileL(seed)(f))
      case Next(a, next) =>
        try {
          val (continue, state) = f(seed, a)
          if (!continue) Coeval.now(state) else
            next.flatMap(_.foldWhileL(state)(f))
        } catch {
          case NonFatal(ex) => Coeval.error(ex)
        }
      case NextSeq(list, next) =>
        try {
          var continue = true
          var state = seed
          val iter = list.iterator

          while (continue && iter.hasNext) {
            val (c,s) = f(state, iter.next())
            state = s
            continue = c
          }

          if (!continue) Coeval.now(state) else
            next.flatMap(_.foldWhileL(state)(f))
        } catch {
          case NonFatal(ex) => Coeval.error(ex)
        }
    }

  /** Right associative lazy fold on `LazyIterator` using the
    * folding function 'f'.
    *
    * This method evaluates `lb` lazily (in some cases it will not be
    * needed), and returns a lazy value. We are using `(A, Eval[B]) =>
    * Eval[B]` to support laziness in a stack-safe way. Chained
    * computation should be performed via .map and .flatMap.
    *
    * For more detailed information about how this method works see the
    * documentation for `Eval[_]`.
    */
  def foldRightL[B](lb: Coeval[B])(f: (A, Coeval[B]) => Coeval[B]): Coeval[B] =
    this match {
      case Empty => lb
      case Error(ex) => Coeval.error(ex)
      case Wait(next) =>
        next.flatMap(_.foldRightL(lb)(f))
      case Next(a, next) =>
        f(a, next.flatMap(_.foldRightL(lb)(f)))

      case NextSeq(list, next) =>
        if (list.isEmpty) next.flatMap(_.foldRightL(lb)(f))
        else {
          val a = list.head
          val tail = list.tail
          val rest = Coeval.now(NextSeq(tail, next))
          f(a, rest.flatMap(_.foldRightL(lb)(f)))
        }
    }

  /** Find the first element matching the predicate, if one exists. */
  def findL(p: A => Boolean): Coeval[Option[A]] =
    foldWhileL(Option.empty[A])((s,a) => if (p(a)) (false, Some(a)) else (true, s))

  /** Count the total number of elements. */
  def countL: Coeval[Long] =
    foldLeftL(0L)((acc,_) => acc + 1)

  /** Given a sequence of numbers, calculates a sum. */
  def sumL[B >: A](implicit B: Numeric[B]): Coeval[B] =
    foldLeftL(B.zero)(B.plus)

  /** Check whether at least one element satisfies the predicate. */
  def existsL(p: A => Boolean): Coeval[Boolean] =
    foldWhileL(false)((s,a) => if (p(a)) (false, true) else (true, s))

  /** Check whether all elements satisfy the predicate. */
  def forallL(p: A => Boolean): Coeval[Boolean] =
    foldWhileL(true)((s,a) => if (!p(a)) (false, false) else (true, s))

  /** Aggregates elements in a `List` and preserves order. */
  def toListL: Coeval[List[A]] = {
    foldLeftL(mutable.ListBuffer.empty[A]) { (acc, a) => acc += a }
      .map(_.toList)
  }

  /** Returns true if there are no elements, false otherwise. */
  def isEmptyL: Coeval[Boolean] =
    foldWhileL(true)((_,_) => (false, false))

  /** Returns true if there are elements, false otherwise. */
  def nonEmptyL: Coeval[Boolean] =
    foldWhileL(false)((_,_) => (false, true))

  /** Returns the first element in the iterable, as an option. */
  def headL: Coeval[Option[A]] =
    this match {
      case Wait(next) => next.flatMap(_.headL)
      case Empty => Coeval.now(None)
      case Error(ex) => Coeval.error(ex)
      case Next(a, _) => Coeval.now(Some(a))
      case NextSeq(list, _) => Coeval.now(list.headOption)
    }

  /** Alias for [[headL]]. */
  def firstL: Coeval[Option[A]] = headL

  /** Returns a new sequence that will take a maximum of
    * `n` elements from the start of the source sequence.
    */
  def take(n: Int): LazyIterator[A] =
    if (n <= 0) Empty else this match {
      case Wait(next) => Wait(next.map(_.take(n)))
      case Empty => Empty
      case Error(ex) => Error(ex)
      case Next(a, next) =>
        if (n - 1 > 0)
          Next(a, next.map(_.take(n-1)))
        else
          Next(a, EmptyCoeval)

      case NextSeq(list, rest) =>
        val length = list.length
        if (length == n)
          NextSeq(list, EmptyCoeval)
        else if (length < n)
          NextSeq(list, rest.map(_.take(n-length)))
        else
          NextSeq(list.take(n), EmptyCoeval)
    }

  /** Returns a new sequence that will take elements from
    * the start of the source sequence, for as long as the given
    * function `f` returns `true` and then stop.
    */
  def takeWhile(p: A => Boolean): LazyIterator[A] =
    this match {
      case Wait(next) => Wait(next.map(_.takeWhile(p)))
      case Empty => Empty
      case Error(ex) => Error(ex)
      case Next(a, next) =>
        try { if (p(a)) Next(a, next.map(_.takeWhile(p))) else Empty }
        catch { case NonFatal(ex) => Error(ex) }
      case NextSeq(list, rest) =>
        try {
          val filtered = list.takeWhile(p)
          if (filtered.length < list.length)
            NextSeq(filtered, EmptyCoeval)
          else
            NextSeq(filtered, rest.map(_.takeWhile(p)))
        } catch {
          case NonFatal(ex) => Error(ex)
        }
    }

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  def onErrorHandleWith[B >: A](f: Throwable => LazyIterator[B]): LazyIterator[B] =
    this match {
      case Empty => Empty
      case Wait(next) => Wait(next.map(_.onErrorHandleWith(f)))
      case Next(a, next) => Next(a, next.map(_.onErrorHandleWith(f)))
      case NextSeq(seq, next) => NextSeq(seq, next.map(_.onErrorHandleWith(f)))
      case Error(ex) => try f(ex) catch { case NonFatal(err) => Error(err) }
    }

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, LazyIterator[B]]): LazyIterator[B] =
    onErrorHandleWith {
      case ex if pf.isDefinedAt(ex) => pf(ex)
      case other => LazyIterator.error(other)
    }

  /** Recovers from potential errors by mapping them to
    * a final element using the provided function.
    */
  def onErrorHandle[B >: A](f: Throwable => B): LazyIterator[B] =
    onErrorHandleWith(ex => LazyIterator.now(f(ex)))

  /** Recovers from potential errors by mapping them to
    * a final element using the provided function.
    */
  def onErrorRecover[B >: A](pf: PartialFunction[Throwable, B]): LazyIterator[B] =
    onErrorHandleWith {
      case ex if pf.isDefinedAt(ex) => LazyIterator.now(pf(ex))
      case other => LazyIterator.error(other)
    }

  /** Drops the first `n` elements, from left to right. */
  def drop(n: Int): LazyIterator[A] =
    if (n <= 0) this else this match {
      case Wait(next) => Wait(next.map(_.drop(n)))
      case Empty => Empty
      case Error(ex) => Error(ex)
      case Next(a, next) => Wait(next.map(_.drop(n-1)))
      case NextSeq(list, rest) =>
        val length = list.length
        if (length == n)
          Wait(rest)
        else if (length > n)
          NextSeq(list.drop(n), rest)
        else
          Wait(rest.map(_.drop(n - length)))
    }

  /** Triggers memoization of the iterable on the first traversal,
    * such that results will get reused on subsequent traversals.
    */
  def memoize: LazyIterator[A] =
    this match {
      case Wait(next) => Wait(next.memoize.map(_.memoize))
      case ref @ (Empty | Error(_)) => ref
      case Next(a, rest) => Next(a, rest.memoize.map(_.memoize))
      case NextSeq(list, rest) => NextSeq(list, rest.memoize.map(_.memoize))
    }

  /** Creates a new [[monix.eval.Task Task]] that will consume the
    * source iterator and upon completion of the source it will
    * complete with `Unit`.
    */
  def completedL: Coeval[Unit] = {
    def loop(coeval: Coeval[LazyIterator[A]]): Coeval[Unit] =
      coeval.flatMap {
        case Next(elem, rest) => loop(rest)
        case NextSeq(elems, rest) => loop(rest)
        case Wait(rest) => loop(rest)
        case Empty => Coeval.unit
        case Error(ex) => Coeval.error(ex)
      }

    loop(Coeval.now(this))
  }

  /** Materializes the stream and for each element applies
    * the given function.
    */
  def foreach(f: A => Unit): Unit = {
    def loop(task: Coeval[LazyIterator[A]]): Coeval[Unit] = task.flatMap {
      case Next(elem, rest) =>
        try { f(elem); loop(rest) }
        catch { case NonFatal(ex) => Coeval.error(ex) }

      case NextSeq(elems, rest) =>
        try { elems.foreach(f); loop(rest) }
        catch { case NonFatal(ex) => Coeval.error(ex) }

      case Wait(rest) => loop(Coeval.defer(rest))
      case Empty => Coeval.unit
      case Error(ex) =>
        Coeval.error(ex)
    }

    loop(Coeval.now(this)).value
  }
}

object LazyIterator {
  /** Lifts a strict value into an `LazyIterator` */
  def now[A](a: A): LazyIterator[A] = Next(a, EmptyCoeval)

  /** Builder for an [[Error]] state. */
  def error[A](ex: Throwable): LazyIterator[A] = Error(ex)

  /** Builder for an [[Empty]] state. */
  def empty[A]: LazyIterator[A] = Empty

  /** Builder for a [[Wait]] iterator state. */
  def wait[A](rest: Coeval[LazyIterator[A]]): LazyIterator[A] = Wait(rest)

  /** Builds a [[Next]] iterator state. */
  def next[A](head: A, rest: Coeval[LazyIterator[A]]): LazyIterator[A] =
    Next(head, rest)

  /** Builds a [[Next]] iterator state. */
  def nextSeq[A](headSeq: LinearSeq[A], rest: Coeval[LazyIterator[A]]): LazyIterator[A] =
    NextSeq(headSeq, rest)

  /** Lifts a strict value into an `LazyIterator` */
  def evalAlways[A](a: => A): LazyIterator[A] =
    Wait(Coeval.evalAlways(try Next(a, EmptyCoeval) catch { case NonFatal(ex) => Error(ex) }))

  /** Lifts a strict value into an `LazyIterator` and
    * memoizes the result for subsequent executions.
    */
  def evalOnce[A](a: => A): LazyIterator[A] =
    Wait(Coeval.evalOnce(try Next(a, EmptyCoeval) catch { case NonFatal(ex) => Error(ex) }))

  /** Promote a non-strict value representing a LazyIterator
    * to an LazyIterator of the same type.
    */
  def defer[A](fa: => LazyIterator[A]): Wait[A] =
    Wait(Coeval.defer(Coeval.evalAlways(fa)))

  /** Generages a range between `from` (inclusive) and `until` (exclusive),
    * with `step` as the increment.
    */
  def range(from: Long, until: Long, step: Long = 1L): LazyIterator[Long] = {
    def loop(cursor: Long): LazyIterator[Long] = {
      val isInRange = (step > 0 && cursor < until) || (step < 0 && cursor > until)
      val nextCursor = cursor + step
      if (!isInRange) Empty else Next(cursor, Coeval.evalAlways(loop(nextCursor)))
    }

    Wait(Coeval.evalAlways(loop(from)))
  }

  /** Converts any sequence into an async iterable.
    *
    * Because the list is a linear sequence that's known
    * (but not necessarily strict), we'll just return
    * a strict state.
    */
  def fromList[A](list: immutable.LinearSeq[A], batchSize: Int): Coeval[LazyIterator[A]] =
    if (list.isEmpty) Coeval.now(Empty) else Coeval.now {
      val (first, rest) = list.splitAt(batchSize)
      NextSeq(first, Coeval.defer(fromList(rest, batchSize)))
    }

  /** Converts an iterable into an async iterator. */
  def fromIterable[A](iterable: Iterable[A], batchSize: Int): Coeval[LazyIterator[A]] =
    Coeval.now(iterable).flatMap { iter => fromIterator(iter.iterator, batchSize) }

  /** Converts an iterable into an async iterator. */
  def fromIterable[A](iterable: java.lang.Iterable[A], batchSize: Int): Coeval[LazyIterator[A]] =
    Coeval.now(iterable).flatMap { iter => fromIterator(iter.iterator, batchSize) }

  /** Converts a `scala.collection.Iterator` into an async iterator. */
  def fromIterator[A](iterator: scala.collection.Iterator[A], batchSize: Int): Coeval[LazyIterator[A]] =
    Coeval.evalOnce {
      try {
        val buffer = mutable.ListBuffer.empty[A]
        var processed = 0
        while (processed < batchSize && iterator.hasNext) {
          buffer += iterator.next()
          processed += 1
        }

        if (processed == 0) Empty
        else if (processed == 1)
          Next(buffer.head, fromIterator(iterator, batchSize))
        else
          NextSeq(buffer.toList, fromIterator(iterator, batchSize))
      } catch {
        case NonFatal(ex) =>
          Error(ex)
      }
    }

  /** Converts a `java.util.Iterator` into an async iterator. */
  def fromIterator[A](iterator: java.util.Iterator[A], batchSize: Int): Coeval[LazyIterator[A]] =
    Coeval.evalOnce {
      try {
        val buffer = mutable.ListBuffer.empty[A]
        var processed = 0
        while (processed < batchSize && iterator.hasNext) {
          buffer += iterator.next()
          processed += 1
        }

        if (processed == 0) Empty
        else if (processed == 1)
          Next(buffer.head, fromIterator(iterator, batchSize))
        else
          NextSeq(buffer.toList, fromIterator(iterator, batchSize))
      } catch {
        case NonFatal(ex) =>
          Error(ex)
      }
    }

  /** A state of the [[LazyIterator]] representing a deferred iterator. */
  final case class Wait[+A](next: Coeval[LazyIterator[A]]) extends LazyIterator[A]

  /** A state of the [[LazyIterator]] representing a head/tail decomposition.
    *
    * @param head is the next element to be processed
    * @param rest is the next state in the sequence
    */
  final case class Next[+A](head: A, rest: Coeval[LazyIterator[A]]) extends LazyIterator[A]

  /** A state of the [[LazyIterator]] representing a head/tail decomposition.
    *
    * Like [[Next]] except that the head is a strict sequence
    * of elements that don't need asynchronous execution.
    * Meant for doing buffering.
    *
    * @param headSeq is a sequence of the next elements to be processed, can be empty
    * @param rest is the next state in the sequence
    */
  final case class NextSeq[+A](headSeq: LinearSeq[A], rest: Coeval[LazyIterator[A]]) extends LazyIterator[A]

  /** Represents an error state in the iterator.
    *
    * This is a final state. When this state is received, the data-source
    * should have been canceled already.
    *
    * @param ex is an error that was thrown.
    */
  final case class Error(ex: Throwable) extends LazyIterator[Nothing]

  /** Represents an empty iterator.
    *
    * Received as a final state in the iteration process.
    * When this state is received, the data-source should have
    * been canceled already.
    */
  case object Empty extends LazyIterator[Nothing]

  // Reusable instances
  private[eval] final val EmptyCoeval = Coeval.now(Empty)
}