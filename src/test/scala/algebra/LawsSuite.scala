package algebra

class LawsSuite extends munit.FunSuite:
  val samples = List(-3, 0, 1, 7, 42)

  test("Int 加法满足结合律"):
    for a <- samples; b <- samples; c <- samples do
      assertEquals((a |+| b) |+| c, a |+| (b |+| c))

  test("Int 加法有单位元和逆元"):
    val g = summon[Group[Int]]
    for a <- samples do
      assertEquals(a |+| g.empty, a)
      assertEquals(a |+| a.inverse, g.empty)

  test("combineAll 处理空序列时返回单位元"):
    assertEquals(Monoid.combineAll(List.empty[String]), "")
