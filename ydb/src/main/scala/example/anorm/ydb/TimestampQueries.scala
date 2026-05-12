package example.anorm.ydb

import java.sql.Connection
import java.time.{LocalDate, LocalDateTime}
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Demonstrates working with Timestamp64 columns in YDB: fetching timestamps,
  * inserting rows with explicit timestamps, and filtering by timestamp ranges.
  */
object TimestampQueries {

  def getEmployeeCreatedAt(empId: Int)(implicit c: Connection): Option[LocalDateTime] =
    SQL"SELECT created_at FROM employees WHERE id = $empId"
      .as(get[LocalDateTime]("created_at").singleOpt)

  def getAllCreatedTimestamps()(implicit c: Connection): List[(String, LocalDateTime)] = {
    val parser = str("first_name") ~ get[LocalDateTime]("created_at") map SqlParser.flatten
    SQL"SELECT first_name, created_at FROM employees ORDER BY created_at"
      .as(parser.*)
  }

  def findEmployeesCreatedBetween(from: LocalDateTime, to: LocalDateTime)(
      implicit c: Connection
  ): List[String] =
    SQL"SELECT first_name FROM employees WHERE created_at >= $from AND created_at <= $to ORDER BY first_name"
      .as(str("first_name").*)

  def findEmployeesCreatedAfter(ts: LocalDateTime)(implicit c: Connection): List[String] =
    SQL"SELECT first_name FROM employees WHERE created_at > $ts ORDER BY first_name"
      .as(str("first_name").*)

  def findEmployeesCreatedBefore(ts: LocalDateTime)(implicit c: Connection): List[String] =
    SQL"SELECT first_name FROM employees WHERE created_at < $ts ORDER BY first_name"
      .as(str("first_name").*)

  def insertEmployeeWithTimestamp(
      id: Int,
      firstName: String,
      lastName: String,
      email: String,
      hireDate: LocalDate,
      salary: Double,
      departmentId: Int,
      createdAt: LocalDateTime
  )(implicit c: Connection): Int =
    SQL"""UPSERT INTO employees(id, first_name, last_name, email, hire_date, salary, department_id, is_active, created_at, bonus_multiplier)
          VALUES ($id, $firstName, $lastName, $email, $hireDate, $salary, $departmentId, true, $createdAt, 1.0)"""
      .executeUpdate()

  def countEmployeesCreatedOnDate(year: Int, month: Int, day: Int)(
      implicit c: Connection
  ): Long = {
    val from = LocalDateTime.of(year, month, day, 0, 0, 0)
    val to = LocalDateTime.of(year, month, day, 23, 59, 59)
    SQL"SELECT count(*) FROM employees WHERE created_at >= $from AND created_at <= $to"
      .as(scalar[Long].single)
  }

  def getLatestCreatedAt()(implicit c: Connection): Option[LocalDateTime] =
    SQL"SELECT MAX(created_at) AS max_ts FROM employees"
      .as(get[Option[LocalDateTime]]("max_ts").single)

  def getEarliestCreatedAt()(implicit c: Connection): Option[LocalDateTime] =
    SQL"SELECT MIN(created_at) AS min_ts FROM employees"
      .as(get[Option[LocalDateTime]]("min_ts").single)
}
