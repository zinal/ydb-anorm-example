package example.anorm.ydb

import scala.language.implicitConversions

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util
import java.util.concurrent.TimeUnit

import scala.collection.mutable

import scala.util.Using

import tech.ydb.topic.TopicClient
import tech.ydb.topic.settings.{SendSettings, WriterSettings}
import tech.ydb.topic.write.{Message, SyncWriter}

/** One piece of payload for [[YdbTransactionalTopics.sendMessage]].
  *
  * Pass `String` or `Array[Byte]` arguments at the call site; the companion implicits convert
  * strings with UTF-8 and copy byte arrays.
  */
final class SendSegment private (val bytes: Array[Byte])

object SendSegment {

  implicit def utf8String(text: String): SendSegment =
    new SendSegment(text.getBytes(StandardCharsets.UTF_8))

  implicit def byteArray(data: Array[Byte]): SendSegment =
    new SendSegment(util.Arrays.copyOf(data, data.length))
}

/** One [[TopicClient]] per JDBC transaction with lazily created [[SyncWriter]] instances per
  * topic path, so you can publish to several topics before `commit` / `rollback`.
  *
  * Compression executor and producer id come from the implicit [[YdbTopicPublishContext]] used
  * when this object is constructed via [[YdbTransactionalTopics.open]].
  *
  * See [[https://github.com/zinal/ydb-snippets/tree/main/apps/jdbc-basic ydb-snippets/apps/jdbc-basic]]
  * for the underlying driver pattern (`setCompressionExecutor`, `init()`, transactional
  * `SendSettings`, `flush`).
  */
final class YdbTransactionalTopics private (
    val connection: Connection,
    topicClient: TopicClient,
    private val writerShutdownSeconds: Long,
    private val producerId: String
) extends AutoCloseable {

  private val writers = mutable.Map.empty[String, SyncWriter]

  /** Returns the sync writer for `topicPath`, creating it on first use (per-transaction cache). */
  def syncWriter(topicPath: String): SyncWriter =
    writers.synchronized {
      writers.getOrElseUpdate(topicPath, createWriter(topicPath))
    }

  private def createWriter(topicPath: String): SyncWriter = {
    val settings = WriterSettings
      .newBuilder()
      .setTopicPath(topicPath)
      .setProducerId(producerId)
      .build()
    val w = topicClient.createSyncWriter(settings)
    w.init()
    w
  }

  def enqueueTransactional(topicPath: String, message: Message): Unit = {
    val tx = YdbTopicJdbc.currentYdbTransaction(connection)
    syncWriter(topicPath).send(
      message,
      SendSettings.newBuilder().setTransaction(tx).build()
    )
  }

  def enqueueTransactional(topicPath: String, payload: Array[Byte]): Unit =
    enqueueTransactional(topicPath, Message.of(payload))

  def enqueueTransactionalUtf8(
      topicPath: String,
      text: String,
      charset: Charset = StandardCharsets.UTF_8
  ): Unit =
    enqueueTransactional(topicPath, text.getBytes(charset))

  def flush(topicPath: String): Unit =
    syncWriter(topicPath).flush()

  /** Enqueues one message whose payload is all `segments` concatenated (each segment is UTF-8
    * text or raw bytes via [[SendSegment]] implicits), then [[flush]].
    */
  def sendMessage(topicPath: String, segments: SendSegment*): Unit = {
    val merged = segments.foldLeft(Array.emptyByteArray)((acc, seg) => acc ++ seg.bytes)
    enqueueTransactional(topicPath, merged)
    flush(topicPath)
  }

  override def close(): Unit = {
    writers.synchronized {
      writers.values.foreach(shutdownWriterQuietly)
      writers.clear()
    }
    try topicClient.close()
    catch { case _: Exception => () }
  }

  private def shutdownWriterQuietly(writer: SyncWriter): Unit =
    try writer.shutdown(writerShutdownSeconds, TimeUnit.SECONDS)
    catch { case _: Exception => () }
}

object YdbTransactionalTopics {

  /** Opens a topic client; writers are created on demand per [[syncWriter]]. */
  def open(connection: Connection, writerShutdownSeconds: Long = 30L)(implicit
      ctx: YdbTopicPublishContext
  ): YdbTransactionalTopics = {
    val transport = YdbTopicJdbc.grpcTransport(connection)
    val topicClient = TopicClient
      .newClient(transport)
      .setCompressionExecutor(ctx.compressionExecutor)
      .build()
    try new YdbTransactionalTopics(connection, topicClient, writerShutdownSeconds, ctx.producerId)
    catch {
      case ex: Exception =>
        try topicClient.close()
        catch { case _: Exception => () }
        throw ex
    }
  }

  def resource[A](connection: Connection, writerShutdownSeconds: Long = 30L)(use: YdbTransactionalTopics => A)(implicit
      ctx: YdbTopicPublishContext
  ): A =
    Using.resource(open(connection, writerShutdownSeconds))(use)
}
