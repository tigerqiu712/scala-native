package scala.scalanative
package compiler
package codegen

import java.{lang => jl}
import scala.collection.mutable
import util.{unsupported, unreachable, sh, Show}
import util.Show.{Sequence => s, Indent => i, Unindent => ui, Repeat => r, Newline => nl}
import compiler.analysis._
import ClassHierarchyExtractors._
import ClassHierarchy.Top
import ControlFlow.{Graph => CFG}
import ExceptionHandling.{Result => EH}
import nir.Shows.brace
import nir._

class LLCodeGen(assembly: Seq[Defn])(implicit top: Top) {
  type Res = Show.Result

  private val fresh = new Fresh("gen")
  private val globals = assembly.collect {
    case Defn.Var(_, n, ty, _)     => n -> ty
    case Defn.Const(_, n, ty, _)   => n -> ty
    case Defn.Declare(_, n, sig)   => n -> sig
    case Defn.Define(_, n, sig, _) => n -> sig
  }.toMap
  private val prelude = Seq(
      sh"declare i32 @llvm.eh.typeid.for(i8*)",
      sh"declare i32 @__gxx_personality_v0(...)",
      sh"declare i8* @__cxa_begin_catch(i8*)",
      sh"declare void @__cxa_end_catch()",
      sh"@_ZTIN11scalanative16ExceptionWrapperE = external constant { i8*, i8*, i8* }"
  )
  private val gxxpersonality =
    sh"personality i8* bitcast (i32 (...)* @__gxx_personality_v0 to i8*)"
  private val excrecty = sh"{ i8*, i32 }"
  private val landingpad =
    sh"landingpad { i8*, i32 } catch i8* bitcast ({ i8*, i8*, i8* }* @_ZTIN11scalanative16ExceptionWrapperE to i8*)"
  private val typeid =
    sh"call i32 @llvm.eh.typeid.for(i8* bitcast ({ i8*, i8*, i8* }* @_ZTIN11scalanative16ExceptionWrapperE to i8*))"
  private val RtType = genType(Rt.Type)

  def gen(buffer: java.nio.ByteBuffer) =
    buffer.put(genAssembly().toString.getBytes)

  def genAssembly() = {
    val sorted = assembly.sortBy {
      case _: Defn.Struct  => 1
      case _: Defn.Const   => 2
      case _: Defn.Var     => 3
      case _: Defn.Declare => 4
      case _: Defn.Define  => 5
      case _               => -1
    }

    r(prelude ++: sorted.map(genDefn), sep = nl(""))
  }

  def genDefn(defn: Defn) = defn match {
    case Defn.Var(attrs, name, ty, rhs) =>
      genGlobalDefn(name, attrs.isExtern, isConst = false, ty, rhs)
    case Defn.Const(attrs, name, ty, rhs) =>
      genGlobalDefn(name, attrs.isExtern, isConst = true, ty, rhs)
    case Defn.Declare(attrs, name, sig) =>
      genFunctionDefn(attrs, name, sig, Seq())
    case Defn.Define(attrs, name, sig, blocks) =>
      genFunctionDefn(attrs, name, sig, blocks)
    case Defn.Struct(attrs, name, tys) =>
      sh"%$name = type {${r(tys, sep = ", ")}}"
    case defn =>
      unsupported(defn)
  }

  def genGlobalDefn(name: nir.Global,
                    isExtern: Boolean,
                    isConst: Boolean,
                    ty: nir.Type,
                    rhs: nir.Val) = {
    val external = if (isExtern) "external " else ""
    val keyword  = if (isConst) "constant" else "global"
    val init = rhs match {
      case Val.None => sh"$ty"
      case _        => sh"$rhs"
    }

    sh"@$name = $external$keyword $init"
  }

