package example.anorm.ydb

import java.sql.PreparedStatement
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.util.UUID
import anorm.{Column, MetaDataItem, ToStatement, TypeDoesNotMatch}

/** Bridges between the YDB JDBC driver's type representations and what
  * Anorm expects.
  *
  * The YDB driver returns `java.time.LocalDate` directly for Date32 columns
  * and `java.time.LocalDateTime` for Timestamp64 columns (with
  * forceSignedDatetimes=true), while Anorm's built-in Column instances only
  * accept java.sql types. These implicits fix both directions for LocalDate,
  * LocalDateTime, and `java.util.UUID` for YDB `Uuid` columns.
  */
object YdbColumnAdapters {

  implicit val uuidColumn: Column[UUID] =
    Column.nonNull[UUID] { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case u: UUID    => Right(u)
        case s: String  =>
          try Right(UUID.fromString(s))
          catch {
            case _: IllegalArgumentException =>
              Left(TypeDoesNotMatch(s"Cannot parse UUID string for column $qualified"))
          }
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to UUID for column $qualified"
          ))
      }
    }

  implicit val uuidToStatement: ToStatement[UUID] =
    new ToStatement[UUID] {
      def set(s: PreparedStatement, index: Int, v: UUID): Unit =
        s.setObject(index, v)
    }

  implicit val bigDecimalColumn: Column[BigDecimal] =
    Column.nonNull[BigDecimal] { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case bd: java.math.BigDecimal => Right(BigDecimal(bd))
        case bd: BigDecimal           => Right(bd)
        case n: java.lang.Number      => Right(BigDecimal(n.doubleValue()))
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to BigDecimal for column $qualified"
          ))
      }
    }

  implicit val optBigDecimalColumn: Column[Option[BigDecimal]] =
    Column { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case null                     => Right(None)
        case bd: java.math.BigDecimal => Right(Some(BigDecimal(bd)))
        case bd: BigDecimal           => Right(Some(bd))
        case n: java.lang.Number      => Right(Some(BigDecimal(n.doubleValue())))
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to Option[BigDecimal] for column $qualified"
          ))
      }
    }

  implicit val localDateColumn: Column[LocalDate] =
    Column.nonNull[LocalDate] { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case ld: LocalDate          => Right(ld)
        case d: java.sql.Date       => Right(d.toLocalDate)
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
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case null                   => Right(None)
        case ld: LocalDate          => Right(Some(ld))
        case d: java.sql.Date       => Right(Some(d.toLocalDate))
        case ts: java.sql.Timestamp => Right(Some(ts.toLocalDateTime.toLocalDate))
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to Option[LocalDate] for column $qualified"
          ))
      }
    }

  implicit val localDateTimeColumn: Column[LocalDateTime] =
    Column.nonNull[LocalDateTime] { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case ldt: LocalDateTime     => Right(ldt)
        case inst: Instant          => Right(LocalDateTime.ofInstant(inst, ZoneOffset.UTC))
        case ts: java.sql.Timestamp => Right(ts.toLocalDateTime)
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to LocalDateTime for column $qualified"
          ))
      }
    }

  implicit val localDateTimeToStatement: ToStatement[LocalDateTime] =
    new ToStatement[LocalDateTime] {
      def set(s: PreparedStatement, index: Int, v: LocalDateTime): Unit =
        s.setTimestamp(index, java.sql.Timestamp.from(v.toInstant(ZoneOffset.UTC)))
    }

  implicit val optLocalDateTimeColumn: Column[Option[LocalDateTime]] =
    Column { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case null                   => Right(None)
        case ldt: LocalDateTime     => Right(Some(ldt))
        case inst: Instant          => Right(Some(LocalDateTime.ofInstant(inst, ZoneOffset.UTC)))
        case ts: java.sql.Timestamp => Right(Some(ts.toLocalDateTime))
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to Option[LocalDateTime] for column $qualified"
          ))
      }
    }
}
