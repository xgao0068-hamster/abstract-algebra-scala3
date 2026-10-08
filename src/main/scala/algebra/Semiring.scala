package algebra

/** 半环：两个运算 ⊕（“加”）和 ⊗（“乘”）。
  *
  *   - (A, ⊕, zero) 是交换幺半群
  *   - (A, ⊗, one) 是幺半群
  *   - ⊗ 对 ⊕ 满足分配律：a ⊗ (b ⊕ c) == (a ⊗ b) ⊕ (a ⊗ c)
  *   - zero 是 ⊗ 的零化元：zero ⊗ a == a ⊗ zero == zero
  *
  * 和环相比，半环不要求 ⊕ 有逆元，所以 min、max、或（∨）都能当“加法”。
  * 同一份图算法换一个半环，就能回答完全不同的问题。
  *
  * 同一个类型（例如 Double）上可以有很多种半环，所以这里不用全局 given，
  * 而是把实例做成具名的值，调用时用 `(using Semiring.minPlus)` 显式选择。
  */
trait Semiring[A]:
  def zero: A
  def one: A
  extension (x: A)
    def ⊕(y: A): A
    def ⊗(y: A): A

object Semiring:
  def apply[A](using s: Semiring[A]): Semiring[A] = s

  /** 热带半环 (min, +)：路径权重相加，多条路径取最小。答案是“最短路”。 */
  val minPlus: Semiring[Double] = new Semiring[Double]:
    def zero = Double.PositiveInfinity
    def one = 0.0
    extension (x: Double)
      def ⊕(y: Double) = math.min(x, y)
      def ⊗(y: Double) = x + y

  /** (max, ×)：路径上的汇率相乘，多条路径取最大。答案是“最佳兑换比率”。 */
  val maxTimes: Semiring[Double] = new Semiring[Double]:
    def zero = 0.0
    def one = 1.0
    extension (x: Double)
      def ⊕(y: Double) = math.max(x, y)
      def ⊗(y: Double) = x * y

  /** 瓶颈半环 (max, min)：路径容量取最窄处，多条路径取最宽。答案是“最大可通过量”。 */
  val maxMin: Semiring[Double] = new Semiring[Double]:
    def zero = 0.0
    def one = Double.PositiveInfinity
    extension (x: Double)
      def ⊕(y: Double) = math.max(x, y)
      def ⊗(y: Double) = math.min(x, y)

  /** 布尔半环 (∨, ∧)：答案是“能不能到达”。 */
  val boolean: Semiring[Boolean] = new Semiring[Boolean]:
    def zero = false
    def one = true
    extension (x: Boolean)
      def ⊕(y: Boolean) = x || y
      def ⊗(y: Boolean) = x && y

/** 半环上的方阵。matrix(i)(j) 是从 i 到 j 的一步边权，没有边就是 zero。 */
type Matrix[A] = Vector[Vector[A]]

object Matrix:
  def identity[A](n: Int)(using S: Semiring[A]): Matrix[A] =
    Vector.tabulate(n, n)((i, j) => if i == j then S.one else S.zero)

  /** 矩阵乘法：把普通矩阵乘法里的 + 和 × 换成 ⊕ 和 ⊗。
    * (A ⊗ B)(i)(j) = ⊕_k A(i)(k) ⊗ B(k)(j)，即“先走 A 一步再走 B 一步”的所有路径汇总。
    */
  def mul[A](a: Matrix[A], b: Matrix[A])(using S: Semiring[A]): Matrix[A] =
    val n = a.length
    Vector.tabulate(n, n): (i, j) =>
      (0 until n).foldLeft(S.zero)((acc, k) => acc ⊕ (a(i)(k) ⊗ b(k)(j)))

  def add[A](a: Matrix[A], b: Matrix[A])(using S: Semiring[A]): Matrix[A] =
    Vector.tabulate(a.length, a.length)((i, j) => a(i)(j) ⊕ b(i)(j))

  /** A^k：恰好走 k 步的所有路径汇总。用快速幂，只依赖 ⊗ 的结合律。 */
  def power[A](a: Matrix[A], k: Int)(using S: Semiring[A]): Matrix[A] =
    require(k >= 0)
    if k == 0 then identity(a.length)
    else if k % 2 == 0 then
      val half = power(a, k / 2)
      mul(half, half)
    else mul(a, power(a, k - 1))

  /** I ⊕ A ⊕ A² ⊕ … ⊕ A^k：至多走 k 步的所有路径汇总。
    *
    * 当图里没有“越绕越好”的环时（例如 minPlus 下没有负环），取 k = n - 1
    * 就是所有点对之间的最优路径，也就是半环上的闭包 A*。
    */
  def upTo[A](a: Matrix[A], k: Int)(using S: Semiring[A]): Matrix[A] =
    (1 to k).foldLeft((identity(a.length), identity(a.length))):
      case ((sum, pow), _) =>
        val next = mul(pow, a)
        (add(sum, next), next)
    ._1

  /** 把一个半环的矩阵逐元素映射到另一个半环，例如汇率 r ↦ -log r。 */
  def map[A, B](a: Matrix[A])(f: A => B): Matrix[B] = a.map(_.map(f))
