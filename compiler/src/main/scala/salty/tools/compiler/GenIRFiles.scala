package salty.tools
package compiler

import java.io._
import java.nio._
import java.nio.channels._
import scala.tools.nsc._
import scala.tools.nsc.io.AbstractFile
import salty.ir
import salty.ir.Serializers._
import salty.ir.Deserializers._
import salty.util.serialize

trait GenIRFiles extends SubComponent  {
  import global._

  def genIRFile(cunit: CompilationUnit, sym: Symbol, stat: ir.Stat): String = {
    val outfile = getFileFor(cunit, sym, ".salty").file
    val buffer = serialize(stat)
    val channel = (new FileOutputStream(outfile)).getChannel()
    buffer.flip
    try channel.write(buffer)
    finally channel.close
    outfile.getCanonicalPath
  }

  def loadIRFile(path: String): ir.Stat = {
    val file = new RandomAccessFile(path, "r")
    val channel = file.getChannel
    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    buffer.load
    val res = buffer.getStat
    buffer.clear
    channel.close
    file.close
    res
  }

  private def getFileFor(cunit: CompilationUnit, sym: Symbol, suffix: String) = {
    val baseDir: AbstractFile =
      settings.outputDirs.outputDirFor(cunit.source.file)

    val pathParts = sym.fullName.split("[./]")
    val dir = (baseDir /: pathParts.init)(_.subdirectoryNamed(_))

    var filename = pathParts.last
    if (sym.isModuleClass && !sym.isImplClass)
      filename = filename + nme.MODULE_SUFFIX_STRING

    dir fileNamed (filename + suffix)
  }
}