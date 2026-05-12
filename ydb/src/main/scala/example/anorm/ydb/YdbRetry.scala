package example.anorm.ydb

import java.sql.{SQLRecoverableException, SQLTransientException}
import scala.util.Random

/** Global retry configuration for YDB operations.
  *
  * @param maxRetries        maximum number of retry attempts
  * @param initialBackoffMs  initial backoff duration in milliseconds
  * @param maxBackoffMs      upper bound on backoff duration
  * @param backoffMultiplier factor by which the backoff grows each attempt
  * @param jitterFraction    fraction of backoff to randomise (0.0 to 1.0)
  */
case class RetryConfig(
    maxRetries: Int = 10,
    initialBackoffMs: Long = 5,
    maxBackoffMs: Long = 1000,
    backoffMultiplier: Double = 2.0,
    jitterFraction: Double = 0.1
)

object RetryConfig {
  implicit val default: RetryConfig = RetryConfig()
}

/** Retry helper for YDB transient failures.
  *
  * The YDB JDBC driver exposes two retryable exception families that map
  * directly to standard JDBC exception types:
  *
  *  - [[SQLRecoverableException]] ← `YdbRetryableException` — always safe
  *    to retry regardless of idempotency.
  *  - [[SQLTransientException]] ← `YdbConditionallyRetryableException` —
  *    safe to retry '''only''' when the operation is idempotent.
  *
  * Catching by the JDBC parent types keeps this utility decoupled from YDB
  * driver classes while still matching the exact exception hierarchy.
  *
  * The entire transactional block (including connection acquisition) should
  * be placed inside the retry closure so that a fresh connection/transaction
  * is used on each attempt.
  *
  * ==Usage==
  * {{{
  * // idempotent read — retries on both exception families
  * YdbRetry.retry(idempotent = true) {
  *   withConnection { implicit c => BasicQueries.countDepartments() }
  * }
  *
  * // non-idempotent write — only unconditionally retryable errors trigger a retry
  * YdbRetry.retry() {
  *   withConnection { implicit c => InsertUpdateDelete.insertDepartment(...) }
  * }
  *
  * // custom configuration
  * implicit val cfg: RetryConfig = RetryConfig(maxRetries = 3, initialBackoffMs = 50)
  * YdbRetry.retry(idempotent = true) { ... }
  * }}}
  */
object YdbRetry {

  def retry[T](idempotent: Boolean = false)(op: => T)(implicit config: RetryConfig): T = {
    var remaining  = config.maxRetries
    var backoffMs  = config.initialBackoffMs
    while (true) {
      try return op
      catch {
        case e: SQLRecoverableException if remaining > 0 =>
          Thread.sleep(jitteredBackoff(backoffMs, config.jitterFraction))
          remaining -= 1
          backoffMs = nextBackoff(backoffMs, config)

        case e: SQLTransientException if remaining > 0 && idempotent =>
          Thread.sleep(jitteredBackoff(backoffMs, config.jitterFraction))
          remaining -= 1
          backoffMs = nextBackoff(backoffMs, config)
      }
    }
    throw new AssertionError("unreachable")
  }

  private def nextBackoff(current: Long, config: RetryConfig): Long =
    math.min((current * config.backoffMultiplier).toLong, config.maxBackoffMs)

  private def jitteredBackoff(backoffMs: Long, jitterFraction: Double): Long = {
    val jitter = (backoffMs * jitterFraction * (Random.nextDouble() * 2 - 1)).toLong
    math.max(1, backoffMs + jitter)
  }
}
