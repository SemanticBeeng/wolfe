package ml.wolfe.term

/**
 * @author riedel
 */
class LogTerm[D <: Dom, T <: Term[D]](val toLog: T, val name:String = null) extends Term[D] {

  def nameToShow = if (name == null) toLog.toString else name

  override def evaluatorImpl(in: Settings) = new AbstractEvaluator2(in) {
    val evalToLog = toLog.evaluatorImpl(in)

    def eval()(implicit execution: Execution) = {
      evalToLog.eval()
      if (execution.typ != Execution.Diff) {
        val inputValues = for ((v, s) <- vars zip input) yield v.domain.toValue(s)
        val inputString = if (inputValues.isEmpty) "[None]" else inputValues.mkString("\n -", "\n -", "")
        println("-----------------------------------")
        println(s"Execution:  $execution")
        println(s"Term:       $nameToShow")
        println(s"Inputs:     $inputString")
        val outputValue = domain.toValue(output)
        println(s"Output:     $outputValue")
        toLog match {
          case composed: Composed[_] =>
            val composer = evalToLog.asInstanceOf[Composed[Dom]#ComposedEvaluator]
            val argValues = for ((a, s) <- composed.arguments zip composer.argOutputs) yield a.domain.toValue(s)
            val argString = argValues.mkString("\n -", "\n -", "")
            println(s"Arguments: $inputString")
          case _ =>
        }
      }
    }

    val output: Setting = evalToLog.output
  }


  override def differentiator2(wrt: Seq[Var[Dom]])(in: Settings, err: Setting, gradientAcc: Settings) =
    new AbstractDifferentiator2(in, err, gradientAcc) {
      val diffToLog = toLog.differentiator2(wrt)(in,err,gradientAcc)
      def forward()(implicit execution: Execution) = {
        diffToLog.forward()
        val inputValues = for ((v, s) <- vars zip input) yield v.domain.toValue(s)
        val inputString = if (inputValues.isEmpty) "[None]" else inputValues.mkString("\n -", "\n -", "")
        println("-----------------------------------")
        println(s"Execution:  $execution")
        println(s"Term:       $nameToShow")
        println(s"Inputs:     $inputString")
        val outputValue = domain.toValue(output)
        println(s"Output:     $outputValue" )

      }

      def backward()(implicit execution: Execution) = {
        diffToLog.backward()
      }

      val output: Setting = diffToLog.output
    }

  val domain = toLog.domain

  def vars = toLog.vars

  def evaluator() = ???

  def atomsIterator = ???

  def differentiator(wrt: Seq[Var[Dom]]) = ???
}