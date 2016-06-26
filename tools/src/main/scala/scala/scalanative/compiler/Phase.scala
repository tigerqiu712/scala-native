package scala.scalanative
package compiler

import scala.collection.mutable
import nir._

final class Phase(passes: Seq[Pass]) {
  import Pass._

  private val preInject: OnInject  = Hook.fuse(passes.map(_.preInject))
  private val postInject: OnInject = Hook.fuse(passes.map(_.postInject))
  private val preDefn: OnDefn      = Hook.fuse(passes.map(_.preDefn))
  private val postDefn: OnDefn     = Hook.fuse(passes.map(_.postDefn))
  private val preBlock: OnBlock    = Hook.fuse(passes.map(_.preBlock))
  private val postBlock: OnBlock   = Hook.fuse(passes.map(_.postBlock))
  private val preInst: OnInst      = Hook.fuse(passes.map(_.preInst))
  private val postInst: OnInst     = Hook.fuse(passes.map(_.postInst))
  private val preCf: OnCf          = Hook.fuse(passes.map(_.preCf))
  private val postCf: OnCf         = Hook.fuse(passes.map(_.postCf))
  private val preVal: OnVal        = Hook.fuse(passes.map(_.preVal))
  private val postVal: OnVal       = Hook.fuse(passes.map(_.postVal))
  private val preType: OnType      = Hook.fuse(passes.map(_.preType))
  private val postType: OnType     = Hook.fuse(passes.map(_.postType))

  private def txAssembly(assembly: Seq[Defn]): Seq[Defn] = {
    val pre = preInject.invoke((), orElse = Seq())

    val post = (assembly ++ pre).flatMap { defn =>
      txDefn(defn)
    }

    post ++ postInject.invoke((), orElse = Seq())
  }

  private def txDefn(defn: Defn): Seq[Defn] = {
    val pres = preDefn.invoke(defn, orElse = Seq(defn))

    pres.flatMap { pre =>
      val post = pre match {
        case defn @ Defn.Var(_, _, ty, value) =>
          defn.copy(ty = txType(ty), value = txVal(value))
        case defn @ Defn.Const(_, _, ty, value) =>
          defn.copy(ty = txType(ty), value = txVal(value))
        case defn @ Defn.Declare(_, _, ty) =>
          defn.copy(ty = txType(ty))
        case defn @ Defn.Define(_, _, ty, blocks) =>
          defn.copy(ty = txType(ty), blocks = blocks.flatMap(txBlock))
        case defn @ Defn.Struct(_, _, tys) =>
          defn.copy(tys = tys.map(txType))
        case defn @ Defn.Trait(_, _, _) =>
          defn
        case defn @ Defn.Class(_, _, _, _) =>
          defn
        case defn @ Defn.Module(_, _, _, _) =>
          defn
      }

      postDefn.invoke(post, orElse = Seq(post))
    }
  }

  private def txBlock(block: Block): Seq[Block] = {
    val pres = preBlock.invoke(block, orElse = Seq(block))

    pres.flatMap { pre =>
      val newparams = pre.params.map { param =>
        Val.Local(param.name, txType(param.ty))
      }
      val newinsts = pre.insts.flatMap(txInst)
      val newcf    = txCf(pre.cf)
      val post     = Block(pre.name, newparams, newinsts, newcf)

      postBlock.invoke(post, orElse = Seq(post))
    }
  }

