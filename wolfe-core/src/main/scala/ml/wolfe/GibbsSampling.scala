package ml.wolfe

import ml.wolfe.fg.{CantProposeException, ContinuousVar, DiscreteVar, Var}

import scala.math._
import scalaxy.loops._
import scala.util.Random

/**
 * Created by luke on 22/08/14.
 */
object GibbsSampling {

  def apply(fg:FactorGraph, iterations:Int = 10000) = {
    fg.expectations = new SparseVector(10000)

    val unobservedNodes = fg.nodes.filterNot(_.variable.isObserved)

    for(i <- (0 until iterations).optimized) {
      //println( (for(n <- fg.nodes if !n.variable.isObserved) yield n.variable.label + ": " + n.variable.value).mkString("    ") + "\n")
      val n = unobservedNodes(Random.nextInt(unobservedNodes.length))
      sample(n.variable)
      for (f <- fg.expectationFactors) {
        fg.expectations += f.potential.statsForCurrentSetting()
      }
    }

    fg.expectations /= iterations
  }

  def sample(v:Var[_]):Unit = {
    def score = v.node.edges.map(e => e.f.potential.valueForCurrentSetting()).sum

    val oldSetting = v.setting
    val oldScore = score

    var sampled = false

    val sampledFromEdge = v.node.edges.sortBy(_ => Math.random()).find { e =>
      try {
        e.f.potential.proposeSetting(e)
        true
      } catch {
        case e:CantProposeException => false
      }
    }

    sampledFromEdge match {
      case None =>
        println("Could not propose for " + v.label + "; Invoking sampleUniform")
        sampleUniform(v)
      case Some(e) =>
    }
    val newScore = score

    if(exp(newScore - oldScore) < Math.random()) v.setting = oldSetting

  }


  def sampleUniform(v:Var[_]): Unit = v match {
    case v:DiscreteVar[_] => v.setting = Random.nextInt(v.dim)
    case v:ContinuousVar => v.setting = 42
  }

}
