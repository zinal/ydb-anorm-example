package example.anorm.ydb

import java.sql.Connection

import scala.util.control.NonFatal

/** JDBC transaction helpers for Anorm alongside YDB topic writes.
  *
  * Topic clients and writers are created '''inside''' each transaction (see
  * [[https://github.com/zinal/ydb-snippets/tree/main/apps/jdbc-basic ydb-snippets/apps/jdbc-basic]]).
  * Compression executor and producer id are supplied via implicit [[YdbTopicPublishContext]].
  */
object YdbTopicAnorm {

  /** Runs `f` with `autoCommit` cleared, commits on success, and restores the previous flag. */
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

  /** Starts a JDBC transaction, opens [[YdbTransactionalTopics]] (one [[tech.ydb.topic.TopicClient]]
    * shared by all topic paths in this transaction), runs `body`, commits, then shuts down all
    * writers and closes the client.
    *
    * Requires an implicit [[YdbTopicPublishContext]] in scope (executor + producer id).
    */
  def withTopicsInJdbcTransaction[A](connection: Connection, writerShutdownSeconds: Long = 30L)(
      body: YdbTransactionalTopics => A
  )(implicit ctx: YdbTopicPublishContext): A = {
    val previousAutoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    var session: YdbTransactionalTopics = null
    try {
      session = YdbTransactionalTopics.open(connection, writerShutdownSeconds)
      val result = body(session)
      connection.commit()
      result
    } catch {
      case NonFatal(t) =>
        try connection.rollback()
        catch { case _: Throwable => () }
        throw t
    } finally {
      if (session != null) {
        try session.close()
        catch { case _: Exception => () }
      }
      try connection.setAutoCommit(previousAutoCommit)
      catch { case _: Exception => () }
    }
  }
}
