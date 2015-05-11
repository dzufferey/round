package round.logic

import round.formula._

import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

//congruence closure to reduce the number of terms in the instanciation
object CongruenceClosure {

  def apply(f: Seq[Formula]): CongruenceClasses = apply(And(f:_*))

  def apply(f: Formula): CongruenceClasses = {

    var formulaToNode = Map.empty[Formula, CcNode]

    def getNode(f: Formula): CcNode = {
      if (formulaToNode contains f) { 
        formulaToNode(f)
      } else {
        val n = f match {
          case Application(f, args) =>
            val argsN = args.map(getNode(_))
            val node = new CcSym(f, argsN)
            for (n <- argsN) n.ccParents += node
            node
          case v @ Variable(_) => new CcVar(v)
          case l @ Literal(_) => new CcLit(l)
          case other =>
            Logger.logAndThrow("CongruenceClosure", Error, "did not expect: " + other)
        }
        formulaToNode += f -> n
        n
      }
    }

    //create the graph
    val gts = FormulaUtils.collectGroundTerms(f)
    gts.foreach( getNode(_) )

    def processEqs(f: Formula): Unit = f match {
      case And(lst @ _*) =>
        lst.foreach(processEqs(_))
      case Eq(a, b) if formulaToNode.contains(a) && formulaToNode.contains(b) =>
        formulaToNode(a).merge(formulaToNode(b))
      case Binding(ForAll | Exists, _, f) =>
        processEqs(f)
      case other =>
        ()
    }
    processEqs(f)
    
    //extract the CC classes
    //TODO could we return the simplest representative ?, i.e., a variables if there is one
    val cls = formulaToNode.values.groupBy(_.find)
    val classes = cls.map{ case (repr, ms) => new CongruenceClass(repr.formula, ms.map(_.formula).toSet) }
    val map = classes.foldLeft(Map.empty[Formula, CongruenceClass])( (acc, c) => {
      c.members.foldLeft(acc)( (a, m) => a + (m -> c) )
    } )
    new CongruenceClasses(classes, map)
  }

}

class CongruenceClasses(cls: Iterable[CongruenceClass], map: Map[Formula, CongruenceClass]) {

  override def toString = cls.mkString("\n")

  def apply(f: Formula): CongruenceClass = {
    map.getOrElse(f, new CongruenceClass(f, Set(f))) 
  }

  def repr(f: Formula) = {
    if (map.contains(f)) map(f).repr
    else f
  }

  def normalize(f: Formula) = {
    FormulaUtils.mapTopDown(repr(_), f)
  }

  lazy val formula: Formula = {
    if (cls.isEmpty) True()
    else And(cls.toSeq.map(_.formula):_*)
  }

  lazy val groundTerms: Set[Formula] = FormulaUtils.collectGroundTerms(formula)

}

object CongruenceClasses {
  val empty = new CongruenceClasses(Nil, Map.empty)
}

//a container for CC classes
class CongruenceClass(val repr: Formula, val members: Set[Formula]) {
  override def toString = repr + " <- " + (members - repr).mkString(", ")
  def contains(f: Formula) = members(f)
  def formula: Formula = {
    val eqs = (members - repr).map( Eq(repr, _) ).toSeq
    if (eqs.isEmpty) True()
    else And(eqs:_*)
  }
}

//node in the CC graph
abstract class CcNode {
  var parent: Option[CcNode] = None
  var ccParents: Set[CcNode] = Set.empty

  def formula: Formula
  def arity: Int
  def getArgs: List[CcNode]
  def name: String

  def find: CcNode = parent match {
    case Some(p) =>
      val p2 = p.find
      parent = Some(p2)
      p2
    case None =>
      this
  }

  def union(that: CcNode) {
    val n1 = this.find
    val n2 = that.find
    n1.parent = Some(n2)
    n2.ccParents = n1.ccParents ++ n2.ccParents
    n1.ccParents = Set.empty
  }
    
  def ccPar = find.ccParents

  def congruent(that: CcNode) = {
    name == that.name &&
    arity == that.arity &&
    getArgs.zip(that.getArgs).forall{ case (a,b) => a.find == b.find }
  }

  def merge(that: CcNode) {
    if (find != that.find) {
      val p1 = this.ccPar
      val p2 = that.ccPar
      union(that)
      val toTest = p1.flatMap( x => p2.map( y => (x,y) ) )
      for ( (x,y) <- toTest if x.find != y.find && x.congruent(y)) x.merge(y)
    }
  }

}

class CcSym(symbol: Symbol, args: List[CcNode]) extends CcNode {
  def formula = symbol(args.map(_.formula):_*) 
  def name = symbol.toString
  def arity = args.length
  def getArgs: List[CcNode] = args
}

class CcVar(v: Variable) extends CcNode {
  def formula = v
  def name = v.toString
  def arity = 0
  def getArgs: List[CcNode] = Nil
}

class CcLit(l: Literal[_]) extends CcNode {
  def formula = l
  def name = l.toString
  def arity = 0
  def getArgs: List[CcNode] = Nil
}