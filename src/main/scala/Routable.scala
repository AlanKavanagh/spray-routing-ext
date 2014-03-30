package spray.routing.ext

import scala.language.experimental.macros
import scala.reflect.macros.Context

import spray.routing._
import scala.language.implicitConversions

/**
 * Object for transformation String* into List
 * {{{
 *   exclude("index")
 * }}}
 */
object exclude {
  def apply(xs: String*) = xs.toList
}

/**
 * Trait contatin methods for resourse implementation.
 *  With resourse method you might quick create routes for you controller. Also map form information onto Model.
 *  {{{
 *    resourse[Controller, Model]
 *  }}}
 *   transform to
 *  {{{
 *    pathPrefix("model") {
 *      //methods for controller.index
 *      // controller.show
 *      // ...
 *    }
 *   }}}
 * Note: for `new` method in controller use `fresh` name.
 */
trait Routable extends HttpService with HttpMethods with HttpHelpers with Helpers {

  /** Define routes without excluded pathes.
   * {{{
   *   resourse[Controller, Model](exclude("index", "show", "new"))
   * }}}
   * @param exclude - list with excluded methods (index, show, ...)
   * @tparam C - you controller
   * @tparam M - you model
   * @return [[Route]]
   */
  def resourse[C, M](exclude: List[String])  = macro RoutableImpl.resourse0Impl[C, M]

  /** Define routes with nested block
   *  {{{
   *    resourse[Controller, Model] {
   *      get0[Controller]("other")
   *    }
   *  }}}
   * @param block [[Route]] - block with nested routes
   * @tparam C - you controller
   * @tparam M - you model
   * @return [[Route]]
   */
  def resourse[C, M](block: Route) = macro RoutableImpl.resourse1Impl[C, M]

  /** Define routes without excluded actions, and nested block
   *  {{{
   *    resourse[Controller, Model](exclude("index"), {
   *      get0[Controller]("other")
   *    })
   *  }}}
   * @param exclude - excluded actions
   * @param block [[Route]] - nested block
   * @tparam C - you controller
   * @tparam M - you model
   * @return [[Route]]
   */
  def resourse[C, M](exclude: List[String], block: Route) = macro RoutableImpl.resourseImpl[C, M]

  /** Simple define routes
   * {{{
   *   resourse[Controller, Model]
   * }}}
   * @tparam C - you controller
   * @tparam M - you model
   * @return [[Route]]
   */
  def resourse[C, M] = macro RoutableImpl.resourse4Impl[C, M]
}

/** Object, which contatain resourse implementation.
 *
 */
object RoutableImpl {
  import spray.routing.Route

  def resourse4Impl[C: c.WeakTypeTag, M: c.WeakTypeTag](c: Context): c.Expr[Route] = {
    import c.universe._

    val route = q"""resourse[${c.weakTypeOf[C]}, ${c.weakTypeOf[M]}](List[String]())"""
    c.Expr[Route](route)
  }

  def resourse1Impl[C: c.WeakTypeTag, M: c.WeakTypeTag](c: Context)
                   (block: c.Expr[Route]): c.Expr[Route] = {
    import c.universe._

    val route = q"""resourse[${c.weakTypeOf[C]}, ${c.weakTypeOf[M]}](List[String](), $block)"""
    c.Expr[Route](route)
  }

  def resourse0Impl[C: c.WeakTypeTag, M: c.WeakTypeTag](c: Context)
                   (exclude: c.Expr[List[String]]): c.Expr[Route] = {
    import c.universe._

    val startPath = convertToPath(s"${c.weakTypeOf[M].typeSymbol.name.toString}")

    val result = getRoute[C, M](c)(exclude)

    val route = q"""
      pathPrefix($startPath) {
          $result
        }
    """

    c.Expr[Route](route)
  }
  def resourseImpl[C: c.WeakTypeTag, M: c.WeakTypeTag](c: Context)
                  (exclude: c.Expr[List[String]], block: c.Expr[Route]): c.Expr[Route] = {
    import c.universe._

    val startPath = convertToPath(s"${c.weakTypeOf[M].typeSymbol.name.toString}")

    val result = getRoute[C, M](c)(exclude)

    val route = q"""
      pathPrefix($startPath) {
          $result ~
          $block
        }
    """

    c.Expr[Route](route)
  }


