package example.anorm.ydb

import java.util.concurrent.Executor

/** Implicit bundle for topic publishing: compression executor and producer id.
  *
  * Typical application wiring:
  *
  * {{{
  * implicit val ydbTopicPublish: YdbTopicPublishContext =
  *   YdbTopicPublishContext(myExecutorService, "payments-outbox")
  *
  * YdbTopicAnorm.withTopicsInJdbcTransaction(conn) { topics =>
  *   topics.sendMessage("topic-a", "hello")
  *   topics.sendMessage("topic-b", "part1", "part2")
  *   topics.sendMessage("topic-c", "head: ", Array(1, 2, 3).map(_.toByte))
  * }
  * }}}
  *
  * Use [[YdbTopicPublishContext.apply]]`(executor)` to pick up [[DefaultProducerId]] from the
  * environment (`YDB_PRODUCER`) or the built-in default.
  */
final case class YdbTopicPublishContext(
    compressionExecutor: Executor,
    producerId: String
)

object YdbTopicPublishContext {

  /** Producer id from `YDB_PRODUCER` when set, otherwise `anorm-example-producer`. */
  val DefaultProducerId: String =
    Option(System.getenv("YDB_PRODUCER")).map(_.trim).filter(_.nonEmpty).getOrElse("anorm-example-producer")

  def apply(compressionExecutor: Executor): YdbTopicPublishContext =
    YdbTopicPublishContext(compressionExecutor, DefaultProducerId)
}
