package example.anorm.ydb

import java.sql.Connection
import java.util.concurrent.Executor

import scala.util.control.NonFatal

/** JDBC transaction helpers for Anorm alongside YDB topic writes.
  *
  * Topic clients and writers are created '''inside''' each transaction (see
  * [[https://github.com/zinal/ydb-snippets/tree/main/apps/jdbc-basic ydb-snippets/apps/jdbc-basic]]),
  * with a caller-supplied [[java.util.concurrent.Executor Executor]] passed into
  * [[YdbTopicWriteSession.open]] for compression.
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

  /** Starts a JDBC transaction, opens a per-transaction [[YdbTopicWriteSession]], runs `body`,
    * commits on success, then closes the topic session (writer shutdown, client close).
    *
    * Use `{ case (conn, topic) => implicit val c = conn; ... }` so Anorm and
    * `topic.sendTransactionalAndFlushUtf8(...)(c)` share one [[Connection]].
    */
  def withTopicInJdbcTransaction[A](
      connection: Connection,
      compressionExecutor: Executor,
      topicPath: String,
      producerId: String = YdbTopicWriteSession.defaultProducerId,
      writerShutdownSeconds: Long = 30L
  )(body: (Connection, YdbTopicWriteSession) => A): A = {
    val previousAutoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    var session: YdbTopicWriteSession = null
    try {
      session = YdbTopicWriteSession.open(
        topicPath,
        connection,
        compressionExecutor,
        producerId,
        writerShutdownSeconds
      )
      val result = body(connection, session)
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
