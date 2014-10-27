package round.macros

import round.formula._
import round.logic._
import dzufferey.utils.Namer

trait Serialization {
  self: Impl =>
  import c.universe._

  //$tpt -> protected def serialize(payload: $tpt, out: _root_.io.netty.buffer.ByteBuf, withLength: Boolean = true, offset: Int = 8): Int
  //$tpt -> protected def deserialize(in: _root_.io.netty.buffer.ByteBuf, withLength: Boolean = true, offset: Int = 8): $tpt
  
  
  def picklingIO(tpt: Tree) = List(
      q"""protected def serialize(payload: $tpt, out: _root_.io.netty.buffer.ByteBuf, offset: Int = 8) {
        import scala.pickling._
        import binary._
        if (offset > 0) out.writerIndex(out.writerIndex() + offset)
        val bytes0 = payload.pickle.value
        val length = bytes0.length
        out.writeBytes(bytes0)
      }""",
      q"""protected def deserialize(in: _root_.io.netty.buffer.ByteBuf, offset: Int = 8): $tpt = {
        import scala.pickling._
        import binary._
        if (offset > 0) in.readerIndex(in.readerIndex() + offset)
        val length = in.readableBytes()
        val bytes = Array.ofDim[Byte](length)
        in.readBytes(bytes)
        BinaryPickle(bytes).unpickle[$tpt]
      }"""
    )

  def primitiveIO(tpt: Tree, write: List[Tree], read: List[Tree]) = List(
      q"""protected def serialize(payload: $tpt, out: _root_.io.netty.buffer.ByteBuf, offset: Int = 8) = {
        if (offset > 0) out.writerIndex(out.writerIndex() + offset)
        ..$write
      }""",
      q"""protected def deserialize(in: _root_.io.netty.buffer.ByteBuf, offset: Int = 8): $tpt = {
        if (offset > 0) in.readerIndex(in.readerIndex() + offset)
        ..$read
      }"""
  )

  def primitiveType(t: Type) = {
    import definitions._
    t =:= BooleanTpe ||
    t =:= ByteTpe ||
    t =:= CharTpe ||
    t =:= ShortTpe ||
    t =:= IntTpe ||
    t =:= LongTpe ||
    t =:= FloatTpe ||
    t =:= DoubleTpe
  }

  def primitiveRead(t: Type): Tree = {
    import definitions._
    if (t =:= BooleanTpe) {
      q"in.readBoolean()"
    } else if (t =:= ByteTpe) {
      q"in.readByte()"
    } else if (t =:= CharTpe) {
      q"in.readChar()"
    } else if (t =:= ShortTpe) {
      q"in.readShort()"
    } else if (t =:= IntTpe) {
      q"in.readInt()"
    } else if (t =:= LongTpe) {
      q"in.readLong()"
    } else if (t =:= FloatTpe) {
      q"in.readFloat()"
    } else if (t =:= DoubleTpe) {
      q"in.readDouble()"
    } else {
      sys.error("not primitive")
    }
  }
 
  def primitiveWrite(t: Type, value: Tree): Tree = {
    import definitions._
    if (t =:= BooleanTpe) {
      q"out.writeBoolean($value)"
    } else if (t =:= ByteTpe) {
      q"out.writeByte($value)"
    } else if (t =:= CharTpe) {
      q"out.writeChar($value)"
    } else if (t =:= ShortTpe) {
      q"out.writeShort($value)"
    } else if (t =:= IntTpe) {
      q"out.writeInt($value)"
    } else if (t =:= LongTpe) {
      q"out.writeLong($value)"
    } else if (t =:= FloatTpe) {
      q"out.writeFloat($value)"
    } else if (t =:= DoubleTpe) {
      q"out.writeDouble($value)"
    } else {
      sys.error("not primitive")
    }
  }

  def tupleWrite(args: List[Type], value: Tree): List[Tree] = {
    val name = Array(TermName("_1"), TermName("_2"), TermName("_3"), TermName("_4"))
    val res = args.zipWithIndex.map{ case (t, idx) => val m = name(idx); primitiveWrite(t, q"$value.$m") }
    //println("tupleWrite: " + res.mkString(", "))
    res
  }

  def tupleRead(args: List[Type]): List[Tree] = {
    val name = Array(q"_1", q"_2", q"_3", q"_4")
    val (reads, vars) = args.map( t => {
      val v = TermName(c.freshName("tmp"))
      val r = primitiveRead(t) 
      (ValDef(Modifiers(), v, TypeTree(t), r), Ident(v))
    }).unzip
    val res = reads ::: List(q"(..$vars)")
    //println("tupleRead: " + res.mkString(", "))
    res
  }

  def serializationMethods(tpt: Tree): List[Tree] = {
    val t = tpt.tpe
    if (primitiveType(t)) {
      val wr = List(primitiveWrite(t, q"payload"))
      val rd = List(primitiveRead(t))
      primitiveIO(tpt, wr, rd)
    } else {
      t match {
        case IsTuple(args) if args.forall(primitiveType) =>
          val wr = tupleWrite(args, q"payload")
          val rd = tupleRead(args)
          primitiveIO(tpt, wr, rd)
        case _ =>
          //TODO string
          //TODO options
          println("using pickling on " + showRaw(tpt))
          picklingIO(tpt)
      }
    }
  }

}
