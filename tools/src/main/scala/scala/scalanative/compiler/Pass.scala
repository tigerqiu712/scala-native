package scala.scalanative
package compiler

import scala.collection.mutable
import nir._

trait Pass {
  import Pass._

  def preInject: OnInject  = Hook.None
  def postInject: OnInject = Hook.None
  def preDefn: OnDefn      = Hook.None
  def postDefn: OnDefn     = Hook.None
  def preBlock: OnBlock    = Hook.None
  def postBlock: OnBlock   = Hook.None
  def preInst: OnInst      = Hook.None
  def postInst: OnInst     = Hook.None
  def preCf: OnCf          = Hook.None
  def postCf: OnCf         = Hook.None
  def preVal: OnVal        = Hook.None
  def postVal: OnVal       = Hook.None
  def preType: OnType      = Hook.None
  def postType: OnType     = Hook.None
}

object Pass {
  type OnInject = Hook[Unit, Seq[Defn]]
  type OnDefn   = Hook[Defn, Seq[Defn]]
  type OnBlock  = Hook[Block, Seq[Block]]
  type OnInst   = Hook[Inst, Seq[Inst]]
  type OnCf     = Hook[Cf, Cf]
  type OnVal    = Hook[Val, Val]
  type OnType   = Hook[Type, Type]
}

trait PassCompanion {
  def apply(ctx: Ctx): Pass
  def depends: Seq[Global] = Seq()
}