  def genFunctionDefn(attrs: Attrs,
                      name: Global,
                      sig: Type,
                      blocks: Seq[Block]) = {
    val Type.Function(argtys, retty) = sig

    val isDecl  = blocks.isEmpty
    val keyword = if (isDecl) "declare" else "define"
    val params =
      if (isDecl) r(argtys, sep = ", ")
      else r(blocks.head.params: Seq[Val], sep = ", ")
    val postattrs: Seq[Attr] =
      if (attrs.inline != Attr.MayInline) Seq(attrs.inline) else Seq()
    val personality = if (attrs.isExtern || isDecl) s() else gxxpersonality
    val body = if (isDecl) {
      s()
    } else {
      implicit val cfg = ControlFlow(blocks)
      implicit val eh  = ExceptionHandling(blocks)
      val blockshows: Seq[Res] = cfg.map { node =>
        genBlock(node.block, node.pred, isEntry = node eq cfg.entry)
      }
      val landingpadshows: Seq[Res] = cfg.map { node =>
        node.block match {
          case Block(n, _, _, cf: Cf.Try) => genLandingPad(n, cf)
          case _                          => Seq()
        }
      }.flatten
      s(" ", brace(r(blockshows ++ landingpadshows)))
    }

    sh"$keyword $retty @$name($params)$postattrs$personality$body"
  }

  def genBlock(block: Block, pred: Seq[ControlFlow.Edge], isEntry: Boolean)(
      implicit cfg: CFG,
      eh: EH): Res = {
    val Block(name, params, insts, cf) = block
    val phis = if (isEntry) {
      Seq()
    } else {
      val branches = phiBranches(pred)
      params.zipWithIndex.map {
        case (Val.Local(name, ty), n) =>
          val froms = branches.map {
            case (from, shows) =>
              sh"[${shows(n)}, %$from]"
          }
          i(sh"%$name = phi $ty ${r(froms, sep = ", ")}")
      }
    }
    val body = r(genInsts(insts, block.cf).map(i(_)))

    sh"${nl("")}${block.name}:${r(phis)}$body"
  }

  def genLandingPad(in: Local, cf: Cf.Try)(implicit cfg: CFG,
                                           eh: EH): Seq[Res] = {
    val landingpad      = sh"$in.landingpad"
    val resume          = sh"$in.resume"
    val rec, rec0, rec1 = fresh()
    val recid, reccmp   = fresh()

    val catches = cf.catches.zipWithIndex.collect {
      case (Next.Catch(ty, succ), n) =>
        val catchn       = sh"$in.catch.$n"
        val w0, w1, w2   = fresh()
        val exc, id, cmp = fresh()
        val fail         = ???

        // format: off
        Seq(
          nl(sh"$catchn:"),
            i(sh"%$w0 = call i8* @__cxa_begin_catch(i8* %$rec0)"),
            i(sh"%$w1 = bitcast i8* %$w0 to i8**"),
            i(sh"%$w2 = getelementptr i8*, i8** %$w1, i32 1"),
            i(sh"%$exc = load i8*, i8** %$w2"),
            i(sh"call void @__cxa_end_catch()"),
            i(sh"%$id = "),
            i(sh"%$cmp = "),
            i(sh"br i1 %$cmp, %$succ, %$fail")
        )
        // format: on
    }.flatten

    // format: off
    Seq(
      nl(sh"$landingpad:"),
        i(sh"%$rec = $landingpad"),
        i(sh"%$rec0 = extractvalue $excrecty %$rec, 0"),
        i(sh"%$rec1 = extractvalue $excrecty %$rec, 1"),
        i(sh"%$recid = $typeid"),
        i(sh"%$reccmp = icmp eq i32 %$rec1, %$recid"),
        i(sh"br i1 %$reccmp, label %$in.catch.0, label %$resume"),
      nl(sh"$resume:"),
        i(sh"resume $excrecty %$rec")
    ) ++ catches
    // format: on
  }

  def genInsts(insts: Seq[Inst], cf: Cf)(implicit cfg: CFG, eh: EH): Seq[Res] = {
    val buf = mutable.UnrolledBuffer.empty[Res]

    insts.foreach { inst =>
      val op   = inst.op
      val name = inst.name
      val bind = if (isVoid(op.resty)) s() else sh"%$name = "

      genInst(buf, bind, op)
    }

    genCf(buf, cf)

    buf
  }