  private def convertToPath(s: String) = {
    val r = "[A-Z]".r
    (r replaceAllIn(s"${s.head.toString.toLowerCase}${s.tail}", "-$0")).toLowerCase
  }

  private def getRoute[C: c.WeakTypeTag, M: c.WeakTypeTag](c: Context)
              (exclude: c.Expr[List[String]]): c.Expr[Route] = {
    import c.universe._

    val params = c.weakTypeOf[M].declarations.collect {
      case x: MethodSymbol if x.isConstructor =>
        x.paramss.map(_.map(_.asTerm))
    }.flatMap(_.flatten)

    if (params.exists(_.isParamWithDefault)) {
      c.warning(c.enclosingPosition, s"Class `${c.weakTypeOf[M]}` have parameter with default!")
    }

    val list = exclude.tree.collect {
      case Literal(Constant(x)) => s"$x"
    }.toList

    val paramNames = params.map(_.name.toString).map(Symbol(_))
    val extract = paramNames.zip(params.map(_.typeSignature)).map{
      case (s, t) =>
        if (t.<:<(typeOf[Option[_]]))
          q"${s}.?"
        else
          q"${s}.as[$t]"
    }.toList

    if (extract.isEmpty) {
      c.abort(c.enclosingPosition, s"Model `${c.weakTypeOf[M]}` should have a parameters!")
    }

    val model = newTermName(s"${c.weakTypeOf[M].typeSymbol.name}")
    val controller = c.weakTypeOf[C]

    val show   = q"""get0[$controller](IntNumber ~> "show")"""
    val index  = q"""get0[$controller]("index")"""
    val edit   = q"""get0[$controller]((IntNumber / "edit") ~> "edit")"""
    val update = q"""put0[$controller](IntNumber ~> "update")"""
    val delete = q"""delete0[$controller](IntNumber ~> "delete")"""


    //Refactor this, ~> htt
    val outerMethod = c.enclosingMethod

    val (sum: List[ValDef], names: List[Ident]) = if (outerMethod != null) {
        val vs = (outerMethod match {
          case DefDef(_, _, _, List(List(xs @ _*)), _, _) => xs
        }).asInstanceOf[List[ValDef]]

        val vvs = vs.map{case x: ValDef => q"val ${x.name}:${x.tpt}"}
        val requestVal = List(q"val request: spray.http.HttpRequest")

        val sum = requestVal ++ vvs

        val tmpNames = List("request0") ++ vs.map{x => s"${x.name}"}

        val names = tmpNames.collect{ case x =>Ident(newTermName(x))}
        (sum, names)
      } else {
        val requestVal = List(q"val request: spray.http.HttpRequest")
        val sum = requestVal

        val tmpNames = List("request0")

        val names = tmpNames.collect{ case x =>Ident(newTermName(x))}

        (sum, names)
    }
    val create = q"""
      requestInstance { request0 =>
        post {
            case class AnonClassController(..$sum) extends ${c.weakTypeOf[C]}
            val controller = new AnonClassController(..$names)

            formFields(..$extract).as($model) { (model) =>
              controller.create(model)
            }
          }
      }
    """

    val fresh = q"""get0[$controller]("new" ~> "fresh")"""

    val originalActions = List(
      ("delete", delete), ("edit", edit), ("show", show), ("update", update), ("new", fresh), ("create", create), ("index", index)
    )

    val excludeActions = originalActions.filter { x => list.contains(x._1)}

    val resultRoute = (originalActions diff excludeActions) map(_._2)

    val route = resultRoute.reduce((a,b) => q"$a ~ $b")
    c.Expr[Route](q"$route")
  }

}