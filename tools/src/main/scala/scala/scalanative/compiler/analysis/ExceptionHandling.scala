package scala.scalanative
package compiler
package analysis

import nir._

object ExceptionHandling {
  type Result = Map[Local, Option[(Val, Local)]]
  def apply(blocks: Seq[Block]): Result = Map.empty
}
