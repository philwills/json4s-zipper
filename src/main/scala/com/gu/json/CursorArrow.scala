package com.gu.json

import scalaz._
import scalaz.Scalaz._
import scalaz.\/._

import org.json4s.JsonAST._


trait CursorArrow { self =>
  import CursorArrow._

  def run: Kleisli[CursorResult, JCursor, JCursor]

  final def compose(that: CursorArrow): CursorArrow = fromK(self.run compose that.run)
  final def andThen(that: CursorArrow): CursorArrow = that compose this

  final def <=<(that: CursorArrow): CursorArrow = compose(that)
  final def >=>(that: CursorArrow): CursorArrow = andThen(that)

  final def <*(that: CursorArrow): CursorArrow = fromK(self.run <* that.run)
  final def *>(that: CursorArrow): CursorArrow = fromK(self.run *> that.run)

  final def orElse(that: CursorArrow): CursorArrow = fromK(self.run <+> that.run)

}

object CursorArrow {

  type CursorResult[+A] = CursorFailure \/ A

  def apply(f: JCursor => CursorResult[JCursor]): CursorArrow =
    fromK(Kleisli(f))

  def fromK(k: Kleisli[CursorResult, JCursor, JCursor]): CursorArrow =
    new CursorArrow {
      val run = k
    }

  def withFailure(f: JCursor => Option[JCursor], msg: String): CursorArrow =
    CursorArrow { cursor =>
      f(cursor).toRightDisjunction(CursorFailure(cursor, msg))
    }

  def fail[A](at: JCursor, msg: String): CursorResult[A] = -\/(CursorFailure(at, msg))

}

/** Data type representing the position of the cursor before the failed action,
  * with a message describing the action that failed.
  */
case class CursorFailure(at: JCursor, msg: String)

object CursorArrows {
  import CursorArrow._

  def field(name: String) = withFailure(_.field(name), "field(" + name + ")")

  def firstElem = withFailure(_.firstElem, "firstElem")

  def elem(index: Int) = withFailure(_.elem(index), "elem(" + index + ")")

  def replace(newFocus: JValue) = CursorArrow(right compose (_.replace(newFocus)))

  def prepend(elem: JValue) = withFailure(_.prepend(elem), "prepend(" + elem + ")")

  def mod(f: JValue => JValue) = CursorArrow { cursor => \/-(cursor.withFocus(f)) }
  
  def transform(pfn: PartialFunction[JValue, JValue]) = CursorArrow { cursor => \/-(cursor.transform(pfn)) }

  def deleteGoUp = withFailure(_.deleteGoUp, "deleteGoUp")

  def setNothing = replace(JNothing)

  def eachElem(that: CursorArrow) = CursorArrow {
    case JCursor(JArray(elems), p) =>
      for (cursors <- that.run.traverse(elems map JCursor.jCursor))
      yield JCursor(JArray(cursors map (_.toJValue)), p)
    case cursor => fail(cursor, "eachElem")
  }

  def try_(arr: CursorArrow): CursorArrow =
    CursorArrow { cursor => arr.run(cursor) orElse cursor.pure[CursorResult] }

}


trait CursorArrowSyntax {
  
  import CursorArrows._

  type CursorArrowBuilder = CursorArrow => CursorArrow

  implicit class BuilderOps(self: CursorArrowBuilder) {
    def \(that: String): CursorArrowBuilder = arr => self(field(that) >=> arr)
    def \(that: CursorArrowBuilder): CursorArrowBuilder = arr => self(that(arr))
  }

  implicit class CursorStateExpr(self: String) {
    def \(that: String): CursorArrowBuilder = field(self) >=> field(that) >=> _
    def \(that: CursorArrowBuilder): CursorArrowBuilder = arr => field(self) >=> that(arr)
  }

  object * extends CursorArrowBuilder {
    def apply(v1: CursorArrow) = eachElem(v1)
  }

}

object CursorArrowSyntax extends CursorArrowSyntax
