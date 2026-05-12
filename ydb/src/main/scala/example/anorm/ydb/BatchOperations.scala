package example.anorm.ydb

import java.sql.Connection
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Demonstrates BatchSql for efficient multi-row DML against YDB:
  * batch inserts (upserts) and batch updates.
  */
object BatchOperations {

  def batchInsertDepartments(
      departments: Seq[(Int, String, String, BigDecimal)]
  )(implicit c: Connection): Array[Int] = {
    require(departments.nonEmpty, "Need at least one department")
    val (firstId, firstName, firstLoc, firstBudget) = departments.head
    val batch = BatchSql(
      "UPSERT INTO departments(id, name, location, budget) VALUES ({id}, {name}, {location}, CAST({budget} AS Decimal(15,2)))",
      Seq[NamedParameter]("id" -> firstId, "name" -> firstName, "location" -> firstLoc, "budget" -> firstBudget),
      departments.tail.map { case (id, name, loc, budget) =>
        Seq[NamedParameter]("id" -> id, "name" -> name, "location" -> loc, "budget" -> budget)
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
      "UPDATE employees SET salary = CAST({salary} AS Decimal(12,2)) WHERE id = {id}",
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
      "UPSERT INTO employee_projects(employee_id, project_id, role, assigned_date) VALUES ({eid}, {pid}, {role}, CAST(CurrentUtcDate() AS Date32))",
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
