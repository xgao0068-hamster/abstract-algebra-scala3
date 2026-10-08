package algebra

/** 一条报价：1 单位 from 可以换到 rate 单位 to。 */
final case class Quote(from: String, to: String, rate: Double)

/** 一个套利环：path 首尾相同，profit 是沿途汇率之积（> 1 就是赚钱）。 */
final case class ArbitrageCycle(path: List[String], profit: Double)

/** 半环的应用：汇率图上的套利检测。
  *
  * 同一张汇率图，三种半环视角：
  *   1. (max, ×)：A^k 的对角线 = “恰好 k 次兑换回到自己”的最佳比率，> 1 就是套利。
  *   2. 取 w = -log(rate) 后换到 (min, +)：× 变 +，max 变 min，套利变成“负权环”。
  *   3. 在 (min, +) 上跑 Bellman–Ford，不仅知道有没有套利，还能把环找出来。
  */
object Arbitrage:

  /** 汇率矩阵（(max, ×) 半环）：没有报价的位置是 zero = 0，对角线也是 0（不允许原地“兑换”）。 */
  def rateMatrix(currencies: Vector[String], quotes: Seq[Quote]): Matrix[Double] =
    val idx = currencies.zipWithIndex.toMap
    val n = currencies.length
    quotes.foldLeft(Vector.fill(n, n)(Semiring.maxTimes.zero)): (m, q) =>
      val (i, j) = (idx(q.from), idx(q.to))
      m.updated(i, m(i).updated(j, math.max(m(i)(j), q.rate)))

  /** 恰好 k 次兑换后回到自己的最佳比率。 */
  def bestRoundTrip(currencies: Vector[String], quotes: Seq[Quote], k: Int): Map[String, Double] =
    val ak = Matrix.power(rateMatrix(currencies, quotes), k)(using Semiring.maxTimes)
    currencies.zipWithIndex.map((c, i) => c -> ak(i)(i)).toMap

  /** -log 是从 (max, ×) 到 (min, +) 的半环同态：
    * -log(a·b) = -log a + -log b，-log(max(a, b)) = min(-log a, -log b)，
    * 并且 zero ↦ zero（0 ↦ +∞）、one ↦ one（1 ↦ 0）。
    */
  def negLog(rate: Double): Double =
    if rate <= 0 then Double.PositiveInfinity else -math.log(rate)

  /** Bellman–Ford（(min, +) 半环上的松弛）找出一个负权环，也就是一个套利环。
    *
    * 松弛那一行 `dist(u) ⊗ w ⊕ dist(v)` 正是半环矩阵乘法里的一项；
    * “变得更好”用的是幂等半环自带的序：a ≤ b 当且仅当 a ⊕ b == a。
    * 这里加了一个很小的 eps，避免浮点舍入被误判成套利。
    */
  def findCycle(currencies: Vector[String], quotes: Seq[Quote], eps: Double = 1e-12): Option[ArbitrageCycle] =
    given Semiring[Double] = Semiring.minPlus
    val n = currencies.length
    val rates = rateMatrix(currencies, quotes)
    val w = Matrix.map(rates)(negLog)
    // 虚拟源点到每个点距离都是 one = 0，这样能找到图里任意位置的负环。
    val dist = Array.fill(n)(Semiring[Double].one)
    val pred = Array.fill(n)(-1)
    var lastRelaxed = -1
    for _ <- 0 until n do
      lastRelaxed = -1
      for u <- 0 until n; v <- 0 until n if w(u)(v) < Double.PositiveInfinity do
        val candidate = dist(u) ⊗ w(u)(v)
        if (candidate ⊕ dist(v)) < dist(v) - eps then
          dist(v) = candidate
          pred(v) = u
          lastRelaxed = v
    // 第 n 轮还能松弛，说明有负环；沿前驱走 n 步一定落在环上。
    Option.when(lastRelaxed >= 0):
      var x = lastRelaxed
      for _ <- 0 until n do x = pred(x)
      val cycle = Iterator.iterate(pred(x))(pred).takeWhile(_ != x).toList.reverse
      val nodes = (x :: cycle) :+ x // 正向：x → … → x
      val names = nodes.map(currencies)
      val profit = nodes.zip(nodes.tail).map((a, b) => rates(a)(b)).product
      ArbitrageCycle(names, profit)

  // ---------- 示例数据 ----------

  val currencies: Vector[String] = Vector("USD", "EUR", "GBP", "JPY", "BTC")

  /** 以美元计的中间价。 */
  val midUsd: Map[String, Double] =
    Map("USD" -> 1.0, "EUR" -> 1.087, "GBP" -> 1.27, "JPY" -> 0.0067, "BTC" -> 60000.0)

  /** 由中间价生成双向报价，每笔兑换损失 spread（做市商点差）。
    * 所有报价都来自同一组中间价时，任何环的乘积都是 (1 - spread)^k < 1，没有套利。
    * `mispriced` 可以把某一对货币的“交叉汇率”改错，人为制造三角套利。
    */
  def quotes(spread: Double = 0.001, mispriced: Map[(String, String), Double] = Map.empty): Seq[Quote] =
    for
      a <- currencies
      b <- currencies if a != b
    yield
      val mid = mispriced.get((a, b)).orElse(mispriced.get((b, a)).map(1 / _)).getOrElse(midUsd(a) / midUsd(b))
      Quote(a, b, mid * (1 - spread))

  /** EUR/GBP 的公允交叉价是 1.087 / 1.27 ≈ 0.856，这里报成 0.87。 */
  val stale: Seq[Quote] = quotes(mispriced = Map(("EUR", "GBP") -> 0.87))

  def demo(): Unit =
    println("== 半环 → 套利检测 ==")
    val fair = quotes()
    println(s"公允报价下的套利环: ${findCycle(currencies, fair)}")
    println(s"EUR/GBP 报错后的套利环: ${findCycle(currencies, stale)}")
    for k <- 2 to 3 do
      val best = bestRoundTrip(currencies, stale, k)
      println(f"恰好 $k 次兑换回到 USD 的最佳比率: ${best("USD")}%.6f")
