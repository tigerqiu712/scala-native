package scala.scalanative
package compiler
package pass

import scala.collection.mutable
import scala.util.control.Breaks._
import util.unsupported
import nir._
import Tx.{Expand, Replace}

/** Hoists all stack allocations to the entry basic block. */
class StackallocHoisting extends Pass {
  private var allocs = mutable.UnrolledBuffer.empty[Inst]

  override def preDefn = Expand[Defn] {
    case defn: Defn.Define =>
      allocs.clear
      Seq(defn)
  }

  override def preInst = Expand[Inst] {
    case inst @ Inst(_, alloc: Op.Stackalloc) =>
      allocs += inst
      Seq()
  }

  override def postDefn = Replace[Defn] {
    case defn: Defn.Define =>
      val Block(n, params, insts, cf) +: rest = defn.blocks

      val newRest = rest.flatMap { block =>
        val insts = block.insts.map {
          case
        }

      }

      val newBlocks = Block(n, params, allocs ++: insts, cf) +: rest

      defn.copy(blocks = newBlocks)
  }
}

object StackallocHoisting extends PassCompanion {
  def apply(ctx: Ctx) = new StackallocHoisting
}
