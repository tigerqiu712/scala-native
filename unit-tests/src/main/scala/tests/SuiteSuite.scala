package tests

object SuiteSuite extends Suite {
  test("assert true") {
    assert(true)
  }

  test("assert not false") {
    assertNot(false)
  }

  test("assert throws") {
    assertThrows {
      throw new Exception
    }
  }
}
