package example.anorm.ydb

import java.sql.Connection
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Demonstrates JDBC transaction management with Anorm against YDB:
  * explicit commit/rollback and business-rule validation inside a
  * transaction.
  *
  * '''YDB does not support savepoints''' — the recommended pattern for
  * partial-failure recovery is to retry the entire transaction (see
  * [[YdbRetry]]).
  */
object Transactions {

  def transferBudget(fromDeptId: Int, toDeptId: Int, amount: BigDecimal)(
      implicit c: Connection
  ): Boolean = {
    val auto = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      SQL("UPDATE departments SET budget = budget - CAST({amt} AS Decimal(15,2)) WHERE id = {id}")
        .on("amt" -> amount, "id" -> fromDeptId)
        .executeUpdate()
      SQL("UPDATE departments SET budget = budget + CAST({amt} AS Decimal(15,2)) WHERE id = {id}")
        .on("amt" -> amount, "id" -> toDeptId)
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
      salary: BigDecimal,
      deptId: Int,
      maxBudget: BigDecimal
  )(implicit c: Connection): Either[String, Int] = {
    val auto = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      val currentBudget =
        SQL"SELECT budget FROM departments WHERE id = $deptId"
          .as(get[BigDecimal]("budget").single)

      if (currentBudget + salary > maxBudget) {
        c.rollback()
        Left(s"Would exceed budget: ${currentBudget + salary} > $maxBudget")
      } else {
        SQL(
          """UPSERT INTO employees(id, first_name, last_name, email, hire_date, salary, department_id, is_active, created_at, bonus_multiplier)
             VALUES ({id}, {fn}, {ln}, {email}, CAST(CurrentUtcDate() AS Date32), CAST({sal} AS Decimal(12,2)), {did}, true, CAST(CurrentUtcTimestamp() AS Timestamp64), 1.0)"""
        ).on(
            "id" -> id, "fn" -> firstName, "ln" -> lastName,
            "email" -> email, "sal" -> salary, "did" -> deptId
          )
          .executeUpdate()
        SQL("UPDATE departments SET budget = budget + CAST({sal} AS Decimal(15,2)) WHERE id = {did}")
          .on("sal" -> salary, "did" -> deptId)
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

  def getDepartmentBudget(deptId: Int)(implicit c: Connection): BigDecimal =
    SQL"SELECT budget FROM departments WHERE id = $deptId"
      .as(get[BigDecimal]("budget").single)
}
