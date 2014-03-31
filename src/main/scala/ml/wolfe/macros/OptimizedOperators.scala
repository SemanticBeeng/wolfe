package ml.wolfe.macros

import ml.wolfe.{BruteForceOperators, Wolfe, Operators}
import scala.reflect.macros.Context
import Wolfe._
import org.scalautils.{Bad, Good}

/**
 * @author Sebastian Riedel
 */
object OptimizedOperators extends Operators {

  import scala.language.experimental.macros

  override def argmax[T, N: Ordering](overWhereOf: Builder[T, N]): T = macro argmaxImpl[T, N]
  override def argmin[T, N: Ordering](overWhereOf: Builder[T, N]): T = macro argminImpl[T, N]
  override def map[T](overWhereOf: Builder[T, _]): Iterable[T] = macro mapImpl[T]


  def argmaxImpl[T: c.WeakTypeTag, N: c.WeakTypeTag](c: Context)
                                                    (overWhereOf: c.Expr[Builder[T, N]])
                                                    (ord: c.Expr[Ordering[N]]) = {
    val helper = new ContextHelper[c.type](c) with OptimizedOperators[c.type]
    if (c.enclosingMacros.size > 1) {
      import c.universe._
      val trees = helper.builderTrees(overWhereOf.tree)
      val code: Tree = q"${ trees.over }.filter(${ trees.where }).maxBy(${ trees.of })"
      c.Expr[T](code)
    } else {
      val result = helper.argmax(overWhereOf.tree)
      c.Expr[T](result.combined)
    }
  }

  def argminImpl[T: c.WeakTypeTag, N: c.WeakTypeTag](c: Context)
                                                    (overWhereOf: c.Expr[Builder[T, N]])
                                                    (ord: c.Expr[Ordering[N]]) = {
    import c.universe._
    val helper = new ContextHelper[c.type](c) with OptimizedOperators[c.type]
    val result: Tree = helper.argmax(overWhereOf.tree, q"-1.0").combined
    c.Expr[T](result)
  }

  def mapImpl[T: c.WeakTypeTag](c: Context)
                               (overWhereOf: c.Expr[Builder[T, _]]) = {
    import c.universe._
    val helper = new ContextHelper[c.type](c) with OptimizedOperators[c.type]

    val result: Tree = helper.map(overWhereOf.tree)
    //    val result: Tree = helper.argmax(overWhereOf.tree, q"-1.0")
    //    val expr = reify[Iterable[T]](overWhereOf.splice.dom.filter(overWhereOf.splice.filter).map(overWhereOf.splice.mapper))
    //    c.Expr[Iterable[T]](c.resetLocalAttrs(expr.tree))
    c.Expr[T](result)
  }


}

