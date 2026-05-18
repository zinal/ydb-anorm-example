package example.anorm.ydb

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.concurrent.{Executor, TimeUnit}

import scala.util.Using

import tech.ydb.common.transaction.YdbTransaction
import tech.ydb.core.grpc.GrpcTransport
import tech.ydb.topic.TopicClient
import tech.ydb.topic.settings.{SendSettings, WriterSettings}
import tech.ydb.topic.write.{Message, SyncWriter}

/** One [[TopicClient]] + [[SyncWriter]] pair for a single JDBC transaction, aligned with the
  * current
  * [[https://github.com/zinal/ydb-snippets/tree/main/apps/jdbc-basic ydb-snippets/apps/jdbc-basic]]
  * flow:
  *
  *  - After `setAutoCommit(false)`, build [[TopicClient]] from
  *    `connection.unwrap(classOf[GrpcTransport])` and pass an application-owned
  *    [[java.util.concurrent.Executor Executor]] into
  *    `TopicClient.newClient(transport).setCompressionExecutor(executor).build()`.
  *  - Build [[WriterSettings]] with topic path and [[producerId]], then `createSyncWriter` and
  *    `init()` (non-blocking init, as in the Java sample).
  *  - Send with `SendSettings` bound to `connection.unwrap(classOf[YdbTransaction])`, then
  *    `flush()` before more SQL, then `commit` / `rollback`.
  *  - On exit, `shutdown` the writer, then `close` the topic client (see [[close]]).
  *
  * YDB currently expects a separate writer per transaction; keep the session lifetime within
  * that transaction and use [[YdbTopicAnorm.withTopicInJdbcTransaction]] for a safe template.
  *
  * @param shutdownTimeoutSeconds upper bound passed to [[SyncWriter.shutdown]] in [[close]]
  */
final class YdbTopicWriteSession private (
    topicClient: TopicClient,
    writer: SyncWriter,
    val topicPath: String,
    val producerId: String,
    shutdownTimeoutSeconds: Long
) extends AutoCloseable {

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

  def defaultProducerId: String =
    Option(System.getenv("YDB_PRODUCER")).map(_.trim).filter(_.nonEmpty).getOrElse("anorm-example-producer")

  def currentYdbTransaction(connection: Connection): YdbTransaction =
    connection.unwrap(classOf[YdbTransaction])

  def grpcTransport(connection: Connection): GrpcTransport =
    connection.unwrap(classOf[GrpcTransport])

  /** Opens a topic client and sync writer for the current JDBC transaction.
    *
    * @param compressionExecutor executor used for topic compression work (often a shared
    *                            `ExecutorService` from your application runtime)
    */
  def open(
      topicPath: String,
      connection: Connection,
      compressionExecutor: Executor,
      producerId: String = defaultProducerId,
      shutdownTimeoutSeconds: Long = 30L
  ): YdbTopicWriteSession = {
    val transport = grpcTransport(connection)
    val topicClient = TopicClient
      .newClient(transport)
      .setCompressionExecutor(compressionExecutor)
      .build()
    try {
      val writerSettings = WriterSettings
        .newBuilder()
        .setTopicPath(topicPath)
        .setProducerId(producerId)
        .build()
      val writer = topicClient.createSyncWriter(writerSettings)
      writer.init()
      new YdbTopicWriteSession(topicClient, writer, topicPath, producerId, shutdownTimeoutSeconds)
    } catch {
      case ex: Exception =>
        try topicClient.close()
        catch { case _: Exception => () }
        throw ex
    }
  }

  def resource[A](
      topicPath: String,
      connection: Connection,
      compressionExecutor: Executor,
      producerId: String = defaultProducerId,
      shutdownTimeoutSeconds: Long = 30L
  )(use: YdbTopicWriteSession => A): A =
    Using.resource(
      open(topicPath, connection, compressionExecutor, producerId, shutdownTimeoutSeconds)
    )(use)
}
