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

package org.scalajs.core.compiler.test

import util._

import org.junit.Test
import org.junit.Assert._

import org.scalajs.core.ir.{Trees => js}

class MatchASTTest extends JSASTTest {

  @Test
  def stripIdentityMatchEndNonUnitResult: Unit = {
    """
    object A {
      def foo = "a" match {
        case "a" => true
        case "b" => false
      }
    }
    """.hasExactly(1, "local variable") {
      case js.VarDef(_, _, _, _) =>
    }
  }

  @Test
  def stripIdentityMatchEndUnitResult: Unit = {
    """
    object A {
      def foo = "a" match {
        case "a" =>
        case "b" =>
      }
    }
    """.hasExactly(1, "local variable") {
      case js.VarDef(_, _, _, _) =>
    }
  }

}
