package example.anorm.pg

import java.sql.Connection
import anorm._
import anorm.SqlParser._

/** Demonstrates fundamental Anorm query patterns: scalar queries, single/optional
  * row retrieval, list results, and SQL string interpolation.
  */
object BasicQueries {

  def countDepartments()(implicit c: Connection): Long =
    SQL"SELECT count(*) FROM departments".as(scalar[Long].single)

  def findDepartmentNameById(id: Int)(implicit c: Connection): Option[String] =
    SQL"SELECT name FROM departments WHERE id = $id"
      .as(str("name").singleOpt)

  def listAllDepartmentNames()(implicit c: Connection): List[String] =
    SQL"SELECT name FROM departments ORDER BY name"
      .as(str("name").*)

  def departmentExists(name: String)(implicit c: Connection): Boolean =
    SQL"SELECT count(*) FROM departments WHERE name = $name"
      .as(scalar[Long].single) > 0

  def findDepartmentWithMaxBudget()(implicit c: Connection): Option[(String, BigDecimal)] = {
    val parser = str("name") ~ get[BigDecimal]("budget") map {
      case n ~ b => (n, b)
    }
    SQL"SELECT name, budget FROM departments ORDER BY budget DESC LIMIT 1"
      .as(parser.singleOpt)
  }

  def listEmployeeEmailsByDepartment(deptName: String)(implicit c: Connection): List[String] =
    SQL"""SELECT e.email
          FROM employees e
          JOIN departments d ON e.department_id = d.id
          WHERE d.name = $deptName
          ORDER BY e.email"""
      .as(str("email").*)

  def getScalarBudgetSum()(implicit c: Connection): BigDecimal =
    SQL"SELECT COALESCE(SUM(budget), 0) FROM departments"
      .as(scalar[BigDecimal].single)
}
