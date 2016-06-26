package scala.scalanative
package compiler

import scala.collection.mutable
import nir._

trait Pass {
  def preInject: Seq[Defn]  = Seq()
  def postInject: Seq[Defn] = Seq()
  def preDefn: Tx[Defn]     = Tx.None[Defn]
  def postDefn: Tx[Defn]    = Tx.None[Defn]
  def preBlock: Tx[Block]   = Tx.None[Block]
  def postBlock: Tx[Block]  = Tx.None[Block]
  def preInst: Tx[Inst]     = Tx.None[Inst]
  def postInst: Tx[Inst]    = Tx.None[Inst]
  def preCf: Tx[Cf]         = Tx.None[Cf]
  def postCf: Tx[Cf]        = Tx.None[Cf]
  def preVal: Tx[Val]       = Tx.None[Val]
  def postVal: Tx[Val]      = Tx.None[Val]
  def preType: Tx[Type]     = Tx.None[Type]
  def postType: Tx[Type]    = Tx.None[Type]
}

trait PassCompanion {
  def apply(ctx: Ctx): Pass
  def depends: Seq[Global] = Seq()
}
