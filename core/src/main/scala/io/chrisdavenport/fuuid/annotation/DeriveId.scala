package io.chrisdavenport.fuuid.annotation

import com.github.ghik.silencer.silent

import scala.annotation.{compileTimeOnly, StaticAnnotation}
import scala.reflect.macros.whitebox

/**
 * This annotation can be used on any kind of object to automatically
 * create an inner `Id` tagged `FUUID` type with convenient methods for
 * its creation. It also provides implicit instances for cats' `Hash`,
 * `Order` and `Show` type-classes. All these instances are available
 * in the enclosing object.
 *
 * The `deriveMeta` parameter can be used to control whether to
 * automatically derive (by setting it to `true`) a
 * [[https://git.io/fj64d doobie's Meta]]
 * instance for the generated `Id` type (a dependency with `fuuid-doobie`
 * and `doobie-postgres` will be necessary).
 *
 * @example For an object named `User` {{{
 * object User {
 *
 *    trait IdTag
 *
 *    type Id = shapeless.tag.@@[FUUID, IdTag]
 *
 *    object Id {
 *
 *        //Creates a new `Id` from a `FUUID`
 *        def apply(fuuid: FUUID): User.Id = ???
 *
 *        //Creates a new `Id` from an `UUID` literal. This method
 *        //uses a macro to compile check the literal value
 *        def apply(s: String): User.Id = ???
 *
 *
 *        //Creates a random `Id` wrapped in an `F`
 *        def random[F[_]: cats.effect.Sync]: F[User.Id] = ???
 *
 *        object Unsafe {
 *
 *          //Creates an unwrapped random `Id`
 *          def random: User.Id = ???
 *
 *        }
 *
 *    }
 *
 *    implicit val IdHashOrderShowInstances: Hash[User.Id]
 *      with Order[User.Id] with Show[User.Id] = ???
 *
 *    //If `deriveMeta` is `true`
 *    implicit val IdMetaInstance: Meta[User.Id] = ???
 *
 * }
 * }}}
 * @param deriveMeta Set this value to `true` to automatically derive a
 *                   [[https://git.io/fj64d doobie's Meta]]
 *                   instance for the generated `Id` type (a dependency
 *                   with `fuuid-doobie` and `doobie-postgres` will be
 *                   necessary). Defaults to `false`.
 */
@compileTimeOnly("enable macro paradise to expand macro annotations")
@silent("never used")
class DeriveId(deriveMeta: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro DeriveIdMacros.impl
}

object DeriveIdMacros {

  def fuuidLiteral(c: whitebox.Context)(s: c.Expr[String]): c.Tree = {
    import c.universe._

    q"${c.prefix}.apply(_root_.io.chrisdavenport.fuuid.FUUID.fuuid($s))"
  }

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  def impl(c: whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._

    val deriveMeta: Boolean = c.prefix.tree match {
      case q"new DeriveId(${b: Boolean})" => b
      case q"new DeriveId(deriveMeta = ${b: Boolean})" => b
      case q"new DeriveId" => false
      case _ => c.abort(c.enclosingPosition, "unexpected annotation pattern!")
    }

    val (name, parents, body) = (annottees map (_.tree)).headOption collect {
      case q"object $name extends ..$parents { ..$body }" => (name, parents, body)
    } getOrElse c.abort(c.enclosingPosition, "@DeriveId can only be used with objects")

    val metaInstance =
      if (deriveMeta) q"""implicit val IdMetaInstance: _root_.doobie.util.Meta[$name.Id] =
          _root_.io.chrisdavenport.fuuid.doobie.implicits.FuuidType(_root_.doobie.postgres.implicits.UuidType)
            .timap($name.Id.apply)(identity)"""
      else q""

    c.Expr[Any](q"""
      @SuppressWarnings(Array("org.wartremover.warts.All"))
      object $name extends ..$parents {

        trait IdTag
      
        type Id = _root_.shapeless.tag.@@[_root_.io.chrisdavenport.fuuid.FUUID, IdTag]
    
        object Id {
    
          def apply(fuuid: _root_.io.chrisdavenport.fuuid.FUUID): $name.Id =
            _root_.shapeless.tag[IdTag][_root_.io.chrisdavenport.fuuid.FUUID](fuuid)
    
          def apply(s: String): $name.Id =
            macro _root_.io.chrisdavenport.fuuid.annotation.DeriveIdMacros.fuuidLiteral
    
          def random[F[_]: _root_.cats.effect.Sync]: F[$name.Id] =
            _root_.cats.effect.Sync[F].map(_root_.io.chrisdavenport.fuuid.FUUID.randomFUUID[F])(apply)
    
          object Unsafe {
            def random: $name.Id =
              Id(_root_.io.chrisdavenport.fuuid.FUUID.randomFUUID[_root_.cats.effect.IO].unsafeRunSync())
          }
          
        }

        implicit val IdHashOrderShowInstances: _root_.cats.Hash[$name.Id] with _root_.cats.Order[$name.Id] with _root_.cats.Show[$name.Id] =
          new _root_.cats.Hash[$name.Id] with _root_.cats.Order[$name.Id] with _root_.cats.Show[$name.Id] {
            override def show(t: $name.Id): String = t.show
            override def eqv(x: $name.Id, y: $name.Id): Boolean = x.eqv(y)
            override def hash(x: $name.Id): Int = x.hashCode
            override def compare(x: $name.Id, y: $name.Id): Int = x.compare(y)
          }
          
        $metaInstance
        
        ..$body
      }
    """)
  }
}
