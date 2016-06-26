package scala.scalanative
package compiler
package pass

import scala.collection.mutable
import analysis.ClassHierarchy.Top
import nir._, Shows._
import util.sh
import Tx.{Expand, Replace}

class DeadCodeElimination(implicit top: Top) extends Pass {
  override def postDefn = Replace[Defn] {
    case defn: Defn.Define =>
      println(defn.name)
      val usedef = analysis.UseDef(defn.blocks)

      val newBlocks = defn.blocks.map { block =>
        val newInsts = block.insts.filter {
          case Inst(n, op) => usedef(n).alive
        }

        block.copy(insts = newInsts)
      }

      defn.copy(blocks = newBlocks)
  }
}

object DeadCodeElimination extends PassCompanion {
  def apply(ctx: Ctx) = new DeadCodeElimination()(ctx.top)
}
