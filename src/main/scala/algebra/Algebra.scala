package algebra

/** 半群：带结合律的二元运算。 combine(combine(a, b), c) == combine(a, combine(b, c)) */
trait Semigroup[A]:
  extension (x: A) def |+|(y: A): A

/** 幺半群：半群 + 单位元。 empty |+| a == a |+| empty == a */
trait Monoid[A] extends Semigroup[A]:
  def empty: A

/** 群：幺半群 + 逆元。 a |+| a.inverse == empty */
trait Group[A] extends Monoid[A]:
  extension (x: A) def inverse: A

object Monoid:
  def apply[A](using m: Monoid[A]): Monoid[A] = m

  /** 幺半群让我们可以安全地折叠任意序列（也是并行归约的基础）。 */
  def combineAll[A: Monoid](xs: IterableOnce[A]): A =
    xs.iterator.foldLeft(Monoid[A].empty)(_ |+| _)

given Group[Int] with
  def empty: Int = 0
  extension (x: Int)
    def |+|(y: Int): Int = x + y
    def inverse: Int = -x

given Monoid[String] with
  def empty: String = ""
  extension (x: String) def |+|(y: String): String = x + y

given [A]: Monoid[List[A]] with
  def empty: List[A] = Nil
  extension (x: List[A]) def |+|(y: List[A]): List[A] = x ++ y

/** 给半群补一个单位元：None 就是 empty。 只有半群的东西（例如一根 K 线，没有“空 K 线”）也能折叠了。 */
given [A: Semigroup]: Monoid[Option[A]] with
  def empty: Option[A] = None
  extension (x: Option[A])
    def |+|(y: Option[A]): Option[A] = (x, y) match
      case (Some(a), Some(b)) => Some(a |+| b)
      case _                  => x.orElse(y)

/** 按键合并：同一个键上的值用 V 的半群合并。分桶聚合、跨机器汇总都靠它。 */
given [K, V: Semigroup]: Monoid[Map[K, V]] with
  def empty: Map[K, V] = Map.empty
  extension (x: Map[K, V])
    def |+|(y: Map[K, V]): Map[K, V] =
      y.foldLeft(x) { case (acc, (k, v)) => acc.updated(k, acc.get(k).fold(v)(_ |+| v)) }