  def genInst(buf: mutable.UnrolledBuffer[Res], bind: Res, op: Op): Unit =
    op match {
      case Op.Call(ty, Val.Global(pointee, _), args) =>
        buf += sh"${bind}call $ty @$pointee(${r(args, sep = ", ")})"

      case Op.Call(ty, ptr, args) =>
        val pointee = fresh()

        buf += sh"%$pointee = bitcast $ptr to $ty*"
        buf += sh"${bind}call $ty %$pointee(${r(args, sep = ", ")})"

      case Op.Load(ty, ptr) =>
        val pointee = fresh()

        buf += sh"%$pointee = bitcast $ptr to $ty*"
        buf += sh"${bind}load $ty, $ty* %$pointee"

      case Op.Store(ty, ptr, value) =>
        val pointee = fresh()

        buf += sh"%$pointee = bitcast $ptr to $ty*"
        buf += sh"${bind}store $value, $ty* %$pointee"

      case Op.Elem(ty, ptr, indexes) =>
        val pointee = fresh()
        val derived = fresh()

        buf += sh"%$pointee = bitcast $ptr to $ty*"
        buf +=
        sh"%$derived = getelementptr $ty, $ty* %$pointee, ${r(indexes, sep = ", ")}"
        buf +=
        sh"${bind}bitcast ${ty.elemty(indexes.tail)}* %$derived to i8*"

      case Op.Stackalloc(ty, n) =>
        val pointee = fresh()
        val elems   = if (n == Val.None) sh"" else sh", $n"

        buf += sh"%$pointee = alloca $ty$elems"
        buf += sh"${bind}bitcast $ty* %$pointee to i8*"

      case Op.Extract(aggr, indexes) =>
        buf += sh"${bind}extractvalue $aggr, ${r(indexes, sep = ", ")}"

      case Op.Insert(aggr, value, indexes) =>
        buf += sh"${bind}insertvalue $aggr, $value, ${r(indexes, sep = ", ")}"

      case Op.Bin(opcode, ty, l, r) =>
        val bin = opcode match {
          case Bin.Iadd => "add"
          case Bin.Isub => "sub"
          case Bin.Imul => "mul"
          case _        => opcode.toString.toLowerCase
        }

        buf += sh"${bind}$bin $l, ${genJustVal(r)}"

      case Op.Comp(opcode, ty, l, r) =>
        val cmp = opcode match {
          case Comp.Ieq => "icmp eq"
          case Comp.Ine => "icmp ne"
          case Comp.Ult => "icmp ult"
          case Comp.Ule => "icmp ule"
          case Comp.Ugt => "icmp ugt"
          case Comp.Uge => "icmp uge"
          case Comp.Slt => "icmp slt"
          case Comp.Sle => "icmp sle"
          case Comp.Sgt => "icmp sgt"
          case Comp.Sge => "icmp sge"
          case Comp.Feq => "fcmp ueq"
          case Comp.Fne => "fcmp une"
          case Comp.Flt => "fcmp ult"
          case Comp.Fle => "fcmp ule"
          case Comp.Fgt => "fcmp ugt"
          case Comp.Fge => "fcmp uge"
        }

        buf += sh"${bind}$cmp $l, ${genJustVal(r)}"

      case Op.Conv(name, ty, v) =>
        buf += sh"${bind}$name $v to $ty"

      case Op.Select(cond, v1, v2) =>
        buf += sh"${bind}select $cond, $v1, $v2"

      case Op.Is(ClassRef(cls), obj) =>
        val typeptrptr, typeptr = fresh()

        buf += sh"%$typeptrptr = bitcast $obj to i8**"
        buf += sh"%$typeptr = load i8*, i8** $typeptrptr"

        if (cls.range.length == 1) {
          buf += sh"${bind}icmp eq i8* $typeptr, ${cls.typeConst}"

        } else {
          val idptr, id, ge, le = fresh()

          buf += sh"%$idptr = getelementptr $RtType, $RtType* $typeptr, i32 0, i32 0"
          buf += sh"%$id = load i32, i32* %$idptr"
          buf += sh"%$ge = icmp sle i32 ${cls.range.start}, %$id"
          buf += sh"%$le = icmp sle i32 %$id, ${cls.range.end}"
          buf += sh"${bind}and i1 %$ge, %$le"
        }

      case Op.Is(TraitRef(trt), obj) =>
        val typeptrptr, typeptr, idptr, id, boolptr = fresh()

        val ity, ival = ???

        buf += sh"%$typeptrptr = bitcast $obj to i8**"
        buf += sh"%$typeptr = load i8*, i8** $obj"
        buf += sh"%$idptr = getelementptr $RtType, $RtType* $typeptr, i32 0, i32 0"
        buf += sh"%$id = load i32, i32** %$idptr"
        buf += sh"%$boolptr = getelementptr $ity, $ival, i32 0, i32 %$id, i32 ${trt.id}"
        buf += sh"${bind}load i1, i1* %$boolptr"

      case op =>
        unsupported(op)
    }

