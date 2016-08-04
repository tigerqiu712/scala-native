package tests

import java.lang.System.exit

object Main {
  val suites = Seq[Suite](
    SuiteSuite
  )

  def main(args: Array[String]): Unit =
    if (!suites.forall(_.run)) exit(1) else exit(0)
}
