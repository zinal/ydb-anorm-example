package example.anorm.ydb

import java.sql.Connection

import scala.util.control.NonFatal

/** Small utilities for combining Anorm (implicit [[Connection]]) with YDB topic writes.
  *
  * Topic sessions are opened separately via [[YdbTopicWriteSession]] because their lifetime is
  * usually wider than a single JDBC transaction, while each `send` must be scoped to the
  * current YDB transaction handle carried by the connection.
  */
object YdbTopicAnorm {

  /** Runs `f` with `autoCommit` cleared, commits on success, and restores the previous flag.
    *
    * This is a plain JDBC transaction boundary suitable for interleaving Anorm `SQL` calls with
    * [[YdbTopicWriteSession]] sends on the same connection. Use `{ implicit c => ... }` so Anorm
    * statements and topic helpers can share one implicit [[Connection]].
    *
    * Example (same pattern as `jdbc-basic`, with Anorm in the middle):
    *
    * {{{
    * import anorm._
    *
    * YdbTopicWriteSession.resource("my_topic", conn) { session =>
    *   YdbTopicAnorm.withLocalTransaction(conn) { implicit c =>
    *     SQL"UPDATE departments SET budget = budget - 1 WHERE id = 1".executeUpdate()
    *     session.sendTransactionalAndFlushUtf8("budget-changed:1")
    *     SQL"UPDATE departments SET budget = budget + 1 WHERE id = 2".executeUpdate()
    *   }
    * }
    * }}}
    */
  def withLocalTransaction[A](connection: Connection)(f: Connection => A): A = {
    val previousAutoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    try {
      val result = f(connection)
      connection.commit()
      result
    } catch {
      case NonFatal(t) =>
        try connection.rollback()
        catch { case _: Throwable => () }
        throw t
    } finally {
      try connection.setAutoCommit(previousAutoCommit)
      catch { case _: Throwable => () }
    }
  }
}
