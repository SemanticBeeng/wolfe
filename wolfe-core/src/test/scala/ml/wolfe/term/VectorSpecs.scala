package ml.wolfe.term

import ml.wolfe._
import ml.wolfe.term.TermImplicits._
import scala.util.Random

/**
 * @author riedel
 */
class VectorSpecs extends WolfeSpec {

  implicit val random = new Random(0)

  "An vector variable term" should {
    "evaluate to a vector" in {
      val x = Vectors(2).Var
      val result = x.eval(x := vector(1.0, 2.0))
      result should equal(vector(1.0, 2.0))
    }

    "provide its constant gradient" in {
      val x = Vectors(2).Var
      val result = x.diff(x)(x := vector(2.0, 1.0))
      result should equal(vector(1.0, 1.0))
    }

    "access its elements" in {
      val x = Vectors(2).Var
      val i = Ints.Var
      val term = x(i)
      term.eval(x := vector(1.0, 2.0), i := 1) should be (2.0)
    }

    "support addition" in {
      val x = Vectors(2).Var
      val t = x + x
      t.eval(x := vector(1,2)) should equal (vector(2,4))
    }
  }

  "A vector apply term" should {
    "provide its constant gradient" ignore {
      val x = Vectors(1).Var
      val t = x(0)
      val result = t.diff(x)(x := vector(3.0))
      result(0) should be (1.0)
    }
  }
}

