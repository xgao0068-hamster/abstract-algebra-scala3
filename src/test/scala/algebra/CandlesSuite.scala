package algebra

class CandlesSuite extends munit.FunSuite:
  val minute = 60_000L
  val ticks = Candles.simulate(3_000)
  val bars = Candles.simulate(30).map(Bar.of)

  test("K 线半群满足结合律，并且与到达顺序无关"):
    for a <- bars.take(8); b <- bars.take(8); c <- bars.take(8) do
      assertEquals((a |+| b) |+| c, a |+| (b |+| c))
      assertEquals(a |+| b, b |+| a)

  test("同一毫秒的多笔成交：结合律和交换律仍然成立"):
    val same = List(Tick(1000, 10.0, 1), Tick(1000, 12.0, 2), Tick(1000, 11.0, 3), Tick(999, 9.0, 1), Tick(1001, 9.5, 1)).map(Bar.of)
    for a <- same; b <- same; c <- same do
      assertEquals((a |+| b) |+| c, a |+| (b |+| c))
      assertEquals(a |+| b, b |+| a)
    for perm <- same.take(3).permutations do
      assertEquals(perm.reduce(_ |+| _), same.take(3).reduce(_ |+| _))

  test("Option[Bar] 是幺半群：None 是单位元"):
    val b = Option(bars.head)
    assertEquals(b |+| None, b)
    assertEquals(Option.empty[Bar] |+| b, b)

  test("一根 K 线的 OHLCV 与直接从 tick 算出的一致"):
    val bar = Monoid.combineAll(ticks.take(100).map(t => Option(Bar.of(t)))).get
    val sample = ticks.take(100)
    assertEquals(bar.open, sample.head.price)
    assertEquals(bar.close, sample.last.price)
    assertEquals(bar.high, sample.map(_.price).max)
    assertEquals(bar.low, sample.map(_.price).min)
    assertEquals(bar.volume, sample.map(_.size).sum)
    assertEquals(bar.vwap, sample.map(t => t.price * t.size).sum / sample.map(_.size).sum)

  test("并行聚合与串行聚合逐位相同"):
    val serial = Candles.aggregate(ticks, minute)
    for chunks <- List(1, 3, 8, 64) do assertEquals(Candles.aggregateParallel(ticks, minute, chunks), serial)

  test("乱序到达的 tick 聚合结果相同"):
    assertEquals(Candles.aggregate(scala.util.Random(7).shuffle(ticks), minute), Candles.aggregate(ticks, minute))

  test("1 分钟线重采样成 5 分钟线 == 直接从 tick 聚合 5 分钟线"):
    assertEquals(Candles.resample(Candles.aggregate(ticks, minute), 5 * minute), Candles.aggregate(ticks, 5 * minute))

  test("滑动窗口与朴素重算结果一致"):
    val xs = ticks.take(200).map(t => Option(Bar.of(t)))
    for w <- List(1, 2, 7, 50) do
      val naive = xs.indices.map(i => Monoid.combineAll(xs.slice(i - w + 1, i + 1)))
      assertEquals(SlidingWindow.rolling(xs, w), naive)

  test("滑动窗口对不满足交换律的幺半群也保持顺序"):
    val words = List("a", "b", "c", "d", "e")
    assertEquals(SlidingWindow.rolling(words, 3), List("a", "ab", "abc", "bcd", "cde"))

  def close(a: Stats, b: Stats): Unit =
    assertEquals(a.count, b.count)
    assertEqualsDouble(a.mean, b.mean, 1e-9)
    assertEqualsDouble(a.m2, b.m2, 1e-6)
    assertEquals(a.min, b.min)
    assertEquals(a.max, b.max)

  test("Stats 幺半群：单位元精确，结合律在浮点容差内成立"):
    val xs = ticks.take(300).map(t => Stats.of(t.price))
    val empty = summon[Monoid[Stats]].empty
    assertEquals(xs.head |+| empty, xs.head)
    assertEquals(empty |+| xs.head, xs.head)
    val (a, b, c) = (Monoid.combineAll(xs.take(100)), Monoid.combineAll(xs.slice(100, 220)), Monoid.combineAll(xs.drop(220)))
    close((a |+| b) |+| c, a |+| (b |+| c))

  test("Stats 合并出的方差与两遍扫描的教科书公式一致"):
    val prices = ticks.map(_.price)
    val mean = prices.sum / prices.size
    val variance = prices.map(p => (p - mean) * (p - mean)).sum / (prices.size - 1)
    val merged = prices.grouped(97).map(g => Monoid.combineAll(g.map(Stats.of))).foldLeft(summon[Monoid[Stats]].empty)(_ |+| _)
    assertEqualsDouble(merged.mean, mean, 1e-9)
    assertEqualsDouble(merged.variance, variance, 1e-6)