trait OptimizedOperators[C <: Context] extends MetaStructures[C]
                                               with MetaStructuredFactors[C]
                                               with Conditioner[C]
                                               with MetaGradientCalculators[C] {

  import context.universe._


  def inferenceCode(objRhs: Tree, graph: TermName) = objRhs match {
    case q"$f(${ _ })" =>
      f.symbol.annotations.find(_.tpe.typeSymbol == wolfeSymbols.optByInference) match {
        case Some(annotation) => q"${ annotation.scalaArgs.head }($graph)"
        case None => q"ml.wolfe.MaxProduct($graph,1)"
      }
    case _ => q"ml.wolfe.MaxProduct($graph,1)"
  }

  def learningCode(objRhs: Tree, weightsSet: TermName) = {
    def getCodeFromAnnotation(f: Tree): Tree = {
      f.symbol.annotations.find(_.tpe.typeSymbol == wolfeSymbols.optByLearning) match {
        case Some(annotation) => q"${ annotation.scalaArgs.head }($weightsSet)"
        case None => q"new OnlineTrainer($weightsSet, new Perceptron, 4)"
      }
    }
    objRhs match {
      case q"$f(${ _ })" => getCodeFromAnnotation(f)
      case _ => q"new OnlineTrainer($weightsSet, new Perceptron, 4)"
    }
  }

  def map(builder: Tree): Tree = {
    val trees = builderTrees(builder)
    val Function(List(mapperArg), _) = simplifyBlocks(trees.using)

    //we should do this until no more inlining can be done

    //todo: this is messy
    def transform(using: Tree) = transformAndCollect[List[Tree]](using, {
      case ArgmaxOperator(argmaxBuilder) =>
        val codeAndInit = argmaxLinearModel(argmaxBuilder)
        val initContainsMappingArg = codeAndInit.initialization.exists(_.exists(_.symbol == mapperArg.symbol))
        if (initContainsMappingArg) (codeAndInit.combined, Nil) else (codeAndInit.code, codeAndInit.initialization)
    })
    var (mapper, initCode) = transform(trees.using)
    var inlined = inlineOnce(mapper)
    while (inlined.isDefined) {
      val (m, i) = transform(inlined.get)
      initCode :::= i
      mapper = m
      inlined = inlineOnce(m)
    }

    val mapCall = trees.where match {
      case w if w == EmptyTree => q"${ trees.over }.map($mapper)"
      case w => q"${ trees.over }.filter($w).map($mapper)"
    }
    val flattened = initCode.flatten
    val code = q"""
      ..$flattened
      $mapCall
    """
    context.resetLocalAttrs(code)
  }

  case class CodeAndInitialization(code: Tree, initialization: List[Tree]) {
    def all = initialization :+ code
    def combined = q"{..$all}"
  }

  def argmaxLinearModel(trees: BuilderTrees): CodeAndInitialization = {
    val structName = newTermName(context.fresh("structure"))
    val meta = metaStructure(trees.over)
    val Function(List(objArg), objRhs) = blockToFunction(simplifyBlocks(trees.of))
    val objMatcher = meta.matcher(rootMatcher(objArg.symbol, q"$structName", meta))
    val factors = metaStructuredFactor(objRhs, meta, objMatcher, linearModelInfo = LinearModelInfo(q"_index"))
    val inferCode = inferenceCode(objRhs, newTermName("_graph"))

    val structureDef = meta.classDef(newTermName("_graph"))

    val conditionCode = if (trees.where == EmptyTree) EmptyTree
    else {
      val Function(List(whereArg), whereRhs) = simplifyBlocks(trees.where)
      val whereMatcher = meta.matcher(rootMatcher(whereArg.symbol, q"$structName", meta))
      val conditioner = conditioning(whereRhs, whereMatcher)
      conditioner.code
    }
    val factorieWeights = factors.weightVector.map(
      w => q"ml.wolfe.FactorieConverter.toFactorieDenseVector($w,_index)"
    ).getOrElse(q"new ml.wolfe.DenseVector(0)")

    val initialization = List(
      q"val _index = new ml.wolfe.Index()",
      q"val _factorieWeights = $factorieWeights")

    val code = q"""
      val _graph = new ml.wolfe.MPGraph
      $structureDef
      val $structName = new ${ meta.className }
      $conditionCode
      _graph.setupNodes()
      ${ factors.classDef }
      val factors = new ${ factors.className }($structName)
      _graph.build()
      _graph.weights = _factorieWeights
      $inferCode
      $structName.setToArgmax()
      $structName.value()
    """
    CodeAndInitialization(code, initialization)
  }

  def blockToFunction(tree: Tree): Tree = tree match {
    case f: Function => f
    case Block(stats, Function(args, body)) => {
      val (newBody, newStats) = stats.foldRight(body -> List.empty[Tree]) {
        (stat, result) => stat match {
          case v: ValDef => transform(result._1, { case i: Ident if i.symbol == v.symbol => v.rhs }) -> result._2
        }
      }
      Function(args, Block(newStats, newBody))
    }
    case _ => context.error(context.enclosingPosition, s"Can't turn $tree into function"); ???
  }

  def argmaxByLearning(trees: BuilderTrees, scaling: Tree = q"1.0"): Tree = {
    if (trees.where != EmptyTree)
      context.error(context.enclosingPosition, "Can't learn with constraints on weights yet: " + trees.where)
    val q"($arg) => $rhs" = simplifyBlocks(trees.of)
    def toSum(tree: Tree): BuilderTrees = tree match {
      case s@Sum(BuilderTrees(over, where, of, _)) => BuilderTrees(over, where, of)
      case s => inlineOnce(tree) match {
        case Some(inlined) => toSum(inlined)
        case None => BuilderTrees(q"List(0)", EmptyTree, q"(i:Int) => $s")
      }
    }
    val sum = toSum(rhs)
    val q"($x) => $perInstanceRhs" = simplifyBlocks(sum.of)
    val instanceName = newTermName(context.fresh("_instance"))
    val indexName = newTermName(context.fresh("_index"))
    val weightsSet = newTermName(context.fresh("_weightsSet"))
    val key = newTermName(context.fresh("_key"))
    val learner = learningCode(rhs, weightsSet)

    val replaced = transform(perInstanceRhs, { case i: Ident if i.symbol == x.symbol => Ident(instanceName) })

    metaGradientCalculator(replaced, arg.symbol, Ident(indexName)) match {
      case Good(calculator) =>
        val code = q"""
          import cc.factorie.WeightsSet
          import cc.factorie.la.WeightsMapAccumulator
          import cc.factorie.util.DoubleAccumulator
          import cc.factorie.optimize._
          import ml.wolfe.util.LoggerUtil
          import ml.wolfe._

          val $indexName = new Index
          val $weightsSet = new WeightsSet
          val $key = $weightsSet.newWeights(new ml.wolfe.DenseVector(10000))
          val examples = for ($instanceName <- ${ sum.over }) yield new Example {
            ${ calculator.classDef }
            val gradientCalculator = new ${ calculator.className }
            def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator) = {
              LoggerUtil.debug("Instance: " + $indexName)
              val weights = $weightsSet($key).asInstanceOf[FactorieVector]
              val (v, g) = gradientCalculator.valueAndGradient(weights)
              value.accumulate($scaling * v)
              gradient.accumulate($key, g, $scaling)
            }
          }
          val trainer = $learner
          trainer.trainFromExamples(examples)
          ml.wolfe.FactorieConverter.toWolfeVector($weightsSet($key).asInstanceOf[FactorieVector], $indexName)
        """
        context.resetLocalAttrs(code)
      case Bad(CantDifferentiate(term)) =>
        context.error(context.enclosingPosition, "Can't calculate gradient for " + term)
        ??? //todo: I don't know what should be returned here---doesn't the compiler quit at this point?
    }
  }

  def argmax(overWhereOf: Tree, scaling: Tree = q"1.0"): CodeAndInitialization = {

    val trees = builderTrees(overWhereOf)
    //todo: deal with scaling in linear model as well
    if (trees.over.symbol == wolfeSymbols.vectors)
      CodeAndInitialization(argmaxByLearning(trees, scaling),Nil)
    else argmaxLinearModel(trees)
  }


}