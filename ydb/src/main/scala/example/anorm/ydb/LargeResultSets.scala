package example.anorm.ydb

import java.sql.Connection
import java.util.UUID
import anorm._
import YdbColumnAdapters._

/** Demonstrates streaming a very large YDB result set without materializing it.
  *
  * The query below synthesizes one million rows in-database via cross joins.
  * `withFetchSize` maps to JDBC `PreparedStatement.setFetchSize`, so YDB
  * returns rows in 10k chunks while Anorm's `fold` processes them one at a
  * time.
  */
object LargeResultSets {

  /** Statement-level fetch size: rows retrieved from YDB per batch. */
  val FetchSize: Int = 10000

  /** Single JDBC statement that expands to 1M rows (10 × 10 × 10 × 100 × 100). */
  val MillionRowQuery: String =
    """$q10=(select column0 AS v from (values (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) as qq);
      |$q1k=(select a.v + 10*b.v + 100*c.v as v from $q10 as a cross join $q10 as b cross join $q10 as c);
      |$q1m=(select a.v + 1000*b.v as v from $q1k as a cross join $q1k as b);
      |select RandomUuid(v,1) as a, RandomUuid(v,2) as b, RandomUuid(v,3) as c from $q1m;""".stripMargin

  def countRows()(implicit c: Connection): Long =
    SQL(MillionRowQuery)
      .withFetchSize(Some(FetchSize))
      .fold(0L) { (acc, _) => acc + 1 }
      .fold(_ => 0L, identity)

  /** Counts rows until `maxRows`, then stops — useful when only a prefix is needed. */
  def countUpTo(maxRows: Long)(implicit c: Connection): Long =
    SQL(MillionRowQuery)
      .withFetchSize(Some(FetchSize))
      .foldWhile(0L) { (acc, _) =>
        val next = acc + 1
        (next, next < maxRows)
      }
      .fold(_ => 0L, identity)

  /** Reads one row and returns its UUID columns without building an in-memory collection. */
  def sampleRow()(implicit c: Connection): Option[(UUID, UUID, UUID)] =
    SQL(MillionRowQuery)
      .withFetchSize(Some(FetchSize))
      .foldWhile(Option.empty[(UUID, UUID, UUID)]) {
        case (None, row) =>
          val triple = (
            row[UUID]("a"),
            row[UUID]("b"),
            row[UUID]("c")
          )
          (Some(triple), false)
        case (found, _) => (found, false)
      }
      .fold(_ => None, identity)
}
