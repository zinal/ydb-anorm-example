package example.anorm.ydb

import java.sql.Connection
import java.util.concurrent.Executor

import anorm._
import anorm.SqlParser.str

/** Example transaction that mirrors `jdbc-basic`: read with Anorm, publish to a topic bound to
  * the same YDB transaction, then perform a follow-up write.
  *
  * Requires topic `[[TransactionalTopicSample.TopicName]]` to exist (see `schema.sql`).
  */
object TransactionalTopicSample {

  /** Topic path used by the example and tests. */
  val TopicName: String = "anorm_topic_demo"

  /** Default producer id for the sample (override with env `YDB_PRODUCER` in [[YdbTopicWriteSession]]). */
  val ProducerId: String = "anorm-topic-sample"

  /** Reads a department name, publishes a UTF-8 message to the topic, then updates `location`.
    *
    * @param compressionExecutor application thread pool passed into the topic client builder
    */
  def runDemoTransaction(
      connection: Connection,
      compressionExecutor: Executor,
      departmentId: Int,
      locationMarker: String
  ): String =
    YdbTopicAnorm.withTopicInJdbcTransaction(
      connection,
      compressionExecutor,
      TopicName,
      producerId = ProducerId
    ) { (conn, topic) =>
      implicit val c: Connection = conn
      val name =
        SQL"SELECT name FROM departments WHERE id = $departmentId"
          .as(str("name").single)
      topic.sendTransactionalAndFlushUtf8(s"department:$departmentId:$name\n")
      SQL("UPDATE departments SET location = {loc} WHERE id = {id}")
        .on("loc" -> locationMarker, "id" -> departmentId)
        .executeUpdate()
      name
    }
}
