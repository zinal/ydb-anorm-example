package example.anorm.ydb

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

import java.net.ServerSocket
import java.sql.{Connection, DriverManager, SQLRecoverableException, SQLTransientException}
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}

import anorm._
import anorm.SqlParser.str

/** Thin subclass that exposes the protected `addFixedExposedPort` method
  * needed by YDB (host and container ports must match for discovery).
  */
class YdbContainer(image: String) extends GenericContainer[YdbContainer](image) {
  def addFixedPort(hostPort: Int, containerPort: Int): Unit =
    addFixedExposedPort(hostPort, containerPort)
}

class AnormYdbSpec extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val grpcPort = findAvailablePort()

  private val ydbContainer: YdbContainer = {
    val c = new YdbContainer("ydbplatform/local-ydb:latest")
    c.addFixedPort(grpcPort, grpcPort)
    c.withEnv("GRPC_PORT", grpcPort.toString)
    c.withEnv("YDB_USE_IN_MEMORY_PDISKS", "true")
    c.withCreateContainerCmdModifier { cmd =>
      cmd.withHostName("localhost")
    }
    c.waitingFor(Wait.forHealthcheck())
    c
  }

  private def jdbcUrl: String =
    s"jdbc:ydb:grpc://${ydbContainer.getHost}:$grpcPort/local?forceSignedDatetimes=true"

  // To keep the internal pools open for the duration of the test suite
  private var mainConnection: Connection = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    ydbContainer.start()
    mainConnection = openConnection()
  }

  override def afterAll(): Unit = {
    try { mainConnection.close() } catch { case _: Exception => }
    ydbContainer.stop()
    super.afterAll()
  }

  private def openConnection(): Connection =
    DriverManager.getConnection(jdbcUrl)

  private def withConnection[T](f: Connection => T): T = {
    val conn = openConnection()
    try {
      conn.setAutoCommit(false)
      val result = f(conn)
      conn.commit()
      result
    } finally {
      try { conn.rollback() } catch { case _: Exception => }
      try { conn.close() } catch { case _: Exception => }
    }
  }

  private def execStatements(conn: Connection, sql: String): Unit = {
    val stmts = sql.split(";").map(_.trim).filter(_.nonEmpty)
    val stmt  = conn.createStatement()
    try stmts.foreach(s => stmt.execute(s))
    finally stmt.close()
  }

  private def runSqlResource(conn: Connection, resourcePath: String): Unit = {
    val sql = scala.io.Source.fromResource(resourcePath).mkString
    execStatements(conn, sql)
  }

  private def tryDrop(conn: Connection, table: String): Unit =
    try { val s = conn.createStatement(); try s.execute(s"DROP TABLE $table") finally s.close() }
    catch { case _: Exception => }

  private def tryDropTopic(conn: Connection, topic: String): Unit =
    try { val s = conn.createStatement(); try s.execute(s"DROP TOPIC $topic") finally s.close() }
    catch { case _: Exception => }

  override def beforeEach(): Unit = {
    super.beforeEach()
    withConnection { conn =>
      tryDropTopic(conn, TransactionalTopicSample.TopicName)
      tryDropTopic(conn, TransactionalTopicSample.SecondaryTopicName)
      tryDrop(conn, "employee_projects")
      tryDrop(conn, "projects")
      tryDrop(conn, "operations")
      tryDrop(conn, "employees")
      tryDrop(conn, "departments")
      runSqlResource(conn, "schema.sql")
      runSqlResource(conn, "data.sql")
    }
  }

  private def findAvailablePort(): Int = {
    val ss = new ServerSocket(0)
    try ss.getLocalPort finally ss.close()
  }

  // ---------------------------------------------------------------------------
  // YdbRetry
  // ---------------------------------------------------------------------------

  test("YdbRetry - retries on SQLRecoverableException") {
    implicit val cfg: RetryConfig = RetryConfig(maxRetries = 5, initialBackoffMs = 1, maxBackoffMs = 10)
    var attempts = 0
    val result = YdbRetry.retry() {
      attempts += 1
      if (attempts < 3) throw new SQLRecoverableException("simulated retryable")
      "ok"
    }
    assert(result === "ok")
    assert(attempts === 3)
  }

  test("YdbRetry - retries SQLTransientException only when idempotent") {
    implicit val cfg: RetryConfig = RetryConfig(maxRetries = 5, initialBackoffMs = 1, maxBackoffMs = 10)

    var attempts1 = 0
    val r1 = YdbRetry.retryIdempotent { implicit operationId =>
      attempts1 += 1
      if (attempts1 < 3) throw new SQLTransientException("simulated conditionally retryable")
      "ok"
    }
    assert(r1 === "ok")
    assert(attempts1 === 3)

    var attempts2 = 0
    assertThrows[SQLTransientException] {
      YdbRetry.retry() {
        attempts2 += 1
        throw new SQLTransientException("not idempotent")
      }
    }
    assert(attempts2 === 1)
  }

  test("YdbRetry - gives up after maxRetries") {
    implicit val cfg: RetryConfig = RetryConfig(maxRetries = 2, initialBackoffMs = 1, maxBackoffMs = 5)
    var attempts = 0
    assertThrows[SQLRecoverableException] {
      YdbRetry.retry() {
        attempts += 1
        throw new SQLRecoverableException("persistent failure")
      }
    }
    assert(attempts === 3) // initial + 2 retries
  }

  // ---------------------------------------------------------------------------
  // BasicQueries
  // ---------------------------------------------------------------------------

  test("BasicQueries - countDepartments") {
    withConnection { implicit c =>
      assert(BasicQueries.countDepartments() === 4L)
    }
  }

  test("BasicQueries - findDepartmentNameById") {
    withConnection { implicit c =>
      assert(BasicQueries.findDepartmentNameById(1) === Some("Engineering"))
      assert(BasicQueries.findDepartmentNameById(999) === None)
    }
  }

  test("BasicQueries - listAllDepartmentNames") {
    withConnection { implicit c =>
      assert(BasicQueries.listAllDepartmentNames() ===
        List("Engineering", "HR", "Marketing", "Sales"))
    }
  }

  test("BasicQueries - departmentExists") {
    withConnection { implicit c =>
      assert(BasicQueries.departmentExists("Engineering") === true)
      assert(BasicQueries.departmentExists("NonExistent") === false)
    }
  }

  test("BasicQueries - findDepartmentWithMaxBudget") {
    withConnection { implicit c =>
      val result = BasicQueries.findDepartmentWithMaxBudget()
      assert(result.isDefined)
      assert(result.get._1 === "Engineering")
      assert(result.get._2 === BigDecimal("500000.00"))
    }
  }

  test("BasicQueries - listEmployeeEmailsByDepartment") {
    withConnection { implicit c =>
      val emails = BasicQueries.listEmployeeEmailsByDepartment("Engineering")
      assert(emails.length === 3)
      assert(emails.contains("alice.smith@example.com"))
    }
  }

  test("BasicQueries - getScalarBudgetSum") {
    withConnection { implicit c =>
      assert(BasicQueries.getScalarBudgetSum() === BigDecimal("1200000.00"))
    }
  }

  // ---------------------------------------------------------------------------
  // RowParsers
  // ---------------------------------------------------------------------------

  test("RowParsers - getAllDepartments") {
    withConnection { implicit c =>
      val depts = RowParsers.getAllDepartments()
      assert(depts.length === 4)
      assert(depts.head === Department(1, "Engineering", Some("Building A"), BigDecimal("500000.00")))
    }
  }

  test("RowParsers - getEmployeeById") {
    withConnection { implicit c =>
      val alice = RowParsers.getEmployeeById(1)
      assert(alice.isDefined)
      assert(alice.get.firstName === "Alice")
      assert(alice.get.lastName === "Smith")
      assert(alice.get.salary === BigDecimal("95000.00"))
      assert(alice.get.isActive === true)
      assert(alice.get.hireDate === java.time.LocalDate.of(2020, 1, 15))
      assert(alice.get.createdAt === LocalDateTime.of(2020, 1, 15, 9, 0, 0))
      assert(alice.get.rating === Some(4.5))
      assert(alice.get.bonusMultiplier === 1.15)

      assert(RowParsers.getEmployeeById(999) === None)
    }
  }

  test("RowParsers - getNamesAndSalaries (flattened parser)") {
    withConnection { implicit c =>
      val list = RowParsers.getNamesAndSalaries()
      assert(list.length === 6)
      assert(list.head._1 === "Dave")
      assert(list.head._2 === BigDecimal("115000.00"))
    }
  }

  test("RowParsers - getEmployeeWithDepartment (joined parser)") {
    withConnection { implicit c =>
      val result = RowParsers.getEmployeeWithDepartment(1)
      assert(result.isDefined)
      assert(result.get.employee.firstName === "Alice")
      assert(result.get.departmentName === "Engineering")
      assert(result.get.employee.hireDate === java.time.LocalDate.of(2020, 1, 15))
      assert(result.get.employee.createdAt === LocalDateTime.of(2020, 1, 15, 9, 0, 0))
    }
  }

  test("RowParsers - getEmployeeCountByDepartment (aggregate parser)") {
    withConnection { implicit c =>
      val counts = RowParsers.getEmployeeCountByDepartment()
      assert(counts.length === 4)
      val eng = counts.find(_._1 == "Engineering")
      assert(eng === Some(("Engineering", 3L)))
      val hr = counts.find(_._1 == "HR")
      assert(hr === Some(("HR", 1L)))
    }
  }

  // ---------------------------------------------------------------------------
  // ParameterBinding
  // ---------------------------------------------------------------------------

  test("ParameterBinding - findByDepartmentName (named params)") {
    withConnection { implicit c =>
      val names = ParameterBinding.findByDepartmentName("Engineering")
      assert(names.sorted === List("Alice", "Bob", "Dave"))
    }
  }

  test("ParameterBinding - findBySalaryRange") {
    withConnection { implicit c =>
      val names = ParameterBinding.findBySalaryRange(BigDecimal(80000), BigDecimal(100000))
      assert(names === List("Frank", "Alice"))
    }
  }

  test("ParameterBinding - findByDepartmentId (NamedParameter instances)") {
    withConnection { implicit c =>
      val names = ParameterBinding.findByDepartmentId(2)
      assert(names === List("Carol"))
    }
  }

  test("ParameterBinding - findByIds (multi-value IN)") {
    withConnection { implicit c =>
      val names = ParameterBinding.findByIds(Seq(1, 3, 5))
      assert(names === List("Alice", "Carol", "Eve"))
    }
  }

  test("ParameterBinding - findEmployees (optional parameter)") {
    withConnection { implicit c =>
      val withDept = ParameterBinding.findEmployees(Some(1))
      assert(withDept.sorted === List("Alice", "Bob", "Dave"))
      val all = ParameterBinding.findEmployees(None)
      assert(all.length === 6)
    }
  }

  test("ParameterBinding - searchByNamePrefix") {
    withConnection { implicit c =>
      val result = ParameterBinding.searchByNamePrefix("Al")
      assert(result === List("Alice"))
    }
  }

  test("ParameterBinding - findActiveInDepartment") {
    withConnection { implicit c =>
      assert(ParameterBinding.findActiveInDepartment(4, active = true) === Nil)
      assert(ParameterBinding.findActiveInDepartment(4, active = false) === List("Frank"))
    }
  }

  // ---------------------------------------------------------------------------
  // ColumnMappings
  // ---------------------------------------------------------------------------

  test("ColumnMappings - getAllEmails (custom Column[Email])") {
    withConnection { implicit c =>
      val emails = ColumnMappings.getAllEmails()
      assert(emails.length === 6)
      assert(emails.head === ColumnMappings.Email("alice.smith@example.com"))
    }
  }

  test("ColumnMappings - findByEmail (custom ToStatement[Email])") {
    withConnection { implicit c =>
      val name = ColumnMappings.findByEmail(ColumnMappings.Email("bob.johnson@example.com"))
      assert(name === Some("Bob"))
      assert(ColumnMappings.findByEmail(ColumnMappings.Email("nobody@example.com")) === None)
    }
  }

  test("ColumnMappings - getEmployeeSalaryLevels (transforming parser)") {
    withConnection { implicit c =>
      val levels = ColumnMappings.getEmployeeSalaryLevels()
      assert(levels.length === 5) // Frank is inactive
      assert(levels.head === ("Eve", ColumnMappings.Junior))
      assert(levels.find(_._1 == "Dave") === Some(("Dave", ColumnMappings.Principal)))
    }
  }

  test("ColumnMappings - getDepartmentAliased") {
    withConnection { implicit c =>
      assert(ColumnMappings.getDepartmentAliased(1) === Some(("Engineering", "Building A")))
    }
  }

  // ---------------------------------------------------------------------------
  // InsertUpdateDelete
  // ---------------------------------------------------------------------------

  test("InsertUpdateDelete - insertDepartment") {
    withConnection { implicit c =>
      InsertUpdateDelete.insertDepartment(10, "R&D", "Building D", BigDecimal(200000))
      assert(BasicQueries.countDepartments() === 5L)
      assert(BasicQueries.findDepartmentNameById(10) === Some("R&D"))
    }
  }

  test("InsertUpdateDelete - insertEmployee (named params)") {
    withConnection { implicit c =>
      val hireDate = java.time.LocalDate.of(2024, 1, 1)
      val createdAt = LocalDateTime.of(2024, 1, 1, 12, 0, 0)
      InsertUpdateDelete.insertEmployee(
        10, "Grace", "Hopper", "grace@example.com",
        hireDate, BigDecimal(120000), 1, createdAt
      )
      val emp = RowParsers.getEmployeeById(10)
      assert(emp.isDefined)
      assert(emp.get.firstName === "Grace")
      assert(emp.get.hireDate === hireDate)
      assert(emp.get.createdAt === createdAt)
    }
  }

  test("InsertUpdateDelete - insertProject") {
    withConnection { implicit c =>
      InsertUpdateDelete.insertProject(
        10, "Project Delta", BigDecimal(30000), java.time.LocalDate.of(2024, 6, 1)
      )
      assert(BasicQueries.countDepartments() === 4L) // unchanged
    }
  }

  test("InsertUpdateDelete - updateSalary") {
    withConnection { implicit c =>
      val affected = InsertUpdateDelete.updateSalary(1, BigDecimal(100000))
      assert(affected === 1)
      val emp = RowParsers.getEmployeeById(1)
      assert(emp.get.salary === BigDecimal("100000.00"))
    }
  }

  test("InsertUpdateDelete - deactivateEmployee") {
    withConnection { implicit c =>
      val affected = InsertUpdateDelete.deactivateEmployee(1, "Left company")
      assert(affected === 1)
      val emp = RowParsers.getEmployeeById(1)
      assert(emp.get.isActive === false)
      assert(emp.get.notes === Some("Left company"))

      // second call is a no-op — employee is already inactive; YDB may
      // still report 1 affected row, so we verify state instead
      InsertUpdateDelete.deactivateEmployee(1, "again")
      val emp2 = RowParsers.getEmployeeById(1)
      assert(emp2.get.isActive === false)
      assert(emp2.get.notes === Some("Left company"))
    }
  }

  test("InsertUpdateDelete - deleteProject") {
    withConnection { implicit c =>
      import anorm._
      SQL"DELETE FROM employee_projects WHERE project_id = 3".executeUpdate()
      InsertUpdateDelete.deleteProject(3)
      // verify the row is gone
      val count = SQL"SELECT count(*) FROM projects WHERE id = 3"
        .as(anorm.SqlParser.scalar[Long].single)
      assert(count === 0L)
    }
  }

  test("InsertUpdateDelete - upsertDepartment (YDB UPSERT)") {
    withConnection { implicit c =>
      InsertUpdateDelete.upsertDepartment(10, "Legal", "Building E", BigDecimal(100000))
      assert(BasicQueries.findDepartmentNameById(10) === Some("Legal"))

      // upsert existing row — replaces all columns
      InsertUpdateDelete.upsertDepartment(1, "Engineering", "Building Z", BigDecimal(999999))
      val result = BasicQueries.findDepartmentWithMaxBudget()
      assert(result.get._1 === "Engineering")
      assert(result.get._2 === BigDecimal("999999.00"))
    }
  }

  // ---------------------------------------------------------------------------
  // StreamingResults
  // ---------------------------------------------------------------------------

  test("StreamingResults - totalSalary (fold)") {
    withConnection { implicit c =>
      assert(StreamingResults.totalSalary() === BigDecimal(546000))
    }
  }

  test("StreamingResults - employeesUntilBudget (foldWhile)") {
    withConnection { implicit c =>
      val names = StreamingResults.employeesUntilBudget(BigDecimal(150000))
      assert(names === List("Eve", "Carol"))
    }
  }

  test("StreamingResults - collectActiveEmployeeNames (withResult cursor)") {
    withConnection { implicit c =>
      val names = StreamingResults.collectActiveEmployeeNames()
      assert(names === List("Alice", "Bob", "Carol", "Dave", "Eve"))
    }
  }

  test("StreamingResults - countRowsViaFold") {
    withConnection { implicit c =>
      assert(StreamingResults.countRowsViaFold() === 6)
    }
  }

  // ---------------------------------------------------------------------------
  // AnormMacros
  // ---------------------------------------------------------------------------

  test("AnormMacros - getAllDepartments (Macro.namedParser)") {
    withConnection { implicit c =>
      val depts = AnormMacros.getAllDepartments()
      assert(depts.length === 4)
      assert(depts.head.name === "Engineering")
    }
  }

  test("AnormMacros - getEmployeeById (SnakeCase naming)") {
    withConnection { implicit c =>
      val emp = AnormMacros.getEmployeeById(1)
      assert(emp.isDefined)
      assert(emp.get.firstName === "Alice")
      assert(emp.get.hireDate === java.time.LocalDate.of(2020, 1, 15))
    }
  }

  test("AnormMacros - getDeptSummaries (indexedParser)") {
    withConnection { implicit c =>
      val summaries = AnormMacros.getDeptSummaries()
      assert(summaries.length === 4)
      assert(summaries.exists(_.name == "Engineering"))
    }
  }

  test("AnormMacros - getEmployeeBriefs (aliased join)") {
    withConnection { implicit c =>
      val briefs = AnormMacros.getEmployeeBriefs()
      assert(briefs.exists(b => b.name == "Alice" && b.dept == "Engineering"))
    }
  }

  // ---------------------------------------------------------------------------
  // Transactions
  // ---------------------------------------------------------------------------

  test("Transactions - transferBudget (commit)") {
    withConnection { implicit c =>
      implicit val operationId: UUID = UUID.randomUUID()
      val before1 = Transactions.getDepartmentBudget(1)
      val before2 = Transactions.getDepartmentBudget(2)
      val ok = Transactions.transferBudget(1, 2, BigDecimal(50000))
      assert(ok === true)
      assert(Transactions.getDepartmentBudget(1) === before1 - BigDecimal(50000))
      assert(Transactions.getDepartmentBudget(2) === before2 + BigDecimal(50000))
    }
  }

  test("Transactions - transferBudget skips work when operation id already applied") {
    withConnection { implicit c =>
      implicit val operationId: UUID = UUID.randomUUID()
      val before1 = Transactions.getDepartmentBudget(1)
      val before2 = Transactions.getDepartmentBudget(2)
      assert(Transactions.transferBudget(1, 2, BigDecimal(1000)) === true)
      assert(Transactions.transferBudget(1, 2, BigDecimal(1000)) === true)
      assert(Transactions.getDepartmentBudget(1) === before1 - BigDecimal(1000))
      assert(Transactions.getDepartmentBudget(2) === before2 + BigDecimal(1000))
    }
  }

  test("Transactions - hireWithBudgetCheck (approved)") {
    withConnection { implicit c =>
      implicit val operationId: UUID = UUID.randomUUID()
      val result = Transactions.hireWithBudgetCheck(
        10, "Grace", "Hopper", "grace@example.com",
        BigDecimal(80000), 2, BigDecimal(500000)
      )
      assert(result.isRight)
      assert(result.right.get === 10)
    }
  }

  test("Transactions - hireWithBudgetCheck (rejected by budget)") {
    withConnection { implicit c =>
      implicit val operationId: UUID = UUID.randomUUID()
      val result = Transactions.hireWithBudgetCheck(
        11, "Reject", "Person", "reject@example.com",
        BigDecimal(900000), 2, BigDecimal(100000)
      )
      assert(result.isLeft)
      assert(result.left.getOrElse("").toString.contains("exceed budget"))
    }
  }

  test("Transactions - hireWithBudgetCheck skips work when operation id already applied") {
    withConnection { implicit c =>
      implicit val operationId: UUID = UUID.randomUUID()
      val deptBefore = Transactions.getDepartmentBudget(2)
      val r1 = Transactions.hireWithBudgetCheck(
        10, "Grace", "Hopper", "grace@example.com",
        BigDecimal(80000), 2, BigDecimal(500000)
      )
      assert(r1 === Right(10))
      val r2 = Transactions.hireWithBudgetCheck(
        10, "Grace", "Hopper", "grace@example.com",
        BigDecimal(80000), 2, BigDecimal(500000)
      )
      assert(r2 === Right(10))
      assert(Transactions.getDepartmentBudget(2) === deptBefore + BigDecimal(80000))
    }
  }

  test("Transactions - retry pattern with YdbRetry") {
    implicit val cfg: RetryConfig = RetryConfig(maxRetries = 3, initialBackoffMs = 1)
    var attempts = 0
    val ok = YdbRetry.retryIdempotent { implicit operationId =>
      withConnection { implicit c =>
        attempts += 1
        if (attempts < 3) throw new SQLTransientException("simulated conditionally retryable")
        Transactions.transferBudget(1, 2, BigDecimal(10000))
      }
    }
    assert(ok === true)
    assert(attempts === 3)
  }

  // ---------------------------------------------------------------------------
  // BatchOperations
  // ---------------------------------------------------------------------------

  test("BatchOperations - batchInsertDepartments") {
    withConnection { implicit c =>
      val depts = Seq(
        (10, "R&D",   "Building D", BigDecimal(180000)),
        (11, "Legal", "Building E", BigDecimal(120000)),
        (12, "Ops",   "Building F", BigDecimal(90000))
      )
      BatchOperations.batchInsertDepartments(depts)
      assert(BatchOperations.countDepartments() === 7L)
    }
  }

  test("BatchOperations - batchUpdateSalaries") {
    withConnection { implicit c =>
      val updates = Seq(
        (1, BigDecimal(100000)),
        (2, BigDecimal(110000)),
        (3, BigDecimal(82000))
      )
      BatchOperations.batchUpdateSalaries(updates)
      assert(BatchOperations.getSalary(1) === BigDecimal("100000.00"))
      assert(BatchOperations.getSalary(2) === BigDecimal("110000.00"))
      assert(BatchOperations.getSalary(3) === BigDecimal("82000.00"))
    }
  }

  test("BatchOperations - batchInsertEmployeeProjects") {
    withConnection { implicit c =>
      val assignments = Seq(
        (2, 2, "Backend Developer"),
        (6, 3, "HR Representative")
      )
      BatchOperations.batchInsertEmployeeProjects(assignments)

      import anorm._
      import anorm.SqlParser._
      val count = SQL"SELECT count(*) FROM employee_projects".as(scalar[Long].single)
      assert(count === 9L)
    }
  }

  // ---------------------------------------------------------------------------
  // TimestampQueries
  // ---------------------------------------------------------------------------

  test("TimestampQueries - getEmployeeCreatedAt") {
    withConnection { implicit c =>
      val ts = TimestampQueries.getEmployeeCreatedAt(1)
      assert(ts.isDefined)
      assert(ts.get === LocalDateTime.of(2020, 1, 15, 9, 0, 0))
    }
  }

  test("TimestampQueries - getAllCreatedTimestamps") {
    withConnection { implicit c =>
      val all = TimestampQueries.getAllCreatedTimestamps()
      assert(all.length === 6)
      assert(all.head._1 === "Frank") // earliest: 2017-02-28
      assert(all.last._1 === "Eve")   // latest: 2022-07-01
    }
  }

  test("TimestampQueries - findEmployeesCreatedBetween") {
    withConnection { implicit c =>
      val from = LocalDateTime.of(2019, 1, 1, 0, 0, 0)
      val to   = LocalDateTime.of(2021, 1, 1, 0, 0, 0)
      val names = TimestampQueries.findEmployeesCreatedBetween(from, to)
      assert(names === List("Alice", "Bob"))
    }
  }

  test("TimestampQueries - findEmployeesCreatedAfter") {
    withConnection { implicit c =>
      val ts = LocalDateTime.of(2021, 1, 1, 0, 0, 0)
      val names = TimestampQueries.findEmployeesCreatedAfter(ts)
      assert(names === List("Carol", "Eve"))
    }
  }

  test("TimestampQueries - findEmployeesCreatedBefore") {
    withConnection { implicit c =>
      val ts = LocalDateTime.of(2019, 1, 1, 0, 0, 0)
      val names = TimestampQueries.findEmployeesCreatedBefore(ts)
      assert(names.sorted === List("Dave", "Frank"))
    }
  }

  test("TimestampQueries - insertEmployeeWithTimestamp") {
    withConnection { implicit c =>
      val createdAt = LocalDateTime.of(2024, 6, 15, 14, 30, 0)
      TimestampQueries.insertEmployeeWithTimestamp(
        10, "Grace", "Hopper", "grace@example.com",
        java.time.LocalDate.of(2024, 6, 15), BigDecimal(120000), 1, createdAt
      )
      val ts = TimestampQueries.getEmployeeCreatedAt(10)
      assert(ts === Some(createdAt))
    }
  }

  test("TimestampQueries - countEmployeesCreatedOnDate") {
    withConnection { implicit c =>
      assert(TimestampQueries.countEmployeesCreatedOnDate(2020, 1, 15) === 1L)
      assert(TimestampQueries.countEmployeesCreatedOnDate(2020, 1, 16) === 0L)
    }
  }

  test("TimestampQueries - getLatestCreatedAt") {
    withConnection { implicit c =>
      val latest = TimestampQueries.getLatestCreatedAt()
      assert(latest === Some(LocalDateTime.of(2022, 7, 1, 11, 45, 0)))
    }
  }

  test("TimestampQueries - getEarliestCreatedAt") {
    withConnection { implicit c =>
      val earliest = TimestampQueries.getEarliestCreatedAt()
      assert(earliest === Some(LocalDateTime.of(2017, 2, 28, 16, 30, 0)))
    }
  }

  test("TimestampQueries - Employee model includes createdAt") {
    withConnection { implicit c =>
      val alice = RowParsers.getEmployeeById(1)
      assert(alice.isDefined)
      assert(alice.get.createdAt === LocalDateTime.of(2020, 1, 15, 9, 0, 0))
    }
  }

  // ---------------------------------------------------------------------------
  // FloatingPointQueries
  // ---------------------------------------------------------------------------

  test("FloatingPointQueries - getRating (nullable Double)") {
    withConnection { implicit c =>
      assert(FloatingPointQueries.getRating(1) === Some(4.5))
      assert(FloatingPointQueries.getRating(4) === Some(4.9))
      assert(FloatingPointQueries.getRating(5) === None) // Eve has no rating
    }
  }

  test("FloatingPointQueries - getBonusMultiplier (non-null Double)") {
    withConnection { implicit c =>
      assert(FloatingPointQueries.getBonusMultiplier(1) === 1.15)
      assert(FloatingPointQueries.getBonusMultiplier(4) === 1.25)
      assert(FloatingPointQueries.getBonusMultiplier(5) === 1.0)
      assert(FloatingPointQueries.getBonusMultiplier(6) === 0.95)
    }
  }

  test("FloatingPointQueries - getNamesAndRatings (nullable column listing)") {
    withConnection { implicit c =>
      val all = FloatingPointQueries.getNamesAndRatings()
      assert(all.length === 6)
      assert(all.find(_._1 == "Alice").get._2 === Some(4.5))
      assert(all.find(_._1 == "Eve").get._2 === None)
    }
  }

  test("FloatingPointQueries - findByMinRating (filter Double >=)") {
    withConnection { implicit c =>
      val top = FloatingPointQueries.findByMinRating(4.0)
      assert(top.map(_._1) === List("Dave", "Alice", "Bob"))
      assert(top.head._2 === 4.9)
    }
  }

  test("FloatingPointQueries - findByRatingRange (filter Double between)") {
    withConnection { implicit c =>
      val mid = FloatingPointQueries.findByRatingRange(3.5, 4.3)
      assert(mid.map(_._1) === List("Carol", "Bob"))
      assert(mid.head._2 === 3.8)
      assert(mid.last._2 === 4.2)
    }
  }

  test("FloatingPointQueries - findByBonusMultiplierAbove (filter Double >)") {
    withConnection { implicit c =>
      val above = FloatingPointQueries.findByBonusMultiplierAbove(1.0)
      assert(above.map(_._1) === List("Dave", "Alice", "Bob", "Carol"))
      assert(above.head._2 === 1.25)
    }
  }

  test("FloatingPointQueries - findWithNullRating / findWithNonNullRating") {
    withConnection { implicit c =>
      assert(FloatingPointQueries.findWithNullRating() === List("Eve"))
      assert(FloatingPointQueries.findWithNonNullRating().length === 5)
    }
  }

  test("FloatingPointQueries - averageRating (aggregate on nullable Double)") {
    withConnection { implicit c =>
      val avg = FloatingPointQueries.averageRating()
      assert(avg.isDefined)
      assert(math.abs(avg.get - 4.12) < 0.01)
    }
  }

  test("FloatingPointQueries - sumBonusMultipliers (aggregate on non-null Double)") {
    withConnection { implicit c =>
      val total = FloatingPointQueries.sumBonusMultipliers()
      assert(math.abs(total - 6.5) < 0.01)
    }
  }

  test("FloatingPointQueries - maxRating") {
    withConnection { implicit c =>
      assert(FloatingPointQueries.maxRating() === Some(4.9))
    }
  }

  test("FloatingPointQueries - updateRating (set and clear)") {
    withConnection { implicit c =>
      FloatingPointQueries.updateRating(5, Some(3.7))
      assert(FloatingPointQueries.getRating(5) === Some(3.7))

      FloatingPointQueries.updateRating(1, None)
      assert(FloatingPointQueries.getRating(1) === None)
    }
  }

  test("FloatingPointQueries - updateBonusMultiplier") {
    withConnection { implicit c =>
      FloatingPointQueries.updateBonusMultiplier(2, 1.30)
      assert(FloatingPointQueries.getBonusMultiplier(2) === 1.30)
    }
  }

  test("FloatingPointQueries - insertEmployeeWithDoubles") {
    withConnection { implicit c =>
      val createdAt = LocalDateTime.of(2024, 1, 1, 12, 0, 0)
      FloatingPointQueries.insertEmployeeWithDoubles(
        10, "Grace", "Hopper", "grace@example.com",
        java.time.LocalDate.of(2024, 1, 1), BigDecimal(120000), 1,
        createdAt, Some(4.7), 1.20
      )
      val emp = RowParsers.getEmployeeById(10)
      assert(emp.isDefined)
      assert(emp.get.rating === Some(4.7))
      assert(emp.get.bonusMultiplier === 1.20)
    }
  }

  test("FloatingPointQueries - insertEmployeeWithDoubles (null rating)") {
    withConnection { implicit c =>
      val createdAt = LocalDateTime.of(2024, 2, 1, 12, 0, 0)
      FloatingPointQueries.insertEmployeeWithDoubles(
        11, "Null", "Rating", "null.rating@example.com",
        java.time.LocalDate.of(2024, 2, 1), BigDecimal(80000), 2,
        createdAt, None, 1.0
      )
      val emp = RowParsers.getEmployeeById(11)
      assert(emp.isDefined)
      assert(emp.get.rating === None)
      assert(emp.get.bonusMultiplier === 1.0)
    }
  }

  // ---------------------------------------------------------------------------
  // DecimalQueries
  // ---------------------------------------------------------------------------

  test("DecimalQueries - getDepartmentBudget (read Decimal)") {
    withConnection { implicit c =>
      assert(DecimalQueries.getDepartmentBudget(1) === Some(BigDecimal("500000.00")))
      assert(DecimalQueries.getDepartmentBudget(2) === Some(BigDecimal("300000.00")))
      assert(DecimalQueries.getDepartmentBudget(999) === None)
    }
  }

  test("DecimalQueries - getEmployeeSalary (read Decimal)") {
    withConnection { implicit c =>
      assert(DecimalQueries.getEmployeeSalary(1) === Some(BigDecimal("95000.00")))
      assert(DecimalQueries.getEmployeeSalary(4) === Some(BigDecimal("115000.00")))
    }
  }

  test("DecimalQueries - write and re-read Decimal preserves precision") {
    withConnection { implicit c =>
      val salary = BigDecimal("123456.78")
      val readBack = DecimalQueries.insertAndReadBackSalary(
        20, "Test", "Decimal", "test.decimal@example.com", salary, 1
      )
      assert(readBack === salary)
    }
  }

  test("DecimalQueries - write and re-read Decimal with trailing zeros") {
    withConnection { implicit c =>
      val salary = BigDecimal("99000.10")
      val readBack = DecimalQueries.insertAndReadBackSalary(
        21, "Trail", "Zeros", "trail.zeros@example.com", salary, 1
      )
      assert(readBack.scale >= 1)
      assert(readBack === salary)
    }
  }

  test("DecimalQueries - updateDepartmentBudget and re-read") {
    withConnection { implicit c =>
      val newBudget = BigDecimal("777777.77")
      DecimalQueries.updateDepartmentBudget(1, newBudget)
      assert(DecimalQueries.getDepartmentBudget(1) === Some(newBudget))
    }
  }

  test("DecimalQueries - findDepartmentsByBudgetRange (filter Decimal)") {
    withConnection { implicit c =>
      val result = DecimalQueries.findDepartmentsByBudgetRange(
        BigDecimal("200000"), BigDecimal("400000")
      )
      assert(result.map(_._1) === List("Sales", "Marketing"))
      assert(result.head._2 === BigDecimal("250000.00"))
      assert(result.last._2 === BigDecimal("300000.00"))
    }
  }

  test("DecimalQueries - findEmployeesBySalaryAbove (filter Decimal)") {
    withConnection { implicit c =>
      val result = DecimalQueries.findEmployeesBySalaryAbove(BigDecimal("100000"))
      assert(result.map(_._1) === List("Bob", "Dave"))
      assert(result.head._2 === BigDecimal("105000.00"))
      assert(result.last._2 === BigDecimal("115000.00"))
    }
  }

  test("DecimalQueries - sumDepartmentBudgets (aggregate Decimal)") {
    withConnection { implicit c =>
      val total = DecimalQueries.sumDepartmentBudgets()
      assert(total === BigDecimal("1200000.00"))
    }
  }

  test("DecimalQueries - avgEmployeeSalary (aggregate Decimal)") {
    withConnection { implicit c =>
      val avg = DecimalQueries.avgEmployeeSalary()
      assert(avg.isDefined)
      assert((avg.get - BigDecimal(91000)).abs < BigDecimal(1))
    }
  }

  test("DecimalQueries - maxDepartmentBudget") {
    withConnection { implicit c =>
      assert(DecimalQueries.maxDepartmentBudget() === Some(BigDecimal("500000.00")))
    }
  }

  test("DecimalQueries - minEmployeeSalary") {
    withConnection { implicit c =>
      assert(DecimalQueries.minEmployeeSalary() === Some(BigDecimal("65000.00")))
    }
  }

  // ---------------------------------------------------------------------------
  // YDB topic + JDBC transaction (TransactionalTopicSample)
  // ---------------------------------------------------------------------------

  test("TransactionalTopicSample - multi-topic per JDBC txn (implicit publish context)") {
    val pool = Executors.newCachedThreadPool()
    try {
      val conn = openConnection()
      try {
        val marker = s"topic-demo-${UUID.randomUUID()}"
        implicit val ydbTopicPublish: YdbTopicPublishContext =
          YdbTopicPublishContext(pool, TransactionalTopicSample.ProducerId)
        val name =
          TransactionalTopicSample.runDemoTransaction(conn, departmentId = 1, locationMarker = marker)
        assert(name === "Engineering")
        withConnection { implicit c =>
          val loc =
            SQL"SELECT location FROM departments WHERE id = 1"
              .as(str("location").singleOpt)
          assert(loc === Some(marker))
        }
      } finally conn.close()
    } finally {
      pool.shutdown()
      try pool.awaitTermination(5L, TimeUnit.SECONDS)
      catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }
  }

  // ---------------------------------------------------------------------------
  // LargeResultSets
  // ---------------------------------------------------------------------------

  test("LargeResultSets - countRows streams 1M rows with fetch size 10000") {
    withConnection { implicit c =>
      assert(LargeResultSets.countRows() === 1_000_000L)
    }
  }

  test("LargeResultSets - countUpTo stops early without scanning the full set") {
    withConnection { implicit c =>
      assert(LargeResultSets.countUpTo(12_345L) === 12_345L)
    }
  }

  test("LargeResultSets - sampleRow reads one row without materializing the set") {
    withConnection { implicit c =>
      assert(LargeResultSets.sampleRow().isDefined)
    }
  }
}
