package example.anorm.pg

import java.sql.Connection
import java.time.LocalDate
import anorm._
import anorm.SqlParser._

/** Demonstrates DML operations with Anorm: executeInsert with generated key
  * retrieval, executeUpdate for UPDATE/DELETE, typed key parsers, and
  * PostgreSQL's ON CONFLICT (upsert).
  */
object InsertUpdateDelete {

  def insertDepartment(name: String, location: String, budget: BigDecimal)(
      implicit c: Connection
  ): Option[Long] =
    SQL"INSERT INTO departments(name, location, budget) VALUES ($name, $location, $budget)"
      .executeInsert()

  def insertEmployee(
      firstName: String,
      lastName: String,
      email: String,
      hireDate: LocalDate,
      salary: BigDecimal,
      departmentId: Int
  )(implicit c: Connection): Option[Long] =
    SQL(
      """INSERT INTO employees(first_name, last_name, email, hire_date, salary, department_id)
         VALUES ({fn}, {ln}, {email}, {hd}, {sal}, {did})"""
    ).on(
        "fn"    -> firstName,
        "ln"    -> lastName,
        "email" -> email,
        "hd"    -> hireDate,
        "sal"   -> salary,
        "did"   -> departmentId
      )
      .executeInsert()

  def insertProjectReturningInt(name: String, budget: BigDecimal, startDate: LocalDate)(
      implicit c: Connection
  ): Option[Int] =
    SQL"INSERT INTO projects(name, budget, start_date) VALUES ($name, $budget, $startDate)"
      .executeInsert(scalar[Int].singleOpt)

  def updateSalary(employeeId: Int, newSalary: BigDecimal)(implicit c: Connection): Int =
    SQL"UPDATE employees SET salary = $newSalary WHERE id = $employeeId"
      .executeUpdate()

  def deactivateEmployee(employeeId: Int, note: String)(implicit c: Connection): Int =
    SQL"""UPDATE employees SET is_active = false, notes = $note
          WHERE id = $employeeId AND is_active = true"""
      .executeUpdate()

  def deleteProject(projectId: Int)(implicit c: Connection): Int =
    SQL"DELETE FROM projects WHERE id = $projectId".executeUpdate()

  def upsertDepartment(name: String, location: String, budget: BigDecimal)(
      implicit c: Connection
  ): Int =
    SQL"""INSERT INTO departments(name, location, budget)
          VALUES ($name, $location, $budget)
          ON CONFLICT (name)
          DO UPDATE SET location = EXCLUDED.location, budget = EXCLUDED.budget"""
      .executeUpdate()
}
