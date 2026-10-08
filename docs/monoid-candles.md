# Monoid → K 线聚合

> 代码：[`Candles.scala`](../src/main/scala/algebra/Candles.scala)，通用实例在 [`Algebra.scala`](../src/main/scala/algebra/Algebra.scala)
> 测试：[`CandlesSuite.scala`](../src/test/scala/algebra/CandlesSuite.scala)

行情是一条永不结束的流。我们想要的统计量（K 线、VWAP、方差、滚动高低点）有一个共同点：**两段数据的统计量可以直接合并成整段的统计量**，不需要回看原始数据。这句话翻译成代数就是：它们是半群或幺半群。

结合律带来三样东西：

1. **并行**：切成任意块分别算，最后合并，结果不变。
2. **增量 / 重采样**：1 分钟线可以直接合并成 5 分钟线。
3. **滑动窗口**：不需要逆元也能 O(1) 均摊地滑动。

## 1. K 线是半群，不是幺半群

```scala
final case class Bar(openTime, open, high, low, closeTime, close, volume, notional)
```

| 字段 | 合并方式 | 代数结构 |
|---|---|---|
| open | (时间, 价格) 字典序最小的那笔 | 全序上的 min |
| close | (时间, 价格) 字典序最大的那笔 | 全序上的 max |
| high / low | max / min | 半格 |
| volume / notional | + | 交换幺半群 |

积（product）的每个分量都是半群，所以整体是半群。VWAP = notional / volume 不能直接合并，但它是两个可合并量的**商**，这是设计流式统计时最常用的技巧：存可合并的“充分统计量”，查询时再算派生值。

为什么没有单位元？“空 K 线”的 open 填什么都不对。解决办法是 `Option`：

```scala
given [A: Semigroup]: Monoid[Option[A]]  // None 就是 empty
```

这是一个通用构造：任何半群补上一个新元素当单位元就变成幺半群。

open/close 看的是时间戳而不是位置，所以 K 线合并还满足**交换律**：乱序到达的 tick 聚合结果一样（测试 `乱序到达的 tick 聚合结果相同`）。分布式系统里消息乱序是常态，交换律让你不用先排序。

注意同一毫秒里可能有多笔成交。如果只按时间比较、并列时“取左边”，那么交换两笔同时间的成交会改变 open，交换律就不成立了。所以比较键是 (时间, 价格)：用价格打破平局是任意的，但它确定、与顺序无关。真实系统里用交易所的成交序号当第二键更合适（练习 1）。

## 2. 分桶：Map 也是幺半群

```scala
given [K, V: Semigroup]: Monoid[Map[K, V]]  // 同键的值用 V 的半群合并

def aggregate(ticks: Iterable[Tick], intervalMs: Long): Map[Long, Bar] =
  Monoid.combineAll(ticks.iterator.map(t => Map(bucket(t.time, intervalMs) -> Bar.of(t))))
```

每个 tick 变成只有一个键的 Map，然后一路 `|+|`。并行版本 `aggregateParallel` 把 tick 切块交给 `Future`，再用同一个 Map 幺半群合并。测试里切成 1、3、8、64 块，结果**逐位相同**。

> 为什么能逐位相同？示例价格以 0.25 为步长、数量是整数，这些数在二进制浮点里是精确的。真实价格（例如 0.01 步长）合并顺序不同时 notional 可能差最后一位，生产系统通常用整数“最小价位数”或 `BigDecimal` 存价格。

重采样只是换一个桶函数再合并一次：

```scala
Candles.resample(oneMinuteBars, 5 * minute) == Candles.aggregate(ticks, 5 * minute)  // true
```

## 3. 方差：Chan 的并行公式就是一个 combine

```scala
final case class Stats(count: Long, mean: Double, m2: Double, min: Double, max: Double)
```

两段数据 (n_a, μ_a, M2_a)、(n_b, μ_b, M2_b) 合并：

- n = n_a + n_b
- δ = μ_b − μ_a
- μ = μ_a + δ · n_b / n
- M2 = M2_a + M2_b + δ² · n_a · n_b / n

数学上严格结合，浮点上近似结合，所以测试用容差比较。这也提醒我们：**定律是关于数学对象的，代码里的 Double 只是近似**，测试要写对比较方式。

## 4. 滑动窗口：没有逆元也能滑

“最近 20 根 K 线的最高价”怎么增量维护？如果统计量是群（例如求和），可以加新的、减旧的。但 max 没有逆元，K 线也没有。

`SlidingWindow` 用两个栈实现队列：

- `back`：新进来的元素，和它们的聚合 `backAgg`
- `front`：旧元素的**后缀聚合**，表头是窗口里最老的元素到 front 末尾的聚合

弹出时如果 front 空了，就把 back 整个倒过去，顺便算出后缀聚合。每个元素最多被倒一次，所以推入和弹出都是均摊 O(1)，全程只用 `|+|`。

```scala
SlidingWindow.rolling(List("a", "b", "c", "d", "e"), 3)
// List(a, ab, abc, bcd, cde)  —— 字符串拼接不满足交换律，顺序依然正确
```

## 5. 在 REPL 里玩

```scala
sbt console
scala> import algebra.*
scala> val ticks = Candles.simulate(2000)
scala> val bars = Candles.aggregate(ticks, 60_000)
scala> bars.toVector.sortBy(_._1).take(3).foreach(println)
scala> Monoid.combineAll(ticks.map(t => Stats.of(t.price))).stddev
```

## 练习

1. 给 `Tick` 加一个成交序号 `seq: Long`，用 (时间, 序号) 代替 (时间, 价格) 打破平局；再给 `Bar` 加一个成交笔数 `trades: Long` 字段，确认定律测试仍然通过。
2. 写一个 `Monoid[TopK]`，维护成交量最大的 k 笔 tick。它满足交换律吗？
3. 用 `SlidingWindow` 和 `Stats` 实现 20 根 K 线的滚动波动率，与朴素重算对比。
4. 布林带需要滚动均值和标准差，用本章的东西组合出来。
5. 为什么“中位数”不能写成这样的幺半群？查一下 t-digest 或 KLL 草图，它们是怎么用近似换来可合并性的？
