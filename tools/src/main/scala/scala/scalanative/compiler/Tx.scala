package scala.scalanative
package compiler

import scala.language.implicitConversions
import util.unsupported

sealed abstract class Tx[+T] {
  def apply[T](t: T): T
  def apply[T](ts: Seq[T]): Seq[T]
}
object Tx {
  final case object None extends Tx[Nothing] {
    def apply[T](t: T): T            = t
    def apply[T](ts: Seq[T]): Seq[T] = ts
  }

  final case class Visit[T](pf: PartialFunction[T, Unit]) extends Tx[T] {
    def apply[T](t: T): T            = { pf.applyOrElse(t, (_: T) => ()); t }
    def apply[T](ts: Seq[T]): Seq[T] = ts.foreach(apply)
  }

  final case class Replace[T](pf: PartialFunction[T, T]) extends Tx[T] {
    def apply[T](t: T): T            = pf.applyOrElse(t, (_: T) => t)
    def apply[T](ts: Seq[T]): Seq[T] = ts.map(apply)
  }

  final case class Expand[T](pf: PartialFunction[T, Seq[T]]) extends Tx[T] {
    def apply[T](t: T): T            = unsupported("single-element expand")
    def apply[T](ts: Seq[T]): Seq[T] = ts.flatMap { t => pf.applyOrElse(t, (_: T) => Seq(t)) }
  }

  final case class Fused[T](txs: Seq[Tx[T]]) extends Tx[T] {
    def apply[T](t: T): T            = ???
    def apply[T](ts: Seq[T]): Seq[T] = ???
  }

  def fuse[T](txs: Seq[Tx[T]]): Tx[T] =
    txs.filterNot(_ == None) match {
      case Seq()   => Tx.None
      case Seq(tx) => tx
      case txs     => Tx.Fused(txs)
    }
}
