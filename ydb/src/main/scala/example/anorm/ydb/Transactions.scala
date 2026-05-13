package example.anorm.ydb

import java.sql.Connection
import java.util.UUID
import anorm._
import anorm.SqlParser._
import YdbColumnAdapters._

/** Labels stored in [[operations]] for idempotent transactional writes. */
object OperationType {
  val BudgetTransfer: String = "budget_transfer"
  val HireWithBudget: String = "hire_with_budget"
}

/** Demonstrates JDBC transaction management with Anorm against YDB.
  *
  * '''YDB:''' Any failing statement in a transaction aborts that transaction
  * automatically; retries are done by re-running the whole transaction (see
  * [[YdbRetry]]). There is no need to branch on partial writes inside the
  * client — only a pre-check against the `operations` idempotency table and
  * then the full effect plus an `operations` row in one commit.
  *
  * '''YDB does not support savepoints.'''
  */
object Transactions {

  private def operationAlreadyApplied(operationType: String)(implicit c: Connection, operationId: UUID): Boolean =
    SQL("SELECT operation_id FROM operations WHERE operation_id = {id} AND operation_type = {typ}")
      .on("id" -> operationId, "typ" -> operationType)
      .as(get[UUID]("operation_id").singleOpt)
      .isDefined

  private def recordOperation(operationType: String)(implicit c: Connection, operationId: UUID): Unit = {
    SQL(
      """UPSERT INTO operations(operation_id, operation_type, applied_at)
         VALUES ({id}, {typ}, CAST(CurrentUtcTimestamp() AS Timestamp64))"""
    ).on("id" -> operationId, "typ" -> operationType)
      .executeUpdate()
    ()
  }

  /** Transfers budget at most once per implicit `operationId`. Under retry, use
    * `YdbRetry.retryIdempotent { implicit operationId =>
    *   withConnection { implicit c => transferBudget(...) } }`.
    */
  def transferBudget(
      fromDeptId: Int,
      toDeptId: Int,
      amount: BigDecimal
  )(implicit c: Connection, operationId: UUID): Boolean = {
    val auto = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      if (operationAlreadyApplied(OperationType.BudgetTransfer)) {
        c.commit()
        true
      } else {
        SQL("UPDATE departments SET budget = budget - CAST({amt} AS Decimal(15,2)) WHERE id = {id}")
          .on("amt" -> amount, "id" -> fromDeptId)
          .executeUpdate()
        SQL("UPDATE departments SET budget = budget + CAST({amt} AS Decimal(15,2)) WHERE id = {id}")
          .on("amt" -> amount, "id" -> toDeptId)
          .executeUpdate()
        recordOperation(OperationType.BudgetTransfer)
        c.commit()
        true
      }
    } finally {
      c.setAutoCommit(auto)
    }
  }

  /** Hires and bumps department budget at most once per implicit `operationId`.
    * Under retry, use
    * `YdbRetry.retryIdempotent { implicit operationId =>
    *   withConnection { implicit c => hireWithBudgetCheck(...) } }`.
    */
  def hireWithBudgetCheck(
      id: Int,
      firstName: String,
      lastName: String,
      email: String,
      salary: BigDecimal,
      deptId: Int,
      maxBudget: BigDecimal
  )(implicit c: Connection, operationId: UUID): Either[String, Int] = {
    val auto = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      if (operationAlreadyApplied(OperationType.HireWithBudget)) {
        c.commit()
        Right(id)
      } else {
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
          recordOperation(OperationType.HireWithBudget)
          c.commit()
          Right(id)
        }
      }
    } finally {
      c.setAutoCommit(auto)
    }
  }

  def getDepartmentBudget(deptId: Int)(implicit c: Connection): BigDecimal =
    SQL"SELECT budget FROM departments WHERE id = $deptId"
      .as(get[BigDecimal]("budget").single)
}
