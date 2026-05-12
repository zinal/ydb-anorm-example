package example.anorm.ydb

import java.sql.Connection
import anorm._
import anorm.SqlParser._

/** Demonstrates parameter binding techniques against YDB: named parameters
  * via on(), NamedParameter instances, multi-value IN clauses, optional
  * parameter handling, and SQL string interpolation.
  */
object ParameterBinding {

  def findByDepartmentName(deptName: String)(implicit c: Connection): List[String] =
    SQL("SELECT e.first_name FROM employees e JOIN departments d ON e.department_id = d.id WHERE d.name = {dept} ORDER BY e.first_name")
      .on("dept" -> deptName)
      .as(str("first_name").*)

  def findBySalaryRange(minSalary: BigDecimal, maxSalary: BigDecimal)(implicit c: Connection): List[String] =
    SQL("SELECT first_name FROM employees WHERE salary >= CAST({min} AS Decimal(12,2)) AND salary <= CAST({max} AS Decimal(12,2)) ORDER BY salary")
      .on("min" -> minSalary, "max" -> maxSalary)
      .as(str("first_name").*)

  def findByDepartmentId(deptId: Int)(implicit c: Connection): List[String] = {
    val params: Seq[NamedParameter] = Seq(
      NamedParameter("deptId", ParameterValue.toParameterValue(deptId))
    )
    SQL("SELECT first_name FROM employees WHERE department_id = {deptId} ORDER BY first_name")
      .on(params: _*)
      .as(str("first_name").*)
  }

  def findByIds(ids: Seq[Int])(implicit c: Connection): List[String] =
    SQL"SELECT first_name FROM employees WHERE id IN ($ids) ORDER BY first_name"
      .as(str("first_name").*)

  def findEmployees(maybeDeptId: Option[Int])(implicit c: Connection): List[String] =
    maybeDeptId match {
      case Some(deptId) =>
        SQL"SELECT first_name FROM employees WHERE department_id = $deptId ORDER BY first_name"
          .as(str("first_name").*)
      case None =>
        SQL"SELECT first_name FROM employees ORDER BY first_name"
          .as(str("first_name").*)
    }

  def searchByNamePrefix(prefix: String)(implicit c: Connection): List[String] = {
    val pattern = s"$prefix%"
    SQL"SELECT first_name FROM employees WHERE first_name LIKE $pattern ORDER BY first_name"
      .as(str("first_name").*)
  }

  def findActiveInDepartment(deptId: Int, active: Boolean)(implicit c: Connection): List[String] =
    SQL("SELECT first_name FROM employees WHERE department_id = {dept} AND is_active = {active} ORDER BY first_name")
      .on("dept" -> deptId, "active" -> active)
      .as(str("first_name").*)
}
