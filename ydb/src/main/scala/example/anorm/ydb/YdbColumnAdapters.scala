package example.anorm.ydb

import java.sql.PreparedStatement
import java.time.LocalDate
import anorm.{Column, MetaDataItem, ToStatement, TypeDoesNotMatch}

/** Bridges between the YDB JDBC driver's type representations and what
  * Anorm expects.
  *
  * The YDB driver returns `java.time.LocalDate` directly for `Date` columns,
  * while Anorm's built-in `Column[LocalDate]` only accepts `java.sql.Date`
  * or `java.sql.Timestamp`. Similarly, Anorm's default `ToStatement[LocalDate]`
  * converts via `java.sql.Date`, but the YDB driver types that as `Timestamp`
  * rather than `Date`. These implicits fix both directions.
  */
object YdbColumnAdapters {

  implicit val localDateColumn: Column[LocalDate] =
    Column.nonNull[LocalDate] { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case ld: LocalDate        => Right(ld)
        case d: java.sql.Date     => Right(d.toLocalDate)
        case ts: java.sql.Timestamp => Right(ts.toLocalDateTime.toLocalDate)
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to LocalDate for column $qualified"
          ))
      }
    }

  implicit val localDateToStatement: ToStatement[LocalDate] =
    new ToStatement[LocalDate] {
      def set(s: PreparedStatement, index: Int, v: LocalDate): Unit =
        s.setDate(index, java.sql.Date.valueOf(v))
    }

  implicit val optLocalDateColumn: Column[Option[LocalDate]] =
    Column { (value, meta) =>
      val MetaDataItem(qualified, nullable, _) = meta
      value match {
        case null                   => Right(None)
        case ld: LocalDate          => Right(Some(ld))
        case d: java.sql.Date       => Right(Some(d.toLocalDate))
        case ts: java.sql.Timestamp => Right(Some(ts.toLocalDateTime.toLocalDate))
        case _ if nullable          => Right(None)
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to Option[LocalDate] for column $qualified"
          ))
      }
    }
}
