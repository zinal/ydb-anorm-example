package example.anorm.ydb

import java.sql.Connection

import anorm._
import anorm.SqlParser.str

/** Example transaction: Anorm read, publish to two topics on the same YDB transaction, then update.
  *
  * Publishing uses [[YdbTransactionalTopics.sendMessage]] (UTF-8 `String` segments via [[SendSegment]] implicits).
  *
  * Requires topics `[[TopicName]]` and `[[SecondaryTopicName]]` (see `schema.sql`).
  *
  * Supply `implicit val ... : YdbTopicPublishContext = YdbTopicPublishContext(executor, ProducerId)`
  * (or any producer id) at the call site.
  */
object TransactionalTopicSample {

  val TopicName: String = "anorm_topic_demo"

  val SecondaryTopicName: String = "anorm_topic_demo_secondary"

  val ProducerId: String = "anorm-topic-sample"

  /** Reads a department name, publishes to two topics, then sets `location` to `locationMarker`. */
  def runDemoTransaction(
      connection: Connection,
      departmentId: Int,
      locationMarker: String
  )(implicit ctx: YdbTopicPublishContext): String =
    YdbTopicAnorm.withTopicsInJdbcTransaction(connection) { topics =>
      implicit val c: Connection = topics.connection
      val name =
        SQL"SELECT name FROM departments WHERE id = $departmentId"
          .as(str("name").single)
      topics.sendMessage(TopicName, s"primary:department:$departmentId:$name\n")
      topics.sendMessage(SecondaryTopicName, s"secondary:dept:$departmentId\n")
      SQL("UPDATE departments SET location = {loc} WHERE id = {id}")
        .on("loc" -> locationMarker, "id" -> departmentId)
        .executeUpdate()
      name
    }
}
