package tests

import scala.collection.mutable

final case object AssertionFailed extends Exception

final case class Test(name: String, run: () => Boolean)

abstract class Suite {
  private val tests = new mutable.UnrolledBuffer[Test]

  def assert(cond: Boolean): Unit =
    if (!cond) throw AssertionFailed else ()

  def assertNot(cond: Boolean): Unit =
    if (cond) throw AssertionFailed else ()

  def assertThrows(f: => Unit): Unit =
    try {
      f
      throw AssertionFailed
    } catch {
      case _: Exception => ()
    }

  def test(name: String)(body: => Unit): Unit =
    tests += Test(name, { () =>
      try {
        body
        true
      } catch {
        case _: Exception => false
      }
    })

  def run(): Boolean = {
    println("* " + this.getClass.getName)
    tests.forall { test =>
      val res = test.run()
      println((if (res) "  [ok] " else "  [fail] ") + test.name)
      res
    }
  }
}
