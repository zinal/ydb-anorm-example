package example.anorm.pg

import java.sql.Connection
import anorm._
import anorm.SqlParser._

/** Demonstrates JDBC transaction management with Anorm: explicit commit/rollback,
  * business-rule validation inside a transaction, and savepoints.
  */
object Transactions {

  def transferBudget(fromDeptId: Int, toDeptId: Int, amount: BigDecimal)(
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
      firstName: String,
      lastName: String,
      email: String,
      salary: BigDecimal,
      deptId: Int,
      maxBudget: BigDecimal
  )(implicit c: Connection): Either[String, Long] = {
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
        val empId =
          SQL"""INSERT INTO employees(first_name, last_name, email, hire_date, salary, department_id)
                VALUES ($firstName, $lastName, $email, CURRENT_DATE, $salary, $deptId)"""
            .executeInsert()
        SQL"UPDATE departments SET budget = budget + $salary WHERE id = $deptId"
          .executeUpdate()
        c.commit()
        Right(empId.getOrElse(-1L))
      }
    } catch {
      case e: Exception =>
        c.rollback()
        Left(e.getMessage)
    } finally {
      c.setAutoCommit(auto)
    }
  }

  def withSavepoint(deptId: Int, amounts: List[BigDecimal])(
      implicit c: Connection
  ): BigDecimal = {
    val auto = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      amounts.foreach { amount =>
        val sp = c.setSavepoint()
        try {
          SQL"UPDATE departments SET budget = budget + $amount WHERE id = $deptId"
            .executeUpdate()
          val current = SQL"SELECT budget FROM departments WHERE id = $deptId"
            .as(get[BigDecimal]("budget").single)
          if (current < 0) c.rollback(sp)
        } catch {
          case _: Exception => c.rollback(sp)
        }
      }
      val result = SQL"SELECT budget FROM departments WHERE id = $deptId"
        .as(get[BigDecimal]("budget").single)
      c.commit()
      result
    } catch {
      case e: Exception =>
        c.rollback()
        throw e
    } finally {
      c.setAutoCommit(auto)
    }
  }

  def getDepartmentBudget(deptId: Int)(implicit c: Connection): BigDecimal =
    SQL"SELECT budget FROM departments WHERE id = $deptId"
      .as(get[BigDecimal]("budget").single)
}
