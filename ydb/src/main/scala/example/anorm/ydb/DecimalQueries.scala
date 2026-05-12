package example.anorm.ydb

import java.sql.Connection
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Demonstrates working with Decimal(p,s) columns in YDB: reading exact
  * decimal values, writing and re-reading for precision validation,
  * filtering by range, and aggregate functions on Decimal data.
  */
object DecimalQueries {

  def getDepartmentBudget(deptId: Int)(implicit c: Connection): Option[BigDecimal] =
    SQL"SELECT budget FROM departments WHERE id = $deptId"
      .as(get[BigDecimal]("budget").singleOpt)

  def getEmployeeSalary(empId: Int)(implicit c: Connection): Option[BigDecimal] =
    SQL"SELECT salary FROM employees WHERE id = $empId"
      .as(get[BigDecimal]("salary").singleOpt)

  def findDepartmentsByBudgetRange(min: BigDecimal, max: BigDecimal)(
      implicit c: Connection
  ): List[(String, BigDecimal)] = {
    val parser = str("name") ~ get[BigDecimal]("budget") map SqlParser.flatten
    SQL("SELECT name, budget FROM departments WHERE budget >= CAST({min} AS Decimal(15,2)) AND budget <= CAST({max} AS Decimal(15,2)) ORDER BY budget")
      .on("min" -> min, "max" -> max)
      .as(parser.*)
  }

  def findEmployeesBySalaryAbove(threshold: BigDecimal)(
      implicit c: Connection
  ): List[(String, BigDecimal)] = {
    val parser = str("first_name") ~ get[BigDecimal]("salary") map SqlParser.flatten
    SQL("SELECT first_name, salary FROM employees WHERE salary > CAST({threshold} AS Decimal(12,2)) ORDER BY salary")
      .on("threshold" -> threshold)
      .as(parser.*)
  }

  def sumDepartmentBudgets()(implicit c: Connection): BigDecimal =
    SQL"""SELECT COALESCE(SUM(budget), Decimal("0", 15, 2)) AS total FROM departments"""
      .as(get[BigDecimal]("total").single)

  def avgEmployeeSalary()(implicit c: Connection): Option[BigDecimal] =
    SQL"SELECT AVG(salary) AS avg_sal FROM employees"
      .as(get[Option[BigDecimal]]("avg_sal").single)

  def maxDepartmentBudget()(implicit c: Connection): Option[BigDecimal] =
    SQL"SELECT MAX(budget) AS max_b FROM departments"
      .as(get[Option[BigDecimal]]("max_b").single)

  def minEmployeeSalary()(implicit c: Connection): Option[BigDecimal] =
    SQL"SELECT MIN(salary) AS min_s FROM employees"
      .as(get[Option[BigDecimal]]("min_s").single)

  def updateDepartmentBudget(deptId: Int, newBudget: BigDecimal)(implicit c: Connection): Int =
    SQL("UPDATE departments SET budget = CAST({budget} AS Decimal(15,2)) WHERE id = {id}")
      .on("budget" -> newBudget, "id" -> deptId)
      .executeUpdate()

  def insertAndReadBackSalary(
      empId: Int, firstName: String, lastName: String, email: String,
      salary: BigDecimal, deptId: Int
  )(implicit c: Connection): BigDecimal = {
    SQL(
      """UPSERT INTO employees(id, first_name, last_name, email, hire_date, salary,
              department_id, is_active, created_at, bonus_multiplier)
            VALUES ({id}, {fn}, {ln}, {email},
              CAST(CurrentUtcDate() AS Date32), CAST({sal} AS Decimal(12,2)), {did}, true,
              CAST(CurrentUtcTimestamp() AS Timestamp64), 1.0)"""
    ).on(
        "id" -> empId, "fn" -> firstName, "ln" -> lastName,
        "email" -> email, "sal" -> salary, "did" -> deptId
      )
      .executeUpdate()
    SQL"SELECT salary FROM employees WHERE id = $empId"
      .as(get[BigDecimal]("salary").single)
  }
}
