package example.anorm.ydb

import java.sql.{SQLRecoverableException, SQLTransientException}
import java.util.UUID
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
  * is used on each attempt. For YDB, a failing statement aborts the
  * transaction automatically; the closure should perform one logical unit of
  * work (including an idempotency check against `operations` when using
  * [[Transactions.transferBudget]] / [[Transactions.hireWithBudgetCheck]]).
  *
  * Use [[retry]] for a by-name body, and [[retryIdempotent]] when the body
  * needs a single stable operation id across all retry attempts (always
  * retries both recoverable and transient failures; [[retry]] alone does not
  * retry [[SQLTransientException]]).
  *
  * ==Usage==
  * {{{
  * // idempotent read — retries on both exception families
  * YdbRetry.retryIdempotent { implicit operationId =>
  *   withConnection { implicit c => BasicQueries.countDepartments() }
  * }
  *
  * // idempotent transactional write — one UUID for the whole retry sequence
  * YdbRetry.retryIdempotent { implicit operationId =>
  *   withConnection { implicit c =>
  *     Transactions.transferBudget(fromDeptId, toDeptId, amount)
  *   }
  * }
  *
  * // non-idempotent write — only unconditionally retryable errors trigger a retry
  * YdbRetry.retry() {
  *   withConnection { implicit c => InsertUpdateDelete.insertDepartment(...) }
  * }
  *
  * // custom configuration
  * implicit val cfg: RetryConfig = RetryConfig(maxRetries = 3, initialBackoffMs = 50)
  * YdbRetry.retry() { ... }
  * }}}
  */
object YdbRetry {

  /** Retries a by-name body. */
  def retry[T]()(op: => T)(implicit config: RetryConfig): T =
    runRetry(false, op)

  /** Retries `op(operationId)` where `operationId` is one [[java.util.UUID]]
    * generated before the retry loop and reused on every attempt (for
    * [[Transactions]] idempotency against YDB `Uuid` columns). Retries on both
    * [[SQLRecoverableException]] and [[SQLTransientException]] (unlike
    * [[retry]], which does not retry transient failures). Use `{ implicit operationId => ... }`
    * so [[Transactions.transferBudget]] / [[Transactions.hireWithBudgetCheck]]
    * receive it implicitly alongside [[java.sql.Connection]].
    */
  def retryIdempotent[T](op: java.util.UUID => T)(implicit config: RetryConfig): T = {
    val operationId = UUID.randomUUID()
    runRetry(idempotent = true, op(operationId))
  }

  private def runRetry[T](idempotent: Boolean, attempt: => T)(implicit config: RetryConfig): T = {
    var remaining = config.maxRetries
    var backoffMs = config.initialBackoffMs
    while (true) {
      try return attempt
      catch {
        case _: SQLRecoverableException if remaining > 0 =>
          Thread.sleep(jitteredBackoff(backoffMs, config.jitterFraction))
          remaining -= 1
          backoffMs = nextBackoff(backoffMs, config)

        case _: SQLTransientException if remaining > 0 && idempotent =>
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
