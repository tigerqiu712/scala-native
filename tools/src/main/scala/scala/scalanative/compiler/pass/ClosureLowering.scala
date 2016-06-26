package scala.scalanative
package compiler
package pass

import scala.collection.mutable
import util.ScopedVar, ScopedVar.scoped
import nir._
import Tx.{Expand, Replace}

/** Eliminates:
 *  - Op.Closure
 */
class ClosureLowering extends Pass {
  override def preInst = Replace[Inst] {
    case isnt @ Inst(_, _: Op.Closure) =>
      unsupported(inst)
  }
}

object ClosureLowering extends PassCompanion {
  def apply(ctx: Ctx) = new ClosureLowering
}
