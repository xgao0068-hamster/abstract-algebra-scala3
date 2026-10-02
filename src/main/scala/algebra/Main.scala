package algebra

@main def hello(): Unit =
  println(s"Σ 1..10 = ${Monoid.combineAll(1 to 10)}")
  println(s"拼接字符串 = ${Monoid.combineAll(List("抽象", "代数", "+", "Scala 3"))}")
  println(s"5 的逆元 = ${5.inverse}")
