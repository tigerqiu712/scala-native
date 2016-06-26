package scala.scalanative
package compiler

import scala.collection.mutable
import nir._

final class Phase(passes: Seq[Pass]) {
  private val preDefn: Tx[Defn]    = Tx.fuse(passes.map(_.preDefn))
  private val postDefn: Tx[Defn]   = Tx.fuse(passes.map(_.postDefn))
  private val preBlocks: Tx[Seq[Block]] = Tx.fuse(passes.map(_.preBlocks))
  private val postBlocks: Tx[Seq[Block]] = Tx.fuse(passes.map(_.postBlocks))
  private val preBlock: Tx[Block]  = Tx.fuse(passes.map(_.preBlock))
  private val postBlock: Tx[Block] = Tx.fuse(passes.map(_.postBlock))
  private val preInst: Tx[Inst]    = Tx.fuse(passes.map(_.preInst))
  private val postInst: Tx[Inst]   = Tx.fuse(passes.map(_.postInst))
  private val preCf: Tx[Cf]        = Tx.fuse(passes.map(_.preCf))
  private val postCf: Tx[Cf]       = Tx.fuse(passes.map(_.postCf))
  private val preVal: Tx[Val]      = Tx.fuse(passes.map(_.preVal))
  private val postVal: Tx[Val]     = Tx.fuse(passes.map(_.postVal))
  private val preType: Tx[Type]    = Tx.fuse(passes.map(_.preType))
  private val postType: Tx[Type]   = Tx.fuse(passes.map(_.postType))

  private def txAssembly(assembly: Seq[Defn]): Seq[Defn] = {
    val pre = assembly ++ passes.flatMap(_.preInject)
    txDefns(pre) ++ passes.flatMap(_.postInject)
  }

  private def txDefns(defns: Seq[Defn]): Seq[Defn] =
    Tx.invoke(postDefn, Tx.invoke(preDefn, defns).map(inDefn))

  private def inDefn(defn: Defn): Defn = defn match {
    case defn @ Defn.Var(_, _, ty, value) =>
      defn.copy(ty = txType(ty), value = txVal(value))
    case defn @ Defn.Const(_, _, ty, value) =>
      defn.copy(ty = txType(ty), value = txVal(value))
    case defn @ Defn.Declare(_, _, ty) =>
      defn.copy(ty = txType(ty))
    case defn @ Defn.Define(_, _, ty, blocks) =>
      defn.copy(ty = txType(ty), blocks = txBlocks(blocks))
    case defn @ Defn.Struct(_, _, tys) =>
      defn.copy(tys = tys.map(txType))
    case defn @ Defn.Trait(_, _, _) =>
      defn
    case defn @ Defn.Class(_, _, _, _) =>
      defn
    case defn @ Defn.Module(_, _, _, _) =>
      defn
  }

  private def txBlocks(blocks: Seq[Block]): Seq[Block] =
    Tx.invoke(postBlock, Tx.invoke(preBlock, blocks).map(inBlock))

  private def inBlock(block: Block): Block = {
    val newparams = block.params.map { param =>
      Val.Local(param.name, txType(param.ty))
    }
    val newinsts = txInsts(block.insts)
    val newcf    = txCf(block.cf)

    Block(block.name, newparams, newinsts, newcf)
  }

  private def txInsts(insts: Seq[Inst]): Seq[Inst] =
    Tx.invoke(postInst, Tx.invoke(preInst, insts).map(inInst))

  private def inInst(inst: Inst): Inst = {
    val newop = inst.op match {
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

    Inst(inst.name, newop)
  }

  private def txCf(cf: Cf): Cf =
    Tx.invoke1(postCf, inCf(Tx.invoke1(preCf, cf)))

  private def inCf(cf: Cf): Cf = cf match {
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

  private def txVal(value: Val): Val =
    Tx.invoke1(postVal, inVal(Tx.invoke1(preVal, value)))

  private def inVal(value: Val): Val = value match {
    case Val.Zero(ty)          => Val.Zero(txType(ty))
    case Val.Undef(ty)         => Val.Undef(txType(ty))
    case Val.Struct(n, values) => Val.Struct(n, values.map(txVal))
    case Val.Array(ty, values) => Val.Array(txType(ty), values.map(txVal))
    case Val.Local(n, ty)      => Val.Local(n, txType(ty))
    case Val.Global(n, ty)     => Val.Global(n, txType(ty))
    case Val.Const(v)          => Val.Const(txVal(v))
    case _                     => value
  }

  private def txType(ty: Type): Type =
    Tx.invoke1(postType, inType(Tx.invoke1(preType, ty)))

  private def inType(ty: Type): Type = ty match {
    case Type.Array(ty, n)      => Type.Array(txType(ty), n)
    case Type.Function(tys, ty) => Type.Function(tys.map(txType), txType(ty))
    case Type.Struct(n, tys)    => Type.Struct(n, tys.map(txType))
    case _                      => ty
  }

  private def txNext(next: Next): Next =
    inNext(next)

  private def inNext(next: Next): Next = next match {
    case succ: Next.Succ     => succ
    case fail: Next.Fail     => fail
    case Next.Label(n, args) => Next.Label(n, args.map(txVal))
    case Next.Case(v, n)     => Next.Case(txVal(v), n)
  }

  final def assembly(assembly: Seq[Defn]): Seq[Defn] = txAssembly(assembly)
  final def insts(inst: Seq[Inst]): Seq[Inst]        = txInsts(inst)
  final def cf(cf: Cf): Cf                           = txCf(cf)
}