  private def txInst(inst: Inst): Seq[Inst] = {
    val pres = preInst.invoke(inst, orElse = Seq(inst))

    pres.flatMap { pre =>
      val newop = pre.op match {
        case Op.Call(ty, ptrv, argvs) =>
          Op.Call(txType(ty), txVal(ptrv), argvs.map(txVal))
        case Op.Load(ty, ptrv) =>
          Op.Load(txType(ty), txVal(ptrv))
        case Op.Store(ty, ptrv, v) =>
          Op.Store(txType(ty), txVal(ptrv), txVal(v))
        case Op.Elem(ty, ptrv, indexvs) =>
          Op.Elem(txType(ty), txVal(ptrv), indexvs.map(txVal))
        case Op.Extract(aggrv, indexvs) =>
          Op.Extract(txVal(aggrv), indexvs)
        case Op.Insert(aggrv, v, indexvs) =>
          Op.Insert(txVal(aggrv), txVal(v), indexvs)
        case Op.Stackalloc(ty) =>
          Op.Stackalloc(txType(ty))
        case Op.Bin(bin, ty, lv, rv) =>
          Op.Bin(bin, txType(ty), txVal(lv), txVal(rv))
        case Op.Comp(comp, ty, lv, rv) =>
          Op.Comp(comp, txType(ty), txVal(lv), txVal(rv))
        case Op.Conv(conv, ty, v) =>
          Op.Conv(conv, txType(ty), txVal(v))

        case Op.Classalloc(n) =>
          Op.Classalloc(n)
        case Op.Field(ty, v, n) =>
          Op.Field(txType(ty), txVal(v), n)
        case Op.Method(ty, v, n) =>
          Op.Method(txType(ty), txVal(v), n)
        case Op.Module(n) =>
          Op.Module(n)
        case Op.As(ty, v) =>
          Op.As(txType(ty), txVal(v))
        case Op.Is(ty, v) =>
          Op.Is(txType(ty), txVal(v))
        case Op.Copy(v) =>
          Op.Copy(txVal(v))
        case Op.Sizeof(ty) =>
          Op.Sizeof(txType(ty))
        case Op.Closure(ty, fun, captures) =>
          Op.Closure(txType(ty), txVal(fun), captures.map(txVal))
      }
      val post = Inst(pre.name, newop)

      postInst.invoke(post, orElse = Seq(post))
    }
  }

  private def txCf(cf: Cf): Cf = {
    val pre = preCf.invoke(cf, orElse = cf)
    val post = pre match {
      case Cf.Unreachable =>
        Cf.Unreachable
      case Cf.Ret(v) =>
        Cf.Ret(txVal(v))
      case Cf.Jump(next) =>
        Cf.Jump(txNext(next))
      case Cf.If(v, thenp, elsep) =>
        Cf.If(txVal(v), txNext(thenp), txNext(elsep))
      case Cf.Switch(v, default, cases) =>
        Cf.Switch(txVal(v), txNext(default), cases.map(txNext))
      case Cf.Invoke(ty, ptrv, argvs, succ, fail) =>
        Cf.Invoke(txType(ty),
                  txVal(ptrv),
                  argvs.map(txVal),
                  txNext(succ),
                  txNext(fail))
      case Cf.Resume(excrec) =>
        Cf.Resume(txVal(excrec))

      case Cf.Throw(v) =>
        Cf.Throw(txVal(v))
      case Cf.Try(norm, exc) =>
        Cf.Try(txNext(norm), txNext(exc))
    }

    postCf.invoke(post, orElse = post)
  }

  private def txVal(value: Val): Val = {
    val pre = preVal.invoke(value, orElse = value)
    val post = pre match {
      case Val.Zero(ty)          => Val.Zero(txType(ty))
      case Val.Undef(ty)         => Val.Undef(txType(ty))
      case Val.Struct(n, values) => Val.Struct(n, values.map(txVal))
      case Val.Array(ty, values) => Val.Array(txType(ty), values.map(txVal))
      case Val.Local(n, ty)      => Val.Local(n, txType(ty))
      case Val.Global(n, ty)     => Val.Global(n, txType(ty))
      case Val.Const(v)          => Val.Const(txVal(v))
      case _                     => pre
    }

    postVal.invoke(post, orElse = post)
  }

  private def txType(ty: Type): Type = {
    val pre = preType.invoke(ty, orElse = ty)
    val post = pre match {
      case Type.Array(ty, n)      => Type.Array(txType(ty), n)
      case Type.Function(tys, ty) => Type.Function(tys.map(txType), txType(ty))
      case Type.Struct(n, tys)    => Type.Struct(n, tys.map(txType))
      case _                      => pre
    }

    postType.invoke(post, orElse = post)
  }

  private def txNext(next: Next): Next = next match {
    case succ: Next.Succ     => succ
    case fail: Next.Fail     => fail
    case Next.Label(n, args) => Next.Label(n, args.map(txVal))
    case Next.Case(v, n)     => Next.Case(txVal(v), n)
  }

  final def apply(assembly: Seq[Defn]): Seq[Defn] = txAssembly(assembly)
  final def apply(defn: Defn): Seq[Defn]          = txDefn(defn)
  final def apply(block: Block): Seq[Block]       = txBlock(block)
  final def apply(inst: Inst): Seq[Inst]          = txInst(inst)
  final def apply(cf: Cf): Cf                     = txCf(cf)
  final def apply(value: Val): Val                = txVal(value)
  final def apply(ty: Type): Type                 = txType(ty)
}

object Phase {}
