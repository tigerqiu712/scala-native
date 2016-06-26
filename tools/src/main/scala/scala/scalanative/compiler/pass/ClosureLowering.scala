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
  override def preInst = Expand[Inst] {
    case Inst(_, _: Op.Closure) =>
      ???
  }
}

object ClosureLowering extends PassCompanion {
  def apply(ctx: Ctx) = new ClosureLowering
}
