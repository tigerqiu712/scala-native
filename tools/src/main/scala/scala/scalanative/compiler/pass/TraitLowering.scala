package scala.scalanative
package compiler
package pass

import scala.collection.mutable
import compiler.analysis.ClassHierarchy._
import compiler.analysis.ClassHierarchyExtractors._
import nir._

/** Lowers traits and operations on them. */
class TraitLowering(implicit top: Top, fresh: Fresh) extends Pass {
  import TraitLowering._

  override def preDefn = {
    case _: Defn.Trait =>
      Seq()

    case Defn.Declare(_, MethodRef(_: Trait, _), _) =>
      Seq()
  }

  override def preInst = {
    case Inst(n, Op.Method(sig, obj, MethodRef(trt: Trait, meth))) =>
      val typeptr    = Val.Local(fresh(), Type.Ptr)
      val idptr      = Val.Local(fresh(), Type.Ptr)
      val id         = Val.Local(fresh(), Type.I32)
      val methptrptr = Val.Local(fresh(), Type.Ptr)

      Seq(
          Inst(typeptr.name, Op.Load(Type.Ptr, obj)),
          Inst(idptr.name,
               Op.Elem(Rt.Type, typeptr, Seq(Val.I32(0), Val.I32(0)))),
          Inst(id.name, Op.Load(Type.I32, idptr)),
          Inst(methptrptr.name,
               Op.Elem(dispatchTy,
                       dispatchVal,
                       Seq(Val.I32(0), id, Val.I32(meth.id)))),
          Inst(n, Op.Load(Type.Ptr, methptrptr))
      )

    case inst @ Inst(n, Op.Is(TraitRef(trt), obj)) =>
      val typeptr = Val.Local(fresh(), Type.Ptr)
      val idptr   = Val.Local(fresh(), Type.Ptr)
      val id      = Val.Local(fresh(), Type.I32)
      val boolptr = Val.Local(fresh(), Type.Ptr)

      Seq(
          Inst(typeptr.name, Op.Load(Type.Ptr, obj)),
          Inst(idptr.name,
               Op.Elem(Rt.Type, typeptr, Seq(Val.I32(0), Val.I32(0)))),
          Inst(id.name, Op.Load(Type.I32, idptr)),
          Inst(boolptr.name,
               Op.Elem(instanceTy,
                       instanceVal,
                       Seq(Val.I32(0), id, Val.I32(trt.id)))),
          Inst(n, Op.Load(Type.Bool, boolptr))
      )
  }
}

object TraitLowering extends PassCompanion {
  def apply(ctx: Ctx) = new TraitLowering()(ctx.top, ctx.fresh)

  val dispatchName = Global.Top("__dispatch")
  val dispatchVal  = Val.Global(dispatchName, Type.Ptr)

  val instanceName = Global.Top("__instance")
  val instanceVal  = Val.Global(instanceName, Type.Ptr)
}
