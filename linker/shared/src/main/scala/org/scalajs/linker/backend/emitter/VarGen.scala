/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.linker.backend.emitter

import scala.annotation.tailrec

import org.scalajs.ir._
import org.scalajs.ir.Names._
import org.scalajs.ir.OriginalName.NoOriginalName
import org.scalajs.ir.Types._

import org.scalajs.linker.backend.javascript.Trees._

/** Manages name generation for non-local, generated fields.
 *
 *  We distinguish two types of linker generated identifiers:
 *
 *  - globalVar: Vars accessible in the entire generated JS program
 *    (typically pertaining to a given class).
 *  - fileLevelVar: Vars that are local to an individual file.
 *
 *  `globalVar`s have `*Def` variants (e.g. `classFunctionDef`) to define them.
 *
 *  While all these distinctions are a bit theoretical at the moment, they will
 *  become relevant for module splitting (#2681).
 */
private[emitter] final class VarGen(jsGen: JSGen, nameGen: NameGen,
    mentionedDangerousGlobalRefs: Set[String]) {

  import jsGen._
  import nameGen._

  def globalVar[T: Scope](field: String, scope: T,
      origName: OriginalName = NoOriginalName)(implicit pos: Position): Tree = {
    VarRef(globalVarIdent(field, scope, origName))
  }

  def globalClassDef[T: Scope](field: String, scope: T,
      parentClass: Option[Tree], members: List[Tree],
      origName: OriginalName = NoOriginalName)(
      implicit pos: Position): Tree = {
    val ident = globalVarIdent(field, scope, origName)
    ClassDef(Some(ident), parentClass, members)
  }

  def globalFunctionDef[T: Scope](field: String, scope: T,
      args: List[ParamDef], body: Tree,
      origName: OriginalName = NoOriginalName)(
      implicit pos: Position): Tree = {
    FunctionDef(globalVarIdent(field, scope, origName), args, body)
  }

  def globalVarDef[T: Scope](field: String, scope: T, value: Tree,
      origName: OriginalName = NoOriginalName, mutable: Boolean = false)(
      implicit pos: Position): Tree = {
    genLet(globalVarIdent(field, scope, origName), mutable, value)
  }

  def globalVarDecl[T: Scope](field: String, scope: T,
      origName: OriginalName = NoOriginalName)(
      implicit pos: Position): Tree = {
    genEmptyMutableLet(globalVarIdent(field, scope, origName))
  }

  // Still public for field exports.
  def globalVarIdent[T](field: String, scope: T,
      origName: OriginalName = NoOriginalName)(
      implicit pos: Position, scopeType: Scope[T]): Ident = {
    genericIdent(field, scopeType.subField(scope), origName)
  }

  /** Dispatch based on type ref.
   *
   *  Returns the relevant coreJSLibVar for primitive types, globalVar otherwise.
   */
  def typeRefVar(field: String, typeRef: NonArrayTypeRef)(
      implicit pos: Position): Tree = {
    typeRef match {
      case primRef: PrimRef =>
        globalVar(field, primRef)

      case ClassRef(className) =>
        globalVar(field, className)
    }
  }

  def fileLevelVar(field: String, subField: String,
      origName: OriginalName = NoOriginalName)(
      implicit pos: Position): VarRef = {
    VarRef(fileLevelVarIdent(field, subField, origName))
  }

  def fileLevelVar(field: String)(implicit pos: Position): VarRef =
    VarRef(fileLevelVarIdent(field))

  def fileLevelVarIdent(field: String, subField: String,
      origName: OriginalName = NoOriginalName)(
      implicit pos: Position): Ident = {
    genericIdent(field, subField, origName)
  }

  def fileLevelVarIdent(field: String)(implicit pos: Position): Ident =
    fileLevelVarIdent(field, NoOriginalName)

  def fileLevelVarIdent(field: String, origName: OriginalName)(
      implicit pos: Position): Ident = {
    genericIdent(field, "", origName)
  }

  private def genericIdent(field: String, subField: String,
      origName: OriginalName = NoOriginalName)(
      implicit pos: Position): Ident = {
    val name =
      if (subField == "") "$" + field
      else "$" + field + "_" + subField

    Ident(avoidClashWithGlobalRef(name), origName)
  }

  private def avoidClashWithGlobalRef(codegenVarName: String): String = {
    /* This is not cached because it should virtually never happen.
     * slowPath() is only called if we use a dangerous global ref, which should
     * already be very rare. And if do a second iteration in the loop only if
     * we refer to the global variables `$foo` *and* `$$foo`. At this point the
     * likelihood is so close to 0 that caching would be more expensive than
     * not caching.
     */
    @tailrec
    def slowPath(lastNameTried: String): String = {
      val nextNameToTry = "$" + lastNameTried
      if (mentionedDangerousGlobalRefs.contains(nextNameToTry))
        slowPath(nextNameToTry)
      else
        nextNameToTry
    }

    /* Hopefully this is JIT'ed away as `false` because
     * `mentionedDangerousGlobalRefs` is in fact `Set.EmptySet`.
     */
    if (mentionedDangerousGlobalRefs.contains(codegenVarName))
      slowPath(codegenVarName)
    else
      codegenVarName
  }

  /** Scopes a globalVar to a certain sub field. */
  trait Scope[T] {
    def subField(x: T): String
  }

  /** Marker value for a CoreJSLibVar. */
  object CoreVar

  object Scope {
    implicit object ClassScope extends Scope[ClassName] {
      def subField(x: ClassName): String = genName(x)
    }

    implicit object FieldScope extends Scope[(ClassName, FieldName)] {
      def subField(x: (ClassName, FieldName)): String =
        genName(x._1) + "__" + genName(x._2)
    }

    implicit object MethodScope extends Scope[(ClassName, MethodName)] {
      def subField(x: (ClassName, MethodName)): String =
        genName(x._1) + "__" + genName(x._2)
    }

    implicit object CoreJSLibScope extends Scope[CoreVar.type] {
      def subField(x: CoreVar.type): String = ""
    }

    /** The PrimRefScope is implied to be in the CoreJSLib. */
    implicit object PrimRefScope extends Scope[PrimRef] {
      def subField(x: PrimRef): String = {
        // The mapping in this function is an implementation detail of the emitter
        x.tpe match {
          case NoType      => "V"
          case BooleanType => "Z"
          case CharType    => "C"
          case ByteType    => "B"
          case ShortType   => "S"
          case IntType     => "I"
          case LongType    => "J"
          case FloatType   => "F"
          case DoubleType  => "D"
          case NullType    => "N"
          case NothingType => "E"
        }
      }
    }
  }
}
