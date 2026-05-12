package example.anorm.pg

import java.sql.Connection
import java.time.LocalDate
import anorm._
import anorm.Macro
import anorm.SqlParser._

/** Demonstrates Anorm macro-generated parsers: Macro.namedParser for direct
  * column-name-to-field mapping, ColumnNaming.SnakeCase for automatic
  * camelCase-to-snake_case translation, and Macro.indexedParser for
  * positional matching.
  */
object AnormMacros {

  // Fields match column names exactly -- works with namedParser out of the box.
  case class DepartmentRow(id: Int, name: String, location: Option[String], budget: BigDecimal)
  implicit val departmentRowParser: RowParser[DepartmentRow] = Macro.namedParser[DepartmentRow]

  // camelCase fields matched to snake_case columns via ColumnNaming.SnakeCase.
  case class EmployeeRow(
      id: Int,
      firstName: String,
      lastName: String,
      email: String,
      hireDate: LocalDate,
      salary: BigDecimal,
      departmentId: Option[Int],
      isActive: Boolean,
      notes: Option[String]
  )
  implicit val employeeRowParser: RowParser[EmployeeRow] =
    Macro.namedParser[EmployeeRow](Macro.ColumnNaming.SnakeCase)

  // Positional (indexed) parser -- fields are matched by SELECT column order.
  case class DeptSummary(name: String, location: Option[String])
  implicit val deptSummaryParser: RowParser[DeptSummary] = Macro.indexedParser[DeptSummary]

  // Parser for a joined projection with aliases matching field names.
  case class EmployeeBrief(name: String, dept: String)
  implicit val employeeBriefParser: RowParser[EmployeeBrief] = Macro.namedParser[EmployeeBrief]

  def getAllDepartments()(implicit c: Connection): List[DepartmentRow] =
    SQL"SELECT id, name, location, budget FROM departments ORDER BY id"
      .as(departmentRowParser.*)

  def getEmployeeById(id: Int)(implicit c: Connection): Option[EmployeeRow] =
    SQL"""SELECT id, first_name, last_name, email, hire_date, salary,
                 department_id, is_active, notes
          FROM employees WHERE id = $id"""
      .as(employeeRowParser.singleOpt)

  def getDeptSummaries()(implicit c: Connection): List[DeptSummary] =
    SQL"SELECT name, location FROM departments ORDER BY name"
      .as(deptSummaryParser.*)

  def getEmployeeBriefs()(implicit c: Connection): List[EmployeeBrief] =
    SQL"""SELECT e.first_name AS name, d.name AS dept
          FROM employees e
          JOIN departments d ON e.department_id = d.id
          ORDER BY e.first_name"""
      .as(employeeBriefParser.*)
}
