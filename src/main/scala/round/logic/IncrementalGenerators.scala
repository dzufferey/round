package round.logic

import round.formula._

import dzufferey.utils.Misc
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

//TODO Local IncrementalGenerator
//TODO triggers/Matching, etc.

//more a formula generator than a term generator
class IncrementalFormulaGenerator(axioms: Iterable[Formula]) {

  protected def mkGen(vs: Iterable[Variable], f: Formula) = {
    import FormulaUtils._
    new Gen(vs.toArray.sorted, Simplify.simplify(f))
  }

  protected class Gen(val vs: Array[Variable], val f: Formula) {

    override def toString = vs.mkString("Gen( ",", "," → " + f)

    val done = Array.tabulate(vs.size)( _ => scala.collection.mutable.Set[Formula]() )

    def similar(tg: Gen) = {
      tg.vs.size == vs.size &&
      (0 until tg.vs.size).forall(i => tg.vs(i) == vs(i)) &&
      tg.f == f
    }

    def isResult = vs.isEmpty
    def result = {
      assert(isResult)
      f
    }

    def newGen(idx: Int, term: Formula): Gen = {
      val kept = List.tabulate(vs.size -1)( i => if (i < idx) vs(i) else vs(i+1) )
      val subs = FormulaUtils.replace(vs(idx), term, f)
      Simplify.deBruijnIndex(ForAll(kept, subs)) match {
        case ForAll(vs, f) => mkGen(vs,f)
        case other => mkGen(Array.empty[Variable], other)
      }
    }

    def apply(term: Formula): Iterable[Gen] = {
      var i = 0
      var res = List.empty[Gen]
      while(i < vs.size) {
        if (term.tpe == vs(i).tpe && !done(i)(term)) {
          res ::= newGen(i, term)
          done(i) += term
        }
        i += 1
      }
      res
    }
    
    def apply(v: Variable, term: Formula): Option[Gen] = {
      val i = vs.indexOf(v)
      if (!done(i)(term)) {
        done(i) += term
        Some(newGen(i, term))
      } else {
        None
      }
    }

    def localMatches(cc: CC): Set[Map[Variable,Formula]] = {
      val toIdx = vs.zipWithIndex.toMap
      def notDone(m: Map[Variable,Formula]): Boolean = {
        m.forall{ case (v,f) => !done(toIdx(v))(f) }
      }
      Matching.findLocalSubterms(cc, vs.toSet, f).filter(notDone)
    }

  }

  //the current generators
  import scala.collection.mutable.ArrayBuffer
  protected val idx  = scala.collection.mutable.Map[Type,ArrayBuffer[Int]]()
  protected val gens = ArrayBuffer[Gen]()

  protected def findSimilar(g: Gen): Option[Int] = {
    val potentialConflict = scala.collection.mutable.BitSet()
    g.vs.foreach( v => {
      potentialConflict ++= idx.getOrElseUpdate(v.tpe, ArrayBuffer[Int]())
    })
    potentialConflict.find( i => gens(i).similar(g))
  }

  protected def addGen(g: Gen) {
    if (findSimilar(g).isEmpty) {
      gens.append(g)
      g.vs.foreach( v => {
        val buffer = idx.getOrElseUpdate(v.tpe, ArrayBuffer[Int]())
        buffer += gens.size -1
      })
    }
  }
  
  //extract the first Gen from the axioms
  axioms.foreach{
    case fa @ ForAll(_, _) =>
      Simplify.deBruijnIndex(fa) match {
        case ForAll(vs, f) => addGen( mkGen(vs, f) )
        case other => Logger("IncrementalGenerator", Warning, "(1) expect ∀, found: " + other)
      }
    case other => Logger("IncrementalGenerator", Warning, "(2) expect ∀, found: " + other)
  }
  
  
  def generate(term: Formula): List[Formula] = {
    val candidate = idx.getOrElseUpdate(term.tpe, ArrayBuffer[Int]())
    var i = 0
    var res = List.empty[Formula]
    while(i < candidate.size) {
      val newGens = gens(candidate(i))(term)
      newGens.foreach( g => {
        if (g.isResult) {
          if (g.result != True()) res ::= g.result
        } else addGen(g)
      })
      i += 1
    }
    res
  }
  
