package scala.scalanative
package compiler

import scala.collection.mutable
import nir._

trait Pass {
  def preInject: Seq[Defn]  = Seq()
  def postInject: Seq[Defn] = Seq()
  def preDefn: Tx[Defn]     = Tx.None
  def postDefn: Tx[Defn]    = Tx.None
  def preBlock: Tx[Block]   = Tx.None
  def postBlock: Tx[Block]  = Tx.None
  def preInst: Tx[Inst]     = Tx.None
  def postInst: Tx[Inst]    = Tx.None
  def preCf: Tx[Cf]         = Tx.None
  def postCf: Tx[Cf]        = Tx.None
  def preVal: Tx[Val]       = Tx.None
  def postVal: Tx[Val]      = Tx.None
  def preType: Tx[Type]     = Tx.None
  def postType: Tx[Type]    = Tx.None
}

trait PassCompanion {
  def apply(ctx: Ctx): Pass
  def depends: Seq[Global] = Seq()
}
