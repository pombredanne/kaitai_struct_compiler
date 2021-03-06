package io.kaitai.struct.precompile

import io.kaitai.struct.ClassTypeProvider
import io.kaitai.struct.datatype.DataType
import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.format._
import io.kaitai.struct.translators.TypeDetector

import scala.reflect.ClassTag

/**
  * Validates all expressions used inside the given ClassSpec to use expected types.
  * @param topClass class to start check with
  */
class TypeValidator(topClass: ClassSpec) {
  val provider = new ClassTypeProvider(topClass)
  val detector = new TypeDetector(provider)

  /**
    * Starts the check from top-level class.
    */
  def run(): Unit =
    validateClass(topClass)

  /**
    * Performs validation of a single ClassSpec: would validate
    * sequence attributes (`seq`), instances (`instances`) and all
    * nested subtypes (`types`) recursively. `doc` and `enums` are
    * not checked, as they contain no expressions.
    * @param curClass class to check
    */
  def validateClass(curClass: ClassSpec): Unit = {
    provider.nowClass = curClass

    curClass.seq.foreach(validateAttr)

    curClass.instances.foreach { case (_, inst) =>
      inst match {
        case pis: ParseInstanceSpec =>
          validateAttr(pis)
        case vis: ValueInstanceSpec =>
          // TODO
      }
    }

    curClass.types.foreach { case (_, nestedClass) =>
      validateClass(nestedClass)
    }
  }

  /**
    * Performs validation of a single parsed attribute (either from a sequence
    * or a parse instance).
    * @param attr attribute to check
    */
  def validateAttr(attr: AttrLikeSpec) {
    val path = attr.path

    attr.cond.ifExpr.foreach((ifExpr) =>
      checkAssert[BooleanType](ifExpr, "boolean", path, "if")
    )

    provider._currentIteratorType = Some(attr.dataType)
    attr.cond.repeat match {
      case RepeatExpr(expr) =>
        checkAssert[IntType](expr, "integer", path, "repeat-expr")
      case RepeatUntil(expr) =>
        checkAssert[BooleanType](expr, "boolean", path, "repeat-until")
      case RepeatEos | NoRepeat =>
        // good
    }

    validateDataType(attr.dataType, path)
  }

  /**
    * Validates single non-composite data type, checking all expressions
    * inside data type definition.
    * @param dataType data type to check
    * @param path original .ksy path to make error messages more meaningful
    */
  def validateDataType(dataType: DataType, path: List[String]) {
    dataType match {
      case blt: BytesLimitType =>
        checkAssert[IntType](blt.size, "integer", path, "size")
      case st: StrFromBytesType =>
        validateDataType(st.bytes, path)
      case ut: UserTypeFromBytes =>
        validateDataType(ut.bytes, path)
      case st: SwitchType =>
        validateSwitchType(st, path)
      case _ =>
        // all other types don't need any specific checks
    }
  }

  def validateSwitchType(st: SwitchType, path: List[String]) {
    val onType = detector.detectType(st.on)
    st.cases.foreach { case (caseExpr, caseType) =>
      val casePath = path ++ List("type", "cases", caseExpr.toString)
      if (caseExpr != SwitchType.ELSE_CONST) {
        try {
          TypeDetector.assertCompareTypes(onType, detector.detectType(caseExpr), Ast.cmpop.Eq)
        } catch {
          case tme: TypeMismatchError =>
            throw new YAMLParseException(tme.getMessage, casePath)
        }
      }
      validateDataType(caseType, casePath)
    }
  }

  /**
    * Checks that expression's type conforms to a given datatype, otherwise
    * throw a human-readable exception, with some pointers that would help
    * finding the expression in source .ksy.
    *
    * Note: `T: Manifest` is required due to JVM type erasure. See
    * http://stackoverflow.com/a/42533114 for more info.
    *
    * @param expr expression to check
    * @param expectStr string to include
    * @param path path to expression base
    * @param pathKey key that contains expression in given path
    * @tparam T type that expression must conform to
    */
  def checkAssert[T: ClassTag](
    expr: Ast.expr,
    expectStr: String,
    path: List[String],
    pathKey: String
  ): Unit = {
    try {
      detector.detectType(expr) match {
        case _: T => // good
        case st: SwitchType =>
          st.combinedType match {
            case _: T => // good
            case actual =>
              throw YAMLParseException.exprType(expectStr, actual, path ++ List(pathKey))
          }
        case actual => throw YAMLParseException.exprType(expectStr, actual, path ++ List(pathKey))
      }
    } catch {
      case err: InvalidIdentifier =>
        throw new ErrorInInput(err, path ++ List(pathKey))
      case err: ExpressionError =>
        throw new ErrorInInput(err, path ++ List(pathKey))
    }
  }
}