  def generate(groundTerms: Iterable[Formula]): List[Formula] = {
    groundTerms.toList.flatMap(generate)
  }

  def locallySaturate(cc: CC): List[Formula] = {
    var i = 0
    val done = scala.collection.mutable.BitSet()
    val res = scala.collection.mutable.ListBuffer[Formula]()
    def checkDone(g: Gen, remaining: Iterable[Map[Variable,Formula]]) = {
      if (g.isResult) {
        assert(remaining.isEmpty || remaining.forall(_.isEmpty))
        if (g.result != True()) res += g.result
        true
      } else {
        false
      }
    }
    def instVar(g: Gen, matches: Iterable[Map[Variable,Formula]]) {
      if (!checkDone(g, matches)) {
        val v = g.vs.last
        val byV = matches.groupBy(_(v))
        for ( (candidate, maps) <- byV;
              g2 <- g(v, candidate) ) {
          val renaming = g.vs.zip(g2.vs).toMap //g2 has renamed arguments ...
          val remaining = maps.map( m => {
            val m1 = m - v 
            m1.map{ case (a,b) => renaming(a) -> b }
          })
          complete(g2, remaining)
        }
      }
    }
    def complete(g: Gen, remaining: Iterable[Map[Variable,Formula]]) {
      if (!checkDone(g, remaining)) findSimilar(g) match {
        case Some(i) =>
          val g2 = gens(i)
          done += i
          instVar(g2, remaining)
        case None =>
          done += gens.size
          addGen(g)
          instVar(g, remaining)
      }
    }
    while(i < gens.size && !done(i)) {
      val g = gens(i)
      instVar(g, g.localMatches(cc))
      i += 1
    }
    res.result
  }

}


class IncrementalGenerator(f: Formula, val cc: CongruenceClosure = new CongruenceClosure) {

  //make sure the current equalities are in the cc
  cc.addConstraints(f)

  val gen = {
    val axioms = for( f <- FormulaUtils.getConjuncts(f) if Quantifiers.hasFA(f) )
                 yield Simplify.pnf(f)
    new IncrementalFormulaGenerator(axioms)
  }

  def generate(term: Formula): List[Formula] = {
    val r = cc.repr(term)
    val newInst = gen.generate(r)
    cc.addConstraints(newInst)
    newInst
  }

  def generate(terms: Set[Formula]): List[Formula] = {
    val buffer = scala.collection.mutable.ListBuffer[Formula]()
    terms.foreach(t => buffer.appendAll(generate(t)))
    buffer.result
  }

  def generateWithExistingGTS = generate(cc.groundTerms)

  /** saturate starting with the groundTerms (representative in cc), up to a certain depth.
   * @param depth (optional) bound on the recursion depth
   * @return applications of the axioms
   */
  def saturate(depth: Option[Int] = None) = {
    val buffer = scala.collection.mutable.ListBuffer[Formula]()
    var d = depth
    var processed = scala.collection.mutable.Set[Formula]()
    var toProcess = cc.groundTerms.map(cc.repr)
    while (d.getOrElse(1) > 0 && !toProcess.isEmpty) {
      val newInst = generate(toProcess)
      buffer ++= newInst
      processed ++= toProcess
      val newGts = newInst.view.flatMap(FormulaUtils.collectGroundTerms)
      toProcess = newGts.map(cc.repr).filter(f => !processed.contains(f)).toSet
      d = d.map(_ - 1)
    }
    if (d.getOrElse(1) == 0) {
      buffer ++= gen.locallySaturate(cc)
    }
    buffer.result
  }

}
