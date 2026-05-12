package example.anorm.ydb

import java.sql.{Connection, PreparedStatement}
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Demonstrates custom Column parsers and ToStatement instances for
  * user-defined types, value-level transformations inside parsers,
  * and column aliasing — all against YDB.
  */
object ColumnMappings {

  case class Email(value: String) extends AnyVal

  implicit val emailColumn: Column[Email] =
    Column.nonNull[Email] { (value, meta) =>
      val MetaDataItem(qualified, _, _) = meta
      value match {
        case s: String => Right(Email(s))
        case _ =>
          Left(TypeDoesNotMatch(
            s"Cannot convert $value:${value.getClass} to Email for column $qualified"
          ))
      }
    }

  implicit val emailToStatement: ToStatement[Email] = new ToStatement[Email] {
    def set(s: PreparedStatement, index: Int, email: Email): Unit =
      s.setString(index, email.value)
  }

  sealed trait SalaryLevel
  case object Junior    extends SalaryLevel
  case object Mid       extends SalaryLevel
  case object Senior    extends SalaryLevel
  case object Principal extends SalaryLevel

  private def classifySalary(salary: BigDecimal): SalaryLevel = salary match {
    case s if s < BigDecimal(70000)  => Junior
    case s if s < BigDecimal(90000)  => Mid
    case s if s < BigDecimal(110000) => Senior
    case _                           => Principal
  }

  val salaryLevelParser: RowParser[(String, SalaryLevel)] =
    str("first_name") ~ get[BigDecimal]("salary") map {
      case name ~ salary => (name, classifySalary(salary))
    }

  def getAllEmails()(implicit c: Connection): List[Email] =
    SQL"SELECT email FROM employees ORDER BY email"
      .as(get[Email]("email").*)

  def findByEmail(email: Email)(implicit c: Connection): Option[String] =
    SQL"SELECT first_name FROM employees WHERE email = $email"
      .as(str("first_name").singleOpt)

  def getEmployeeSalaryLevels()(implicit c: Connection): List[(String, SalaryLevel)] =
    SQL"SELECT first_name, salary FROM employees WHERE is_active = true ORDER BY salary"
      .as(salaryLevelParser.*)

  def getDepartmentAliased(id: Int)(implicit c: Connection): Option[(String, String)] = {
    val parser = str("dept_name") ~ str("dept_location") map SqlParser.flatten
    SQL"""SELECT name AS dept_name, COALESCE(location, 'Unknown') AS dept_location
          FROM departments WHERE id = $id"""
      .as(parser.singleOpt)
  }
}
