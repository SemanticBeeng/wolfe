package ml.wolfe.macros

import ml.wolfe.MPGraph

/**
 * A structure is a collection of MPGraph nodes whose assignments correspond to values of type `T`.
 * @tparam T the type of the values this structure can generate.
 * @author Sebastian Riedel
 */
trait Structure[+T] {
  /**
   * @return all nodes in this structure (including nodes of substructures)
   */
  def nodes(): Iterator[MPGraph.Node]
  /**
   * @return the value that the current assignment to all nodes is representing.
   */
  def value(): T
  /**
   * Sets all nodes to their argmax belief. todo: this could be generically implemented using nodes().
   */
  def setToArgmax()
  /**
   * resets the state of all nodes.
   */
  def resetSetting()
  /**
   * @return is there a next state that the structure can take on or have we iterated over all its states.
   */
  def hasNextSetting: Boolean
  /**
   * set the structure to its next state by changing one or more of its nodes assignments.
   */
  def nextSetting()
}

object Structure {
  def loopSettings(structures: List[Structure[Any]])(loop: () => Unit): () => Unit = structures match {
    case Nil => loop
    case head :: tail =>
      def newLoop() {
        head.resetSetting()
        while (head.hasNextSetting) {
          head.nextSetting()
          loop()
        }
      }
      loopSettings(tail)(newLoop)
  }

  def settingsIterator(structures: List[Structure[Any]],
                       iterator: () => Iterator[Unit] = () => Iterator.empty): () => Iterator[Unit] = structures match {
    case Nil => iterator
    case head :: tail =>
      def newIterator() = new Iterator[Unit] {
        head.resetSetting()
        var inner = iterator()
        if (inner.hasNext) {
          head.nextSetting() //this may not work if head has empty domain
        }
        override def next() = {
          if (inner.hasNext) inner.next()
          else {
            head.nextSetting()
            inner = iterator()
            if (inner.hasNext) inner.next()
          }
        }
        override def hasNext = inner.hasNext || head.hasNextSetting
      }
      settingsIterator(tail, newIterator)
  }


}

