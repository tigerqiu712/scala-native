package scala.scalanative
package compiler
package pass

import util.{unreachable, ScopedVar}, ScopedVar.scoped
import nir._
import Tx.{Expand, Replace}

/** Eliminates returns of Unit values and replaces them with void. */
class UnitLowering(implicit fresh: Fresh) extends Pass {
  import UnitLowering._

  override def preInject = Seq(unitDefn)

  override def preInst = Expand[Inst] {
    case inst @ Inst(n, op)
        if op.resty == Type.Unit
        && !op.isInstanceOf[Op.Copy]=>
      Seq(
          Inst(op),
          Inst(n, Op.Copy(Val.Unit))
      )
  }

  override def preDefn = Replace[Defn] {
    case defn @ Defn.Define(_, _, Type.Function(args, Type.Unit), blocks) =>
      defn.copy(
        ty     = Type.Function(args, Type.Void),
        blocks = blocks.map { block =>
          block.copy(cf = block.cf match {
            case Cf.Ret(_) => Cf.Ret(Val.None)
            case cf        => cf
          })
        }
      )
  }

  override def preType = Replace[Type] {
    case Type.Unit =>
      Type.Ptr

    case Type.Function(params, Type.Unit) =>
      Type.Function(params, Type.Void)
  }
}

object UnitLowering extends PassCompanion {
  def apply(ctx: Ctx) = new UnitLowering()(ctx.fresh)

  val unitName = Global.Top("scala.scalanative.runtime.BoxedUnit$")
  val unit     = Val.Global(unitName, Type.Ptr)
  val unitTy   = Type.Struct(unitName tag "module" tag "class", Seq(Type.Ptr))
  val unitConst =
    Val.Global(unitName tag "module" tag "class" tag "type", Type.Ptr)
  val unitValue = Val.Struct(unitTy.name, Seq(unitConst))
  val unitDefn  = Defn.Const(Attrs.None, unitName, unitTy, unitValue)

  override val depends = Seq(unitName)
}
