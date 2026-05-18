package example.anorm.ydb

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.concurrent.TimeUnit

import scala.util.Using

import tech.ydb.common.transaction.YdbTransaction
import tech.ydb.core.grpc.GrpcTransport
import tech.ydb.topic.TopicClient
import tech.ydb.topic.settings.{SendSettings, WriterSettings}
import tech.ydb.topic.write.{Message, SyncWriter}

/** Pairs a [[TopicClient]] with a [[SyncWriter]] for a single [[topicPath]].
  *
  * This mirrors the control flow in
  * [[https://github.com/zinal/ydb-snippets/tree/main/apps/jdbc-basic ydb-snippets/apps/jdbc-basic]]:
  *
  *  - Open once using a connection from the same JDBC URL / pool as your transactional work,
  *    because publishing reuses the underlying [[GrpcTransport]] from
  *    `connection.unwrap(classOf[GrpcTransport])`.
  *  - Inside each JDBC transaction (`setAutoCommit(false)` … `commit` / `rollback`), bind each
  *    `send` to the driver's current [[YdbTransaction]] from
  *    `connection.unwrap(classOf[YdbTransaction])`.
  *  - Close during application shutdown so the writer can stop cleanly.
  *
  * @param shutdownTimeoutSeconds upper bound passed to [[SyncWriter.shutdown]] in [[close]]
  */
final class YdbTopicWriteSession private (
    topicClient: TopicClient,
    writer: SyncWriter,
    val topicPath: String,
    shutdownTimeoutSeconds: Long
) extends AutoCloseable {

  /** Enqueues `message` against the YDB transaction currently associated with `connection`.
    *
    * The JDBC basic sample calls [[flush]] immediately after each send before more SQL; use
    * [[sendTransactionalAndFlush]] when you want that behaviour, or call [[flush]] yourself when
    * batching multiple messages in one database transaction.
    */
  def enqueueTransactional(message: Message)(implicit connection: Connection): Unit = {
    val tx = YdbTopicWriteSession.currentYdbTransaction(connection)
    writer.send(
      message,
      SendSettings.newBuilder().setTransaction(tx).build()
    )
  }

  def enqueueTransactional(payload: Array[Byte])(implicit connection: Connection): Unit =
    enqueueTransactional(Message.of(payload))

  def enqueueTransactionalUtf8(
      text: String,
      charset: Charset = StandardCharsets.UTF_8
  )(implicit connection: Connection): Unit =
    enqueueTransactional(text.getBytes(charset))

  def flush(): Unit = writer.flush()

  def sendTransactionalAndFlush(payload: Array[Byte])(implicit connection: Connection): Unit = {
    enqueueTransactional(payload)
    flush()
  }

  def sendTransactionalAndFlushUtf8(
      text: String,
      charset: Charset = StandardCharsets.UTF_8
  )(implicit connection: Connection): Unit =
    sendTransactionalAndFlush(text.getBytes(charset))

  override def close(): Unit = {
    try writer.shutdown(shutdownTimeoutSeconds, TimeUnit.SECONDS)
    catch { case _: Exception => () }
    try topicClient.close()
    catch { case _: Exception => () }
  }
}

object YdbTopicWriteSession {

  /** YDB transaction handle bound to the JDBC connection for the current unit of work. */
  def currentYdbTransaction(connection: Connection): YdbTransaction =
    connection.unwrap(classOf[YdbTransaction])

  def grpcTransport(connection: Connection): GrpcTransport =
    connection.unwrap(classOf[GrpcTransport])

  /** Opens a sync writer; callers must [[close]] it or use [[resource]]. */
  def open(
      topicPath: String,
      connection: Connection,
      shutdownTimeoutSeconds: Long = 30L
  ): YdbTopicWriteSession = {
    val transport      = grpcTransport(connection)
    val writerSettings = WriterSettings.newBuilder().setTopicPath(topicPath).build()
    val topicClient    = TopicClient.newClient(transport).build()
    try {
      val writer = topicClient.createSyncWriter(writerSettings)
      writer.initAndWait()
      new YdbTopicWriteSession(topicClient, writer, topicPath, shutdownTimeoutSeconds)
    } catch {
      case ex: Exception =>
        try topicClient.close()
        catch { case _: Exception => () }
        throw ex
    }
  }

  def resource[A](topicPath: String, connection: Connection, shutdownTimeoutSeconds: Long = 30L)(
      use: YdbTopicWriteSession => A
  ): A =
    Using.resource(open(topicPath, connection, shutdownTimeoutSeconds))(use)
}
