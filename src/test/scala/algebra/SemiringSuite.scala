package algebra

class SemiringSuite extends munit.FunSuite:

  /** 逐条检查半环定律。样本都选二进制下精确的数，浮点运算不会引入舍入误差。 */
  def checkLaws[A](name: String, samples: List[A])(using S: Semiring[A]): Unit =
    test(s"$name 满足半环定律"):
      for a <- samples do
        assertEquals(a ⊕ S.zero, a, "zero 是 ⊕ 的单位元")
        assertEquals(a ⊗ S.one, a, "one 是 ⊗ 的右单位元")
        assertEquals(S.one ⊗ a, a, "one 是 ⊗ 的左单位元")
        assertEquals(a ⊗ S.zero, S.zero, "zero 是零化元")
        assertEquals(S.zero ⊗ a, S.zero, "zero 是零化元")
        for b <- samples do
          assertEquals(a ⊕ b, b ⊕ a, "⊕ 交换")
          for c <- samples do
            assertEquals((a ⊕ b) ⊕ c, a ⊕ (b ⊕ c), "⊕ 结合")
            assertEquals((a ⊗ b) ⊗ c, a ⊗ (b ⊗ c), "⊗ 结合")
            assertEquals(a ⊗ (b ⊕ c), (a ⊗ b) ⊕ (a ⊗ c), "左分配")
            assertEquals((a ⊕ b) ⊗ c, (a ⊗ c) ⊕ (b ⊗ c), "右分配")

  checkLaws("(min, +)", List(-2.0, 0.0, 1.5, 3.0))(using Semiring.minPlus)
  checkLaws("(max, ×)", List(0.0, 0.5, 1.0, 2.0, 4.0))(using Semiring.maxTimes)
  checkLaws("(max, min)", List(0.0, 1.0, 2.5, Double.PositiveInfinity))(using Semiring.maxMin)
  checkLaws("(∨, ∧)", List(false, true))(using Semiring.boolean)

  val inf = Double.PositiveInfinity
  // 0 → 1 (4), 0 → 2 (1), 2 → 1 (2), 1 → 3 (1)
  val graph: Matrix[Double] = Vector(
    Vector(inf, 4.0, 1.0, inf),
    Vector(inf, inf, inf, 1.0),
    Vector(inf, 2.0, inf, inf),
    Vector(inf, inf, inf, inf)
  )

  test("(min, +) 上的闭包就是全源最短路"):
    val d = Matrix.upTo(graph, graph.length - 1)(using Semiring.minPlus)
    assertEquals(d(0), Vector(0.0, 3.0, 1.0, 4.0))
    assertEquals(d(3)(0), inf)

  test("同一张图换成布尔半环就是可达性"):
    val reach = Matrix.upTo(Matrix.map(graph)(_ < inf), 3)(using Semiring.boolean)
    assertEquals(reach(0), Vector(true, true, true, true))
    assertEquals(reach(3), Vector(false, false, false, true))

  test("矩阵快速幂与逐次相乘结果一致"):
    given Semiring[Double] = Semiring.minPlus
    val slow = (1 to 3).foldLeft(Matrix.identity[Double](4))((m, _) => Matrix.mul(m, graph))
    assertEquals(Matrix.power(graph, 3), slow)

  test("(max, min) 瓶颈路径：最宽的那条管道"):
    // 0 → 1 容量 5，1 → 2 容量 3；0 → 2 直连容量 2。最宽路线走 0 → 1 → 2，容量 3。
    val cap: Matrix[Double] = Vector(
      Vector(0.0, 5.0, 2.0),
      Vector(0.0, 0.0, 3.0),
      Vector(0.0, 0.0, 0.0)
    )
    assertEquals(Matrix.upTo(cap, 2)(using Semiring.maxMin)(0)(2), 3.0)
