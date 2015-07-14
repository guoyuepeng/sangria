package sangria.execution

import sangria.parser.SourceMapper
import sangria.schema.{AbstractType, DirectiveContext, Schema, ObjectType}
import sangria.ast

import scala.util.{Try, Failure, Success}

class FieldExecutor[Ctx, Val](schema: Schema[Ctx, Val], variables: Map[String, Any], sourceMapper: Option[SourceMapper], valueExecutor: ValueExecutor[_]) {
  def collectFields(tpe: ObjectType[Ctx, Val], selections: List[ast.Selection]): Try[Map[String, Try[List[ast.Field]]]] =
    selections.foldLeft(Success(Map.empty) : Try[Map[String, Try[List[ast.Field]]]]) {
      case (f @ Failure(_), selection) => f
      case (s @ Success(acc), selection) =>
        selection match {
          case field @ ast.Field(_, _, _, dirs, _, _) =>
            val name = resultName(field)

            shouldIncludeNode(dirs, selection) match {
              case Success(true) => acc.get(name) match {
                case Some(Success(list)) => Success(acc.updated(name, Success(list :+ field)))
                case Some(Failure(_)) => s
                case None => Success(acc.updated(name, Success(field :: Nil)))
              }
              case Success(false) => s
              case Failure(error) => Success(acc.updated(name, Failure(error)))
            }
          case fragment @ ast.InlineFragment(typeCondition, dirs, fragmentSelections, _) =>
            for {
              shouldInclude <- shouldIncludeNode(dirs, selection)
              fragmentConditionMatch <- doesFragmentConditionMatch(tpe, fragment)
              fragmentFields <-
                if (shouldInclude && fragmentConditionMatch)
                  collectFields(tpe, fragmentSelections)
                else s
            } yield fragmentFields
//          case fragment @ ast.FragmentSpread(_, dirs, _) =>
        }
    }

  def resultName(field: ast.Field) = field.alias getOrElse field.name

  def shouldIncludeNode(directives: List[ast.Directive], selection: ast.Selection): Try[Boolean] = {
    val possibleDirs = directives
        .map(d => schema.directivesByName
          .get(d.name)
          .map(dd => selection match {
            case _: ast.Field if !dd.onField => Failure(new ExecutionError(s"Directive '${dd.name}' is not allowed to be used on fields", sourceMapper, d.position))
            case _: ast.InlineFragment | _: ast.FragmentSpread if !dd.onFragment =>
              Failure(new ExecutionError(s"Directive '${dd.name}' is not allowed to be used on fields", sourceMapper, d.position))
            case _ => Success(d -> dd)
          })
          .getOrElse(Failure(new ExecutionError(s"Directive '${d.name}' not found.", sourceMapper, d.position))))
        .map(_.flatMap{case (astDir, dir) => valueExecutor.getAttributeValues(dir.arguments, astDir.arguments, variables) map (dir -> _)})

    possibleDirs.collect{case Failure(error) => error}.headOption map (Failure(_)) getOrElse {
      val validDirs = possibleDirs collect {case Success(v) => v}
      val should = validDirs.forall { case (dir, args) => dir.shouldInclude(DirectiveContext(selection, dir, args)) }

      Success(should)
    }
  }

  def doesFragmentConditionMatch(tpe: ObjectType[_, _], conditional: ast.ConditionalFragment): Try[Boolean] =
    schema.outputTypes.get(conditional.typeCondition)
      .map(condTpe => Success(condTpe.name == tpe.name || (condTpe.isInstanceOf[AbstractType] && schema.isPossibleType(condTpe.name, tpe))))
      .getOrElse(Failure(new ExecutionError(s"Unknown type '${conditional.typeCondition}'.", sourceMapper, conditional.position)))
}
