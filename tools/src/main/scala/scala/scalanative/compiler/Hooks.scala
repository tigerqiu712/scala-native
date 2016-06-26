package scala.scalanative
package compiler

import scala.language.implicitConversions

sealed abstract class Expand[T]


sealed abstract class Replace[+T] {
  def invoke(arg: T, orElse: T) = this match {
    case Hook.None         => orElse
    case Hook.PartFunc(pf) => pf.applyOrElse(arg, (_: T) => orElse)
  }
}
object Hook {
  final case object None extends Hook[Any, Nothing]
  final case class PartFunc[From, To](pf: PartialFunction[T, To])
      extends Hook[From, To]

  implicit def apply[From, To](pf: PartialFunction[From, To]): Hook[From, To] =
    PartFunc(pf)

  def fuse[A, B](hooks: Seq[Hook[A, B]]): Hook[A, B] = {
    val pfs = hooks.filter(_ != None)
    ???
  }
}
