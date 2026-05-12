package example.anorm.ydb

import java.sql.Connection
import java.time.{LocalDate, LocalDateTime}
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Demonstrates building and composing RowParsers against YDB: manual
  * construction with get[T], built-in helpers (str, int, bool), the ~
  * combinator, map, flatten, and parsers for joined / aggregate queries.
  */
object RowParsers {

  val departmentParser: RowParser[Department] =
    get[Int]("id") ~
      get[String]("name") ~
      get[Option[String]]("location") ~
      get[BigDecimal]("budget") map {
        case id ~ name ~ location ~ budget =>
          Department(id, name, location, budget)
      }

  val employeeParser: RowParser[Employee] =
    int("id") ~
      str("first_name") ~
      str("last_name") ~
      str("email") ~
      get[LocalDate]("hire_date") ~
      get[BigDecimal]("salary") ~
      get[Option[Int]]("department_id") ~
      bool("is_active") ~
      get[Option[String]]("notes") ~
      get[LocalDateTime]("created_at") ~
      get[Option[Double]]("rating") ~
      get[Double]("bonus_multiplier") map {
        case id ~ fn ~ ln ~ email ~ hd ~ sal ~ did ~ active ~ notes ~ cat ~ rat ~ bm =>
          Employee(id, fn, ln, email, hd, sal, did, active, notes, cat, rat, bm)
      }

  val nameAndSalaryParser: RowParser[(String, BigDecimal)] =
    (str("first_name") ~ get[BigDecimal]("salary")).map(SqlParser.flatten)

  case class EmployeeWithDepartment(employee: Employee, departmentName: String)

  val employeeWithDeptParser: RowParser[EmployeeWithDepartment] =
    employeeParser ~ str("dept_name") map {
      case emp ~ deptName => EmployeeWithDepartment(emp, deptName)
    }

  val deptEmployeeCountParser: RowParser[(String, Long)] =
    str("name") ~ get[Long]("cnt") map SqlParser.flatten

  def getAllDepartments()(implicit c: Connection): List[Department] =
    SQL"SELECT id, name, location, budget FROM departments ORDER BY id"
      .as(departmentParser.*)

  def getEmployeeById(id: Int)(implicit c: Connection): Option[Employee] =
    SQL"""SELECT id, first_name, last_name, email, hire_date, salary,
                 department_id, is_active, notes, created_at,
                 rating, bonus_multiplier
          FROM employees WHERE id = $id"""
      .as(employeeParser.singleOpt)

  def getNamesAndSalaries()(implicit c: Connection): List[(String, BigDecimal)] =
    SQL"SELECT first_name, salary FROM employees ORDER BY salary DESC"
      .as(nameAndSalaryParser.*)

  def getEmployeeWithDepartment(empId: Int)(implicit c: Connection): Option[EmployeeWithDepartment] =
    SQL"""SELECT e.id, e.first_name, e.last_name, e.email, e.hire_date,
                 e.salary, e.department_id, e.is_active, e.notes,
                 e.created_at, e.rating, e.bonus_multiplier,
                 d.name AS dept_name
          FROM employees e
          JOIN departments d ON e.department_id = d.id
          WHERE e.id = $empId"""
      .as(employeeWithDeptParser.singleOpt)

  def getEmployeeCountByDepartment()(implicit c: Connection): List[(String, Long)] =
    SQL"""SELECT d.name, count(e.id) AS cnt
          FROM departments d
          LEFT JOIN employees e ON d.id = e.department_id
          GROUP BY d.name
          ORDER BY d.name"""
      .as(deptEmployeeCountParser.*)
}
