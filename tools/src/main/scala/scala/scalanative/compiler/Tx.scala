package scala.scalanative
package compiler

import scala.annotation.tailrec
import scala.language.implicitConversions
import util.{unreachable, unsupported}
import sourcecode.FullName

sealed abstract class Tx[T] {
  def fullName: FullName
  def isDefinedAt(t: T): Boolean
}
object Tx {
  final case class None[T]() extends Tx[T] {
    def fullName = sourcecode.FullName("None")
    def isDefinedAt(t: T): Boolean = false
  }

  final case class Replace[T](pf: PartialFunction[T, T]) (implicit val fullName: FullName) extends Tx[T] {
    def isDefinedAt(t: T): Boolean = pf.isDefinedAt(t)
  }

  final case class Expand[T](pf: PartialFunction[T, Seq[T]])(implicit val fullName: FullName) extends Tx[T] {
    def isDefinedAt(t: T): Boolean = pf.isDefinedAt(t)
  }

  final case class Fuse[T](txs: Seq[Tx[T]]) extends Tx[T] {
    def fullName = FullName("fused:" + txs.map(_.fullName.value).mkString(":"))
    def isDefinedAt(t: T): Boolean = txs.exists(_.isDefinedAt(t))
  }

  private sealed abstract class Expansion[T <: AnyRef] {
    def length: Int
    def result: Seq[T] = {
      val arr = new Array[AnyRef](length)
      var i = 0
      def loop(exp: Expansion[T]): Unit = exp match {
        case _: Todo[_] => unreachable
        case Done(t)    => arr(i) = t; i += 1
        case Root(exps) => exps.foreach(loop)
      }
      loop(this)
      arr.asInstanceOf[Array[T]]
    }
  }

  private final case class Root[T <: AnyRef](expansions: Seq[Expansion[T]]) extends Expansion[T] {
    def length = expansions.map(_.length).sum
  }

  private final case class Todo[T <: AnyRef](prev: Tx[T], value: T) extends Expansion[T] {
    def length = 1
  }

  private final case class Done[T <: AnyRef](value: T) extends Expansion[T] {
    def length = 1
  }

  def fuse[T <: AnyRef](txs: Seq[Tx[T]]): Tx[T] = {
    val flattened = txs.flatMap {
      case _: Tx.None[_] => Seq()
      case Tx.Fuse(txs)  => txs
      case tx            => Seq(tx)
    }
    flattened match {
      case Seq()   => Tx.None[T]
      case Seq(tx) => tx
      case txs     => Fuse(txs)
    }
  }

  private def invokeTx[T <: AnyRef](tx: Tx[T], t: T): Expansion[T] = tx match {
    case Tx.Replace(pf) =>
      println(s"invoking ${tx.fullName.value}")
      val pff = pf.asInstanceOf[PartialFunction[T, T]]
      val res = pff.applyOrElse(t, (_: T) => t)
      Todo(tx, res)
    case Tx.Expand(pf) =>
      println(s"invoking ${tx.fullName.value}")
      val pff = pf.asInstanceOf[PartialFunction[T, Seq[T]]]
      val res = pff.applyOrElse(t, (_: T) => Seq(t))
      Root(res.map(Todo(tx, _)))
    case _ =>
      unreachable
  }

  private def invokeTodo[T <: AnyRef](txs: Seq[Tx[T]], t: Todo[T]): Expansion[T] =
    txs.collectFirst {
      case tx if tx.isDefinedAt(t.value) && t.prev != tx =>
        tx
    }.fold[Expansion[T]](Done(t.value)) { tx =>
      println(s"found ${tx.fullName}, prev ${t.prev.fullName}")
      invokeTx(tx, t.value)
    }

  private def invokeExp[T <: AnyRef](txs: Seq[Tx[T]], exp: Expansion[T]): Expansion[T] =
    exp match {
      case done: Done[_] => done
      case todo: Todo[_] => invokeExp(txs, invokeTodo(txs, todo))
      case Root(exps)    => Root(exps.map(invokeExp(txs, _)))
    }

  def invoke1[T <: AnyRef](tx: Tx[T], t: T): T = tx match {
    case _: Tx.None[_] =>
      t
    case _ =>
      val Seq(res) = invokeExp(Seq(tx), Todo(Tx.None[T], t)).result
      res
  }

  def invoke[T <: AnyRef](tx: Tx[T], ts: Seq[T]): Seq[T] = tx match {
    case _: Tx.None[_] =>
      ts
    case tx @ (_: Tx.Replace[_] | _: Tx.Expand[_]) =>
      invokeExp(Seq(tx), Root(ts.map(Todo(Tx.None[T], _)))).result
    case Tx.Fuse(txs) =>
      invokeExp(txs, Root(ts.map(Todo(Tx.None[T], _)))).result
  }
}
