package scala.scalanative
package compiler

import java.nio.ByteBuffer
import scala.collection.mutable
import codegen.LLCodeGen
import linker.Linker
import nir._, Shows._
import nir.serialization._
import util.sh

final class Compiler(opts: Opts) {
  private lazy val entry =
    Global.Member(Global.Top(opts.entry), "main_class.ssnr.ObjectArray_unit")

  private lazy val passCompanions: Seq[PassCompanion] = Seq(
      pass.LocalBoxingElimination,
      pass.DeadCodeElimination,
      pass.MainInjection,
      pass.ExternHoisting,
      pass.ModuleLowering,
      pass.RuntimeTypeInfoInjection,
      pass.AsLowering,
      pass.TraitLowering,
      pass.ClassLowering,
      pass.StringLowering,
      pass.ConstLowering,
      pass.SizeofLowering,
      pass.UnitLowering,
      pass.NothingLowering,
      pass.StackallocHoisting,
      pass.CopyPropagation)

  private lazy val (links, assembly): (Seq[Attr.Link], Seq[Defn]) = {
    val deps           = passCompanions.flatMap(_.depends).distinct
    val injects        = passCompanions.flatMap(_.injects).distinct
    val linker         = new Linker(opts.dotpath, opts.classpath)
    val (links, defns) = linker.linkClosed(entry +: deps)

    (links, defns ++ injects)
  }

  private lazy val ctx = Ctx(fresh = Fresh("tx"),
                             entry = entry,
                             top = analysis.ClassHierarchy(assembly))

  private lazy val passes = passCompanions.map(_.apply(ctx))

  private def codegen(assembly: Seq[Defn]): Unit = {
    def serialize(defns: Seq[Defn], bb: ByteBuffer): Unit = {
      val gen = new LLCodeGen(assembly)(ctx.top)
      gen.gen(bb)
    }
    serializeFile(serialize _, assembly, opts.outpath)
  }

  private def debug(assembly: Seq[Defn], suffix: String) =
    if (opts.verbose) {
      def serialize(defns: Seq[Defn], bb: ByteBuffer): Unit = {
        bb.put(nir.Shows.showDefns(assembly).toString.getBytes)
      }
      serializeFile(serialize _, assembly, opts.outpath + s".$suffix.hnir")
    }

  def apply(): Seq[Attr.Link] = {
    def loop(assembly: Seq[Defn], passes: Seq[(Pass, Int)]): Seq[Defn] =
      passes match {
        case Seq() =>
          assembly

        case (pass, id) +: rest =>
          val nassembly = pass(assembly)
          val n         = id + 1
          val padded    = if (n < 10) "0" + n else "" + n

          debug(nassembly, padded + "-" + pass.getClass.getSimpleName)
          loop(nassembly, rest)
      }

    debug(assembly, "00")
    codegen(loop(assembly, passes.zipWithIndex))

    links
  }
}
