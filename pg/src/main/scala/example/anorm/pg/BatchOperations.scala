package example.anorm.pg

import java.sql.Connection
import anorm._
import anorm.SqlParser._

/** Demonstrates BatchSql for efficient multi-row DML: batch inserts with
  * a first + rest parameter list, and batch updates.
  */
object BatchOperations {

  def batchInsertDepartments(
      departments: Seq[(String, String, BigDecimal)]
  )(implicit c: Connection): Array[Int] = {
    require(departments.nonEmpty, "Need at least one department")
    val (firstName, firstLoc, firstBudget) = departments.head
    val batch = BatchSql(
      "INSERT INTO departments(name, location, budget) VALUES ({name}, {location}, {budget})",
      Seq[NamedParameter]("name" -> firstName, "location" -> firstLoc, "budget" -> firstBudget),
      departments.tail.map { case (name, loc, budget) =>
        Seq[NamedParameter]("name" -> name, "location" -> loc, "budget" -> budget)
      }: _*
    )
    batch.execute()
  }

  def batchUpdateSalaries(
      updates: Seq[(Int, BigDecimal)]
  )(implicit c: Connection): Array[Int] = {
    require(updates.nonEmpty, "Need at least one update")
    val (firstId, firstSalary) = updates.head
    val batch = BatchSql(
      "UPDATE employees SET salary = {salary} WHERE id = {id}",
      Seq[NamedParameter]("id" -> firstId, "salary" -> firstSalary),
      updates.tail.map { case (id, salary) =>
        Seq[NamedParameter]("id" -> id, "salary" -> salary)
      }: _*
    )
    batch.execute()
  }

  def batchInsertEmployeeProjects(
      assignments: Seq[(Int, Int, String)]
  )(implicit c: Connection): Array[Int] = {
    require(assignments.nonEmpty, "Need at least one assignment")
    val (firstEmp, firstProj, firstRole) = assignments.head
    val batch = BatchSql(
      "INSERT INTO employee_projects(employee_id, project_id, role) VALUES ({eid}, {pid}, {role})",
      Seq[NamedParameter]("eid" -> firstEmp, "pid" -> firstProj, "role" -> firstRole),
      assignments.tail.map { case (eid, pid, role) =>
        Seq[NamedParameter]("eid" -> eid, "pid" -> pid, "role" -> role)
      }: _*
    )
    batch.execute()
  }

  def countDepartments()(implicit c: Connection): Long =
    SQL"SELECT count(*) FROM departments".as(scalar[Long].single)

  def getSalary(employeeId: Int)(implicit c: Connection): BigDecimal =
    SQL"SELECT salary FROM employees WHERE id = $employeeId"
      .as(get[BigDecimal]("salary").single)
}
