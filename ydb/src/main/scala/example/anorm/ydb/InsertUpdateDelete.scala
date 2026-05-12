package example.anorm.ydb

import java.sql.Connection
import java.time.{LocalDate, LocalDateTime}
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Demonstrates DML operations with Anorm against YDB: UPSERT INTO (the
  * YDB-native idempotent write), INSERT with explicit IDs, UPDATE, DELETE,
  * and executeUpdate() for affected-row counts.
  *
  * YDB has no auto-increment / SERIAL columns, so callers supply IDs
  * explicitly — a common pattern in distributed databases.
  */
object InsertUpdateDelete {

  def insertDepartment(id: Int, name: String, location: String, budget: Double)(
      implicit c: Connection
  ): Int =
    SQL"UPSERT INTO departments(id, name, location, budget) VALUES ($id, $name, $location, $budget)"
      .executeUpdate()

  def insertEmployee(
      id: Int,
      firstName: String,
      lastName: String,
      email: String,
      hireDate: LocalDate,
      salary: Double,
      departmentId: Int,
      createdAt: LocalDateTime,
      rating: Option[Double] = None,
      bonusMultiplier: Double = 1.0
  )(implicit c: Connection): Int =
    SQL(
      """UPSERT INTO employees(id, first_name, last_name, email, hire_date, salary, department_id, is_active, created_at, rating, bonus_multiplier)
         VALUES ({id}, {fn}, {ln}, {email}, {hd}, {sal}, {did}, true, {cat}, {rat}, {bm})"""
    ).on(
        "id"    -> id,
        "fn"    -> firstName,
        "ln"    -> lastName,
        "email" -> email,
        "hd"    -> hireDate,
        "sal"   -> salary,
        "did"   -> departmentId,
        "cat"   -> createdAt,
        "rat"   -> rating,
        "bm"    -> bonusMultiplier
      )
      .executeUpdate()

  def insertProject(id: Int, name: String, budget: Double, startDate: LocalDate)(
      implicit c: Connection
  ): Int =
    SQL"UPSERT INTO projects(id, name, budget, start_date) VALUES ($id, $name, $budget, $startDate)"
      .executeUpdate()

  def updateSalary(employeeId: Int, newSalary: Double)(implicit c: Connection): Int =
    SQL"UPDATE employees SET salary = $newSalary WHERE id = $employeeId"
      .executeUpdate()

  def deactivateEmployee(employeeId: Int, note: String)(implicit c: Connection): Int =
    SQL"""UPDATE employees SET is_active = false, notes = $note
          WHERE id = $employeeId AND is_active = true"""
      .executeUpdate()

  def deleteProject(projectId: Int)(implicit c: Connection): Int =
    SQL"DELETE FROM projects WHERE id = $projectId".executeUpdate()

  /** YDB-native upsert: inserts a new row or replaces the existing one
    * with the same primary key — always idempotent.
    */
  def upsertDepartment(id: Int, name: String, location: String, budget: Double)(
      implicit c: Connection
  ): Int =
    SQL"UPSERT INTO departments(id, name, location, budget) VALUES ($id, $name, $location, $budget)"
      .executeUpdate()
}
