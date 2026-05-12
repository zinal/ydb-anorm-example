package example.anorm.ydb

import java.sql.Connection
import anorm._
import anorm.SqlParser._

/** Demonstrates JDBC transaction management with Anorm against YDB:
  * explicit commit/rollback and business-rule validation inside a
  * transaction.
  *
  * '''YDB does not support savepoints''' — the recommended pattern for
  * partial-failure recovery is to retry the entire transaction (see
  * [[YdbRetry]]).
  */
object Transactions {

  def transferBudget(fromDeptId: Int, toDeptId: Int, amount: Double)(
      implicit c: Connection
  ): Boolean = {
    val auto = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      SQL"UPDATE departments SET budget = budget - $amount WHERE id = $fromDeptId"
        .executeUpdate()
      SQL"UPDATE departments SET budget = budget + $amount WHERE id = $toDeptId"
        .executeUpdate()
      c.commit()
      true
    } catch {
      case e: Exception =>
        c.rollback()
        false
    } finally {
      c.setAutoCommit(auto)
    }
  }

  def hireWithBudgetCheck(
      id: Int,
      firstName: String,
      lastName: String,
      email: String,
      salary: Double,
      deptId: Int,
      maxBudget: Double
  )(implicit c: Connection): Either[String, Int] = {
    val auto = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      val currentBudget =
        SQL"SELECT budget FROM departments WHERE id = $deptId"
          .as(get[Double]("budget").single)

      if (currentBudget + salary > maxBudget) {
        c.rollback()
        Left(s"Would exceed budget: ${currentBudget + salary} > $maxBudget")
      } else {
        SQL"""UPSERT INTO employees(id, first_name, last_name, email, hire_date, salary, department_id, is_active, created_at)
              VALUES ($id, $firstName, $lastName, $email, CAST(CurrentUtcDate() AS Date32), $salary, $deptId, true, CAST(CurrentUtcTimestamp() AS Timestamp64))"""
          .executeUpdate()
        SQL"UPDATE departments SET budget = budget + $salary WHERE id = $deptId"
          .executeUpdate()
        c.commit()
        Right(id)
      }
    } catch {
      case e: Exception =>
        c.rollback()
        Left(e.getMessage)
    } finally {
      c.setAutoCommit(auto)
    }
  }

  def getDepartmentBudget(deptId: Int)(implicit c: Connection): Double =
    SQL"SELECT budget FROM departments WHERE id = $deptId"
      .as(get[Double]("budget").single)
}
