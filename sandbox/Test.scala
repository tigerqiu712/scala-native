import scalanative.native._, stdio._

object Test {
  def main(args: Array[String]): Unit = {
    val sum = (1 to 100).toList.sum
    printf(c"sum is %d", sum)
  }
}
