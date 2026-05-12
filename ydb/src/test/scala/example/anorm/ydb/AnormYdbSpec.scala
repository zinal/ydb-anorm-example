package example.anorm.ydb

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

import java.net.ServerSocket
import java.sql.{Connection, DriverManager, SQLRecoverableException, SQLTransientException}

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
    s"jdbc:ydb:grpc://${ydbContainer.getHost}:$grpcPort/local"

  override def beforeAll(): Unit = {
    super.beforeAll()
    ydbContainer.start()
  }

  override def afterAll(): Unit = {
    ydbContainer.stop()
    super.afterAll()
  }

  private def openConnection(): Connection =
    DriverManager.getConnection(jdbcUrl)

  private def withConnection[T](f: Connection => T): T = {
    val conn = openConnection()
    try f(conn) finally conn.close()
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

  override def beforeEach(): Unit = {
    super.beforeEach()
    withConnection { conn =>
      tryDrop(conn, "employee_projects")
      tryDrop(conn, "projects")
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
    val result = YdbRetry.retry(idempotent = false) {
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
    val r1 = YdbRetry.retry(idempotent = true) {
      attempts1 += 1
      if (attempts1 < 3) throw new SQLTransientException("simulated conditionally retryable")
      "ok"
    }
    assert(r1 === "ok")
    assert(attempts1 === 3)

    var attempts2 = 0
    assertThrows[SQLTransientException] {
      YdbRetry.retry(idempotent = false) {
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
      assert(result.get._2 === 500000.0)
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
      assert(BasicQueries.getScalarBudgetSum() === 1200000.0)
    }
  }

  // ---------------------------------------------------------------------------
  // RowParsers
  // ---------------------------------------------------------------------------

  test("RowParsers - getAllDepartments") {
    withConnection { implicit c =>
      val depts = RowParsers.getAllDepartments()
      assert(depts.length === 4)
      assert(depts.head === Department(1, "Engineering", Some("Building A"), 500000.0))
    }
  }

  test("RowParsers - getEmployeeById") {
    withConnection { implicit c =>
      val alice = RowParsers.getEmployeeById(1)
      assert(alice.isDefined)
      assert(alice.get.firstName === "Alice")
      assert(alice.get.lastName === "Smith")
      assert(alice.get.salary === 95000.0)
      assert(alice.get.isActive === true)

      assert(RowParsers.getEmployeeById(999) === None)
    }
  }

  test("RowParsers - getNamesAndSalaries (flattened parser)") {
    withConnection { implicit c =>
      val list = RowParsers.getNamesAndSalaries()
      assert(list.length === 6)
      assert(list.head._1 === "Dave")
      assert(list.head._2 === 115000.0)
    }
  }

  test("RowParsers - getEmployeeWithDepartment (joined parser)") {
    withConnection { implicit c =>
      val result = RowParsers.getEmployeeWithDepartment(1)
      assert(result.isDefined)
      assert(result.get.employee.firstName === "Alice")
      assert(result.get.departmentName === "Engineering")
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
      val names = ParameterBinding.findBySalaryRange(80000.0, 100000.0)
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
      InsertUpdateDelete.insertDepartment(10, "R&D", "Building D", 200000.0)
      assert(BasicQueries.countDepartments() === 5L)
      assert(BasicQueries.findDepartmentNameById(10) === Some("R&D"))
    }
  }

  test("InsertUpdateDelete - insertEmployee (named params)") {
    withConnection { implicit c =>
      InsertUpdateDelete.insertEmployee(
        10, "Grace", "Hopper", "grace@example.com",
        java.time.LocalDate.of(2024, 1, 1), 120000.0, 1
      )
      val emp = RowParsers.getEmployeeById(10)
      assert(emp.isDefined)
      assert(emp.get.firstName === "Grace")
    }
  }

  test("InsertUpdateDelete - insertProject") {
    withConnection { implicit c =>
      InsertUpdateDelete.insertProject(
        10, "Project Delta", 30000.0, java.time.LocalDate.of(2024, 6, 1)
      )
      assert(BasicQueries.countDepartments() === 4L) // unchanged
    }
  }

  test("InsertUpdateDelete - updateSalary") {
    withConnection { implicit c =>
      val affected = InsertUpdateDelete.updateSalary(1, 100000.0)
      assert(affected === 1)
      val emp = RowParsers.getEmployeeById(1)
      assert(emp.get.salary === 100000.0)
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
      InsertUpdateDelete.upsertDepartment(10, "Legal", "Building E", 100000.0)
      assert(BasicQueries.findDepartmentNameById(10) === Some("Legal"))

      // upsert existing row — replaces all columns
      InsertUpdateDelete.upsertDepartment(1, "Engineering", "Building Z", 999999.0)
      val result = BasicQueries.findDepartmentWithMaxBudget()
      assert(result.get._1 === "Engineering")
      assert(result.get._2 === 999999.0)
    }
  }

  // ---------------------------------------------------------------------------
  // StreamingResults
  // ---------------------------------------------------------------------------

  test("StreamingResults - totalSalary (fold)") {
    withConnection { implicit c =>
      assert(StreamingResults.totalSalary() === 546000.0)
    }
  }

  test("StreamingResults - employeesUntilBudget (foldWhile)") {
    withConnection { implicit c =>
      val names = StreamingResults.employeesUntilBudget(150000.0)
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
      val before1 = Transactions.getDepartmentBudget(1)
      val before2 = Transactions.getDepartmentBudget(2)
      val ok = Transactions.transferBudget(1, 2, 50000.0)
      assert(ok === true)
      assert(Transactions.getDepartmentBudget(1) === before1 - 50000.0)
      assert(Transactions.getDepartmentBudget(2) === before2 + 50000.0)
    }
  }

  test("Transactions - hireWithBudgetCheck (approved)") {
    withConnection { implicit c =>
      val result = Transactions.hireWithBudgetCheck(
        10, "Grace", "Hopper", "grace@example.com",
        80000.0, 2, 500000.0
      )
      assert(result.isRight)
      assert(result.right.get === 10)
    }
  }

  test("Transactions - hireWithBudgetCheck (rejected by budget)") {
    withConnection { implicit c =>
      val result = Transactions.hireWithBudgetCheck(
        11, "Reject", "Person", "reject@example.com",
        900000.0, 2, 100000.0
      )
      assert(result.isLeft)
      assert(result.left.getOrElse("").contains("exceed budget"))
    }
  }

  test("Transactions - retry pattern with YdbRetry") {
    implicit val cfg: RetryConfig = RetryConfig(maxRetries = 3, initialBackoffMs = 1)
    val ok = YdbRetry.retry(idempotent = true) {
      withConnection { implicit c =>
        Transactions.transferBudget(1, 2, 10000.0)
      }
    }
    assert(ok === true)
  }

  // ---------------------------------------------------------------------------
  // BatchOperations
  // ---------------------------------------------------------------------------

  test("BatchOperations - batchInsertDepartments") {
    withConnection { implicit c =>
      val depts = Seq(
        (10, "R&D",   "Building D", 180000.0),
        (11, "Legal", "Building E", 120000.0),
        (12, "Ops",   "Building F", 90000.0)
      )
      BatchOperations.batchInsertDepartments(depts)
      assert(BatchOperations.countDepartments() === 7L)
    }
  }

  test("BatchOperations - batchUpdateSalaries") {
    withConnection { implicit c =>
      val updates = Seq(
        (1, 100000.0),
        (2, 110000.0),
        (3, 82000.0)
      )
      BatchOperations.batchUpdateSalaries(updates)
      assert(BatchOperations.getSalary(1) === 100000.0)
      assert(BatchOperations.getSalary(2) === 110000.0)
      assert(BatchOperations.getSalary(3) === 82000.0)
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
}
