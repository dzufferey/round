package psync.logic

import psync.formula._
import psync.logic.quantifiers._
import psync.utils.smtlib._
import dzufferey.utils.Logger

object TestCommon {

  val pid = CL.procType
  val n = CL.n
  val ho = CL.HO
  
  val i = Variable("i").setType(pid)
  val j = Variable("j").setType(pid)
  val k = Variable("k").setType(pid)

  def ite(a: Formula, b: Formula, c: Formula) = And(Or(Not(a), b), Or(a, c))
  
  /////////////
  // solving //
  /////////////

  val cl = ClDefault

  //corresponds to previous clv_q
  //def cle(v: Int, q: Int) = ClConfig(Some(v), None, Eager(Some(q), true))
  //def clg(v: Int, q: Int) = ClConfig(Some(v), None, Guided(Some(q), false))
  //def clh(v: Int, q1: Int, q2: Int) = ClConfig(Some(v), None, QSeq(Eager(Some(q1), false), Guided(Some(q2), false)))

  def cln(v: Int, t: Tactic, depth: Int, local: Boolean) = ClConfig(Some(v), None, QNew(t, Some(depth), local))

  def reduce(clc: ClConfig, conjuncts: List[Formula], debug: Boolean): Formula = {
    val cl = new CL(clc)
    if(debug) {
      Logger.moreVerbose
      Logger.moreVerbose
      Logger.disallow("Typer")
    }
    try {
      val c0 = conjuncts.flatMap(FormulaUtils.getConjuncts(_))
      val c1 = c0.map(Simplify.simplify)
      if(debug) {
        println("=======before reduce ")
        c1.foreach( f => println("  " + f) )
      }
      val f0 = And(c1 :_*)
      val f1 = cl.reduce(f0)
      if(debug) {
        println("======= send to solver")
        FormulaUtils.getConjuncts(f1).foreach( f => println("  " + f) )
      }
      f1
    } finally {
      if(debug) {
        Logger.lessVerbose
        Logger.lessVerbose
        Logger.allow("Typer")
      }
    }
  }

  def assertUnsat(conjuncts: List[Formula],
                  to: Long = 10000,
                  debug: Boolean = false,
                  reducer: ClConfig = cl,
                  fname: Option[String] = None, 
                  useCvcMf: Boolean = false) {
    val f1 = reduce(reducer, conjuncts, debug)
    val solver = if (useCvcMf) Solver.cvc4mf(UFLIA, fname, to)
                 else Solver(UFLIA, fname, to)
    assert(!solver.testB(f1), "unsat formula")
  }
  
  def assertUnsat(conjuncts: List[Formula],
                  reducer: ClConfig) {
    assertUnsat(conjuncts, 10000, false, reducer)
  }

  def assertSat(conjuncts: List[Formula],
                to: Long = 10000,
                debug: Boolean = false,
                reducer: ClConfig = cl,
                fname: Option[String] = None,
                useCvcMf: Boolean = false) {
    val f1 = reduce(cl, conjuncts, debug)
    val solver = if (useCvcMf) Solver.cvc4mf(UFLIA, fname, to)
                 else Solver(UFLIA, fname, to)
    assert( solver.testB(f1), "sat formula")
  }
  
  def assertSat(conjuncts: List[Formula],
                reducer: ClConfig) {
    assertSat(conjuncts, 10000, false, reducer)
  }


  def getModel(conjuncts: List[Formula],
               to: Long = 10000,
               reducer: ClConfig = cl,
               fname: Option[String] = None) {
    val f1 = reduce(reducer, conjuncts, true)
    val solver = Solver(UFLIA, fname, to)
    solver.testWithModel(f1) match {
      case Sat(Some(model)) =>
        Console.println(model.toString)
      case res =>
        assert( false, "could not parse model: " + res)
    }
  }

}
