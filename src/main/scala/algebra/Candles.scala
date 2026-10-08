package algebra

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** 一笔成交：时间（毫秒）、价格、数量。 */
final case class Tick(time: Long, price: Double, size: Double)

/** 一根 K 线（OHLCV）。open/close 带上各自的时间，合并时才知道谁先谁后。
  * notional = Σ price × size，用来算 VWAP。
  */
final case class Bar(
    openTime: Long,
    open: Double,
    high: Double,
    low: Double,
    closeTime: Long,
    close: Double,
    volume: Double,
    notional: Double
):
  /** 成交量加权平均价。 */
  def vwap: Double = notional / volume

object Bar:
  def of(t: Tick): Bar = Bar(t.time, t.price, t.price, t.price, t.time, t.price, t.size, t.price * t.size)

  /** K 线是半群但不是幺半群：没有一根“空 K 线”能当单位元（open 填什么都不对）。
    * 需要单位元时用 Option[Bar]，见 Algebra.scala 里的 Monoid[Option[A]]。
    *
    * open 取 (时间, 价格) 字典序最小的那一笔，close 取字典序最大的那一笔。
    * 字典序是全序，全序上的 min/max 既结合又交换；high/low 是 max/min，volume/notional 是加法。
    * 所以乱序到达的数据合并结果也一样。
    *
    * 为什么要把价格也放进比较键？同一毫秒里常有多笔成交，只比时间的话，
    * 并列时“取左边/取右边”就依赖到达顺序，交换律会被破坏。用价格打破平局是任意的，
    * 但它是确定的；真实行情里应该用交易所给的成交序号（trade id）当第二键。
    */
  given Semigroup[Bar] with
    extension (x: Bar)
      def |+|(y: Bar): Bar =
        val yFirst = y.openTime < x.openTime || (y.openTime == x.openTime && y.open < x.open)
        val yLast = y.closeTime > x.closeTime || (y.closeTime == x.closeTime && y.close > x.close)
        val first = if yFirst then y else x
        val last = if yLast then y else x
        Bar(
          first.openTime,
          first.open,
          math.max(x.high, y.high),
          math.min(x.low, y.low),
          last.closeTime,
          last.close,
          x.volume + y.volume,
          x.notional + y.notional
        )

/** 在线统计量：个数、均值、M2（离差平方和）、最小值、最大值。
  *
  * combine 用的是 Chan, Golub & LeVeque (1979) 的并行方差公式：
  * 两段数据各自的 (n, mean, M2) 就足以算出整体的 (n, mean, M2)，不需要回看原始数据。
  * 这个公式在数学上满足结合律；在浮点数上只是近似满足，测试里用容差比较。
  */
final case class Stats(count: Long, mean: Double, m2: Double, min: Double, max: Double):
  /** 样本方差（除以 n - 1）。 */
  def variance: Double = if count < 2 then Double.NaN else m2 / (count - 1)
  def stddev: Double = math.sqrt(variance)

object Stats:
  def of(x: Double): Stats = Stats(1, x, 0.0, x, x)

  given Monoid[Stats] with
    def empty: Stats = Stats(0, 0.0, 0.0, Double.PositiveInfinity, Double.NegativeInfinity)
    extension (a: Stats)
      def |+|(b: Stats): Stats =
        if a.count == 0 then b
        else if b.count == 0 then a
        else
          val n = a.count + b.count
          val delta = b.mean - a.mean
          Stats(
            n,
            a.mean + delta * b.count / n,
            a.m2 + b.m2 + delta * delta * a.count * b.count / n,
            math.min(a.min, b.min),
            math.max(a.max, b.max)
          )

/** 滑动窗口聚合：任意幺半群上的“两栈队列”，每次推入/弹出均摊 O(1)。
  *
  * 朴素做法每移动一次窗口就重新折叠 w 个元素，是 O(w)。
  * 有了结合律，可以把窗口拆成两段分别维护聚合值：
  *   - back：新进来的元素（最新的在表头）以及它们的聚合 backAgg
  *   - front：旧元素的“后缀聚合”，表头是窗口里最老元素到 front 末尾的聚合
  * 弹出时如果 front 空了，就把 back 一次性倒进 front，顺便算好后缀聚合。
  * 全程只用 |+|，从不需要“减去”一个元素，所以 max/min/K 线这种没有逆元的统计也能滑动。
  */
