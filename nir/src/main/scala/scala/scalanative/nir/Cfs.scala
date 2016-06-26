package scala.scalanative
package nir

sealed abstract class Cf {
  def nexts: Seq[Next] = this match {
    case Cf.Unreachable | _: Cf.Ret | _: Cf.Throw => Seq()
    case Cf.Jump(n) => Seq(n)
    case Cf.If(_, n1, n2) => Seq(n1, n2)
    case Cf.Switch(_, n, ns) => n +: ns
    case Cf.Invoke(_, _, _, n1, n2) => Seq(n1, n2)
    case Cf.Try(n1, n2) => Seq(n1, n2)
  }
}
object Cf {
  // low-level control
  final case object Unreachable extends Cf
  final case class Ret(value: Val)                          extends Cf
  final case class Jump(next: Next)                         extends Cf
  final case class If(value: Val, thenp: Next, elsep: Next) extends Cf
  final case class Switch(value: Val, default: Next, cases: Seq[Next])
      extends Cf
  final case class Invoke(ty: Type,
                          ptr: Val,
                          args: Seq[Val],
                          succ: Next,
                          fail: Next)
      extends Cf
  final case class Resume(excrec: Val) extends Cf

  // high-level control
  final case class Throw(value: Val)           extends Cf
  final case class Try(succ: Next, fail: Next) extends Cf
}
