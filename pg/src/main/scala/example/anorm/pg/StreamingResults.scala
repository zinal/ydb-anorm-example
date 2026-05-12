package example.anorm.pg

import java.sql.Connection
import anorm._

/** Demonstrates streaming / iterative result processing: fold for full
  * aggregation, foldWhile for early termination, and withResult for
  * cursor-based row-by-row traversal.
  */
object StreamingResults {

  def totalSalary()(implicit c: Connection): BigDecimal =
    SQL"SELECT salary FROM employees"
      .fold(BigDecimal(0)) { (acc, row) =>
        acc + row[BigDecimal]("salary")
      }
      .fold(_ => BigDecimal(0), identity)

  def employeesUntilBudget(budget: BigDecimal)(implicit c: Connection): List[String] =
    SQL"SELECT first_name, salary FROM employees ORDER BY salary"
      .foldWhile((List.empty[String], BigDecimal(0))) {
        case ((names, total), row) =>
          val salary   = row[BigDecimal]("salary")
          val newTotal = total + salary
          if (newTotal <= budget)
            ((row[String]("first_name") :: names, newTotal), true)
          else
            ((names, total), false)
      }
      .fold(_ => Nil, { case (names, _) => names.reverse })

  def collectActiveEmployeeNames()(implicit c: Connection): List[String] =
    SQL"SELECT first_name, is_active FROM employees ORDER BY first_name"
      .withResult { cursorOpt =>
        @annotation.tailrec
        def go(c: Option[Cursor], acc: List[String]): List[String] = c match {
          case Some(cursor) =>
            val name   = cursor.row[String]("first_name")
            val active = cursor.row[Boolean]("is_active")
            val next   = if (active) name :: acc else acc
            go(cursor.next, next)
          case None => acc.reverse
        }
        go(cursorOpt, Nil)
      }
      .fold(_ => Nil, identity)

  def countRowsViaFold()(implicit c: Connection): Int =
    SQL"SELECT id FROM employees"
      .fold(0) { (acc, _) => acc + 1 }
      .fold(_ => 0, identity)
}