  def genCf(buf: mutable.UnrolledBuffer[Res], cf: Cf)(implicit cfg: CFG) =
    cf match {
      case Cf.Unreachable =>
        buf += "unreachable"

      case Cf.Ret(Val.None) =>
        buf += sh"ret void"

      case Cf.Ret(value) =>
        buf += sh"ret $value"

      case Cf.Jump(next) =>
        buf += sh"br $next"

      case Cf.If(cond, thenp, elsep) =>
        buf += sh"br $cond, $thenp, $elsep"

      case Cf.Switch(scrut, default, cases) =>
        buf += sh"switch $scrut, $default [${r(cases.map(i(_)))}${nl("]")}"

      case Cf.Throw(value) =>
        ???

      case Cf.Try(default, cases) =>
        ???

      case cf =>
        unsupported(cf)
    }

  implicit val genType: Show[Type] = Show {
    case Type.Void                     => "void"
    case Type.Vararg                   => "..."
    case Type.Ptr                      => "i8*"
    case Type.Bool                     => "i1"
    case Type.I8                       => "i8"
    case Type.I16                      => "i16"
    case Type.I32                      => "i32"
    case Type.I64                      => "i64"
    case Type.F32                      => "float"
    case Type.F64                      => "double"
    case Type.Array(ty, n)             => sh"[$n x $ty]"
    case Type.Function(args, ret)      => sh"$ret (${r(args, sep = ", ")})"
    case Type.Struct(Global.None, tys) => sh"{ ${r(tys, sep = ", ")} }"
    case Type.Struct(name, _)          => sh"%$name"
    case ty                            => unsupported(ty)
  }

  def genJustVal(v: Val): Res = v match {
    case Val.True          => "true"
    case Val.False         => "false"
    case Val.Zero(ty)      => "zeroinitializer"
    case Val.Undef(ty)     => "undef"
    case Val.I8(v)         => v.toString
    case Val.I16(v)        => v.toString
    case Val.I32(v)        => v.toString
    case Val.I64(v)        => v.toString
    case Val.F32(v)        => llvmFloatHex(v)
    case Val.F64(v)        => llvmDoubleHex(v)
    case Val.Struct(_, vs) => sh"{ ${r(vs, sep = ", ")} }"
    case Val.Array(_, vs)  => sh"[ ${r(vs, sep = ", ")} ]"
    case Val.Chars(v)      => s("c\"", v, "\\00", "\"")
    case Val.Local(n, ty)  => sh"%$n"
    case Val.Global(n, ty) => sh"bitcast (${globals(n)}* @$n to i8*)"
    case _                 => unsupported(v)
  }

  implicit val genVal: Show[Val] = Show { v =>
    sh"${v.ty} ${genJustVal(v)}"
  }

  implicit val genGlobal: Show[Global] = Show { g =>
    def justGlobal(g: Global): Res = g match {
      case Global.None          => unsupported(g)
      case Global.Top(id)       => id
      case Global.Member(n, id) => s(justGlobal(n), "::", id)
    }

    quoted(justGlobal(g))
  }

  implicit val genLocal: Show[Local] = Show {
    case Local(scope, id) => sh"$scope.$id"
  }

  implicit val genNext: Show[Next] = Show {
    case Next.Case(v, n) => sh"$v, label %$n"
    case next            => sh"label %${next.name}"
  }

  implicit def genConv: Show[Conv] = nir.Shows.showConv

  implicit def genAttrSeq: Show[Seq[Attr]] = nir.Shows.showAttrSeq

  private def isVoid(ty: Type): Boolean =
    ty == Type.Void || ty == Type.Unit || ty == Type.Nothing

  private def llvmFloatHex(value: Float): String =
    "0x" + jl.Long.toHexString(jl.Double.doubleToRawLongBits(value.toDouble))

  private def llvmDoubleHex(value: Double): String =
    "0x" + jl.Long.toHexString(jl.Double.doubleToRawLongBits(value))

  private def quoted(sh: Res) = s("\"", sh, "\"")

  private def phiBranches(
      edges: Seq[ControlFlow.Edge]): Seq[(Local, Seq[Res])] = ???
}
