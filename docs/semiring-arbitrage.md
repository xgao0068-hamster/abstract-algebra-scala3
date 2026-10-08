# 半环 → 套利检测

> 代码：[`Semiring.scala`](../src/main/scala/algebra/Semiring.scala)、[`Arbitrage.scala`](../src/main/scala/algebra/Arbitrage.scala)
> 测试：[`SemiringSuite.scala`](../src/test/scala/algebra/SemiringSuite.scala)、[`ArbitrageSuite.scala`](../src/test/scala/algebra/ArbitrageSuite.scala)

## 1. 一个问题，四种答案

给一张有向图，每条边一个权重。问“从 i 到 j 怎么样”，答案取决于你怎么**串联**一条路径上的边、怎么**并联**多条路径：

| 半环 | 串联 ⊗ | 并联 ⊕ | zero | one | 回答的问题 |
|---|---|---|---|---|---|
| `minPlus` | `+` | `min` | +∞ | 0 | 最短路 |
| `maxTimes` | `×` | `max` | 0 | 1 | 最佳兑换比率 / 最可靠路径 |
| `maxMin` | `min` | `max` | 0 | +∞ | 最大可通过量（瓶颈） |
| `boolean` | `∧` | `∨` | false | true | 能不能到 |

只要 (⊕, ⊗) 满足半环定律，**同一份矩阵乘法代码**就能回答以上所有问题：

```scala
// (A ⊗ B)(i)(j) = ⊕_k A(i)(k) ⊗ B(k)(j)
def mul[A](a: Matrix[A], b: Matrix[A])(using S: Semiring[A]): Matrix[A]
```

- `Matrix.power(a, k)`：恰好走 k 步的所有路径汇总（快速幂只依赖 ⊗ 的结合律）。
- `Matrix.upTo(a, k)`：至多走 k 步，即 I ⊕ A ⊕ … ⊕ A^k。图里没有“越绕越好”的环时，k = n − 1 就是闭包 A*。

为什么需要每条定律？
- **⊗ 结合**：路径怎么分段相乘都一样，快速幂才成立。
- **分配律**：先汇总再延长 == 先延长再汇总，这就是动态规划（Bellman–Ford、Floyd–Warshall）敢“合并子问题”的原因。
- **zero 零化**：没有边的地方串联上任何东西仍然没有路。

## 2. 汇率图里的套利

把货币当节点，报价 `1 from = rate to` 当边。沿一个环换一圈，手里的钱乘以沿途汇率之积；**乘积 > 1 就是套利**。

这正是 `maxTimes` 半环：`A^k` 的对角线就是“恰好 k 次兑换回到自己”的最佳比率。

```scala
sbt console
scala> import algebra.*, Arbitrage.*
scala> bestRoundTrip(currencies, stale, 2)("USD")
val res0: Double = 0.998001        // 来回换一次，亏两次点差
scala> bestRoundTrip(currencies, stale, 3)("USD")
val res1: Double = 1.013420987...  // 三角兑换，赚 1.34%
```

示例数据里所有报价都来自同一组中间价，再扣 0.1% 点差，所以本来没有任何套利；`stale` 把 EUR→GBP 的交叉价从公允的 ≈0.856 报成了 0.87，于是出现了**三角套利**，但两两来回仍然亏钱。

## 3. 取对数：从 (max, ×) 到 (min, +)

`-log` 是一个**半环同态**：

- −log(a·b) = −log a + (−log b)　　（⊗ ↦ ⊗）
- −log(max(a, b)) = min(−log a, −log b)　　（⊕ ↦ ⊕）
- 0 ↦ +∞，1 ↦ 0　　（zero ↦ zero，one ↦ one）

同态保持一切用 ⊕、⊗ 写出来的计算，所以 “乘积 > 1 的环” 在 (min, +) 里就是 “**权重和 < 0 的环**”，也就是负权环。测试 `-log 把 (max, ×) 的结果搬到 (min, +)` 逐元素验证了 `negLog(A^3) == (negLog A)^3`。

为什么要绕这一步？因为负权环有现成的经典算法：Bellman–Ford。

## 4. Bellman–Ford：不仅知道有，还能把环找出来

`findCycle` 的核心只有一行松弛：

```scala
val candidate = dist(u) ⊗ w(u)(v)
if (candidate ⊕ dist(v)) < dist(v) - eps then ...
```

这就是半环矩阵乘法里的一项。“更好”用的是幂等半环自带的序：a ≤ b ⟺ a ⊕ b = a。第 n 轮还能松弛，说明有负环，沿前驱指针就能还原出环：

```scala
scala> findCycle(currencies, stale)
val res2: Option[ArbitrageCycle] = Some(ArbitrageCycle(List(GBP, JPY, EUR, GBP), 1.0134...))
```

找到的环未必经过 USD：只要 EUR→GBP 被高估，任何一个第三方货币都能凑成三角。

## 5. 现实中的注意点

- **点差与手续费**：直接乘进汇率里即可，代码里的 `spread` 就是这么做的。
- **浮点误差**：`eps` 防止把 1e-16 级的舍入误判成套利。真实系统里阈值应该大于手续费 + 滑点。
- **深度**：报价背后的挂单量有限。把边权换成可成交数量、用 `maxMin` 半环，就能算出一条路线最多能走多少钱。

## 练习

1. 在 `Semiring` 里加一个 `(max, +)` 半环（最长路），想想它在什么图上闭包才有意义。
2. 用 `maxMin` 半环给每条报价配一个挂单深度，求从 USD 出发到每种货币的最大可兑换量。
3. 把 `findCycle` 改成返回**所有**不同的套利环（提示：每找到一个就把它的一条边删掉再跑）。
4. 证明（或用测试验证）`Matrix.mul` 满足结合律只需要 ⊗ 结合和分配律。
5. 从公开 API 抓一组真实的加密货币报价（注意买价卖价是两条不同的边），看看能不能找到套利。为什么几乎总是找不到？