final case class SlidingWindow[A](front: List[A], back: List[A], backAgg: A, size: Int):
  def push(x: A)(using Monoid[A]): SlidingWindow[A] =
    copy(back = x :: back, backAgg = backAgg |+| x, size = size + 1)

  def pop(using M: Monoid[A]): SlidingWindow[A] =
    require(size > 0, "空窗口不能弹出")
    front match
      case _ :: rest => copy(front = rest, size = size - 1)
      case Nil =>
        // back 的表头是最新元素，从新到旧累积后缀聚合，最后压入的（最老的）落在表头。
        val suffixes = back.foldLeft((M.empty, List.empty[A])) { case ((acc, fs), x) =>
          val agg = x |+| acc
          (agg, agg :: fs)
        }._2
        SlidingWindow(suffixes.tail, Nil, M.empty, size - 1)

  def aggregate(using M: Monoid[A]): A = front.headOption.getOrElse(M.empty) |+| backAgg

object SlidingWindow:
  def empty[A](using M: Monoid[A]): SlidingWindow[A] = SlidingWindow(Nil, Nil, M.empty, 0)

  /** 对每个位置 i，给出 xs(i - w + 1 .. i) 的聚合。 */
  def rolling[A: Monoid](xs: Seq[A], w: Int): Seq[A] =
    require(w > 0)
    xs.scanLeft(empty[A]) { (win, x) =>
      val pushed = win.push(x)
      if pushed.size > w then pushed.pop else pushed
    }.tail.map(_.aggregate)

object Candles:
  /** tick 所在 K 线的起始时间。 */
  def bucket(time: Long, intervalMs: Long): Long = time - Math.floorMod(time, intervalMs)

  /** 把 tick 流聚合成 K 线：每个 tick 变成 Map(桶 -> 单根 K 线)，再用 Map 幺半群一路合并。 */
  def aggregate(ticks: Iterable[Tick], intervalMs: Long): Map[Long, Bar] =
    Monoid.combineAll(ticks.iterator.map(t => Map(bucket(t.time, intervalMs) -> Bar.of(t))))

  /** 并行版：切成若干块，各块独立聚合（可以在不同线程甚至不同机器上），最后合并。
    * 结果和串行版完全相同，这就是结合律给的保证。
    */
  def aggregateParallel(ticks: IndexedSeq[Tick], intervalMs: Long, chunks: Int): Map[Long, Bar] =
    val chunkSize = math.max(1, (ticks.length + chunks - 1) / chunks)
    val partials = ticks.grouped(chunkSize).toList.map(c => Future(aggregate(c, intervalMs)))
    Monoid.combineAll(Await.result(Future.sequence(partials), 1.minute))

  /** 重采样：1 分钟线 → 5 分钟线。不需要回看 tick，直接合并 K 线即可。 */
  def resample(bars: Map[Long, Bar], intervalMs: Long): Map[Long, Bar] =
    Monoid.combineAll(bars.iterator.map((t, b) => Map(bucket(t, intervalMs) -> b)))

  /** 一串模拟成交：价格是步长 0.25 的随机游走（像股指期货的最小变动价位），数量是整数。
    * 0.25 和整数在二进制浮点里都是精确的，所以不同合并顺序的结果可以逐位比较。
    */
  def simulate(n: Int, seed: Long = 42, start: Long = 0L, startPrice: Double = 5000.0): Vector[Tick] =
    val rnd = scala.util.Random(seed)
    Iterator
      .iterate(Tick(start, startPrice, 1)) { t =>
        Tick(t.time + 1 + rnd.nextInt(2000), t.price + 0.25 * (rnd.nextInt(5) - 2), 1 + rnd.nextInt(10))
      }
      .take(n)
      .toVector

  def demo(): Unit =
    println("== Monoid → K 线聚合 ==")
    val minute = 60_000L
    val ticks = simulate(2_000)
    val bars = aggregate(ticks, minute)
    println(s"${ticks.size} 笔成交 → ${bars.size} 根 1 分钟 K 线")
    println(s"并行 8 块聚合与串行结果相同: ${aggregateParallel(ticks, minute, 8) == bars}")
    val five = resample(bars, 5 * minute)
    println(s"1 分钟线重采样成 5 分钟线 与直接从 tick 聚合相同: ${five == aggregate(ticks, 5 * minute)}")
    val sorted = bars.toVector.sortBy(_._1).map(_._2)
    val rolling = SlidingWindow.rolling(sorted.map(Option(_)), 5)
    val last = rolling.last.get
    println(f"最近 5 分钟: 高 ${last.high}%.2f 低 ${last.low}%.2f VWAP ${last.vwap}%.2f 成交量 ${last.volume}%.0f")
    val returns = sorted.zip(sorted.tail).map((a, b) => math.log(b.close / a.close))
    val stats = Monoid.combineAll(returns.map(Stats.of))
    println(f"1 分钟对数收益率: n=${stats.count} 均值=${stats.mean}%.2e 标准差=${stats.stddev}%.2e")
