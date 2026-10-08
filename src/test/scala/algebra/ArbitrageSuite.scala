package algebra

import Arbitrage.*

class ArbitrageSuite extends munit.FunSuite:

  test("公允报价下没有套利"):
    assertEquals(findCycle(currencies, quotes()), None)
    for k <- 1 to 5 do assert(bestRoundTrip(currencies, quotes(), k).values.forall(_ < 1.0))

  test("EUR/GBP 报错后能找到三角套利"):
    val cycle = findCycle(currencies, stale).getOrElse(fail("应该找到套利环"))
    assertEquals(cycle.path.head, cycle.path.last)
    assert(cycle.path.contains("EUR") && cycle.path.contains("GBP"))
    assert(cycle.profit > 1.0, s"利润因子 ${cycle.profit} 应该 > 1")

  test("报错只影响交叉汇率：两次兑换来回仍然亏点差，三次兑换才赚"):
    assert(bestRoundTrip(currencies, stale, 2)("USD") < 1.0)
    val expected = (1 / 1.087) * 0.87 * 1.27 * math.pow(0.999, 3)
    assertEqualsDouble(bestRoundTrip(currencies, stale, 3)("USD"), expected, 1e-12)

  test("-log 把 (max, ×) 的结果搬到 (min, +)"):
    val rates = rateMatrix(currencies, stale)
    val viaMaxTimes = Matrix.power(rates, 3)(using Semiring.maxTimes)
    val viaMinPlus = Matrix.power(Matrix.map(rates)(negLog), 3)(using Semiring.minPlus)
    for i <- currencies.indices; j <- currencies.indices do
      assertEqualsDouble(viaMinPlus(i)(j), negLog(viaMaxTimes(i)(j)), 1e-9)
