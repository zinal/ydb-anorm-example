package example.anorm.pg

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.testcontainers.utility.DockerImageName

import java.sql.{Connection, DriverManager}
import java.time.{LocalDate, LocalDateTime}

class AnormPgSpec extends AnyFunSuite with ForAllTestContainer with BeforeAndAfterEach {

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine")
  )

  private def openConnection(): Connection =
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

  private def withConnection[T](f: Connection => T): T = {
    val conn = openConnection()
    try f(conn) finally conn.close()
  }

  private def runSqlResource(conn: Connection, resourcePath: String): Unit = {
    val sql  = scala.io.Source.fromResource(resourcePath).mkString
    val stmt = conn.createStatement()
    try stmt.execute(sql) finally stmt.close()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    withConnection { conn =>
      val stmt = conn.createStatement()
      try {
        stmt.execute("DROP TABLE IF EXISTS employee_projects CASCADE")
        stmt.execute("DROP TABLE IF EXISTS projects CASCADE")
        stmt.execute("DROP TABLE IF EXISTS employees CASCADE")
        stmt.execute("DROP TABLE IF EXISTS departments CASCADE")
      } finally stmt.close()
      runSqlResource(conn, "schema.sql")
      runSqlResource(conn, "data.sql")
    }
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
      assert(result.get._2 === BigDecimal(500000))
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
      assert(BasicQueries.getScalarBudgetSum() === BigDecimal(1200000))
    }
  }

  // ---------------------------------------------------------------------------
  // RowParsers
  // ---------------------------------------------------------------------------

  test("RowParsers - getAllDepartments") {
    withConnection { implicit c =>
      val depts = RowParsers.getAllDepartments()
      assert(depts.length === 4)
      assert(depts.head === Department(1, "Engineering", Some("Building A"), BigDecimal(500000)))
    }
  }

  test("RowParsers - getEmployeeById") {
    withConnection { implicit c =>
      val alice = RowParsers.getEmployeeById(1)
      assert(alice.isDefined)
      assert(alice.get.firstName === "Alice")
      assert(alice.get.lastName === "Smith")
      assert(alice.get.salary === BigDecimal(95000))
      assert(alice.get.isActive === true)
      assert(alice.get.hireDate === LocalDate.of(2020, 1, 15))
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
      assert(list.head._2 === BigDecimal(115000))
    }
  }

  test("RowParsers - getEmployeeWithDepartment (joined parser)") {
    withConnection { implicit c =>
      val result = RowParsers.getEmployeeWithDepartment(1)
      assert(result.isDefined)
      assert(result.get.employee.firstName === "Alice")
      assert(result.get.departmentName === "Engineering")
      assert(result.get.employee.hireDate === LocalDate.of(2020, 1, 15))
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

  test("InsertUpdateDelete - insertDepartment (generated key)") {
    withConnection { implicit c =>
      val id = InsertUpdateDelete.insertDepartment("R&D", "Building D", BigDecimal(200000))
      assert(id.isDefined)
      assert(id.get > 4)
      assert(BasicQueries.countDepartments() === 5L)
    }
  }

  test("InsertUpdateDelete - insertEmployee (named params)") {
    withConnection { implicit c =>
      val hireDate = LocalDate.of(2024, 1, 1)
      val id = InsertUpdateDelete.insertEmployee(
        "Grace", "Hopper", "grace@example.com",
        hireDate, BigDecimal(120000), 1
      )
      assert(id.isDefined)
      val emp = RowParsers.getEmployeeById(id.get.toInt)
      assert(emp.isDefined)
      assert(emp.get.firstName === "Grace")
      assert(emp.get.hireDate === hireDate)
      assert(emp.get.createdAt !== null)
    }
  }

  test("InsertUpdateDelete - insertProjectReturningInt (typed key parser)") {
    withConnection { implicit c =>
      val id = InsertUpdateDelete.insertProjectReturningInt(
        "Project Delta", BigDecimal(30000), LocalDate.of(2024, 6, 1)
      )
      assert(id.isDefined)
      assert(id.get > 3)
    }
  }

  test("InsertUpdateDelete - updateSalary") {
    withConnection { implicit c =>
      val affected = InsertUpdateDelete.updateSalary(1, BigDecimal(100000))
      assert(affected === 1)
      val emp = RowParsers.getEmployeeById(1)
      assert(emp.get.salary === BigDecimal(100000))
    }
  }

  test("InsertUpdateDelete - deactivateEmployee") {
    withConnection { implicit c =>
      val affected = InsertUpdateDelete.deactivateEmployee(1, "Left company")
      assert(affected === 1)
      val emp = RowParsers.getEmployeeById(1)
      assert(emp.get.isActive === false)
      assert(emp.get.notes === Some("Left company"))

      // second call is a no-op because already inactive
      assert(InsertUpdateDelete.deactivateEmployee(1, "again") === 0)
    }
  }

  test("InsertUpdateDelete - deleteProject") {
    withConnection { implicit c =>
      // first remove FK references
      import anorm._
      SQL"DELETE FROM employee_projects WHERE project_id = 3".executeUpdate()
      assert(InsertUpdateDelete.deleteProject(3) === 1)
      assert(InsertUpdateDelete.deleteProject(3) === 0)
    }
  }

  test("InsertUpdateDelete - upsertDepartment (ON CONFLICT)") {
    withConnection { implicit c =>
      // new row
      InsertUpdateDelete.upsertDepartment("Legal", "Building E", BigDecimal(100000))
      assert(BasicQueries.findDepartmentNameById(5) === Some("Legal"))

      // update existing
      InsertUpdateDelete.upsertDepartment("Engineering", "Building Z", BigDecimal(999999))
      val result = BasicQueries.findDepartmentWithMaxBudget()
      assert(result.get._1 === "Engineering")
      assert(result.get._2 === BigDecimal(999999))
    }
  }

  // ---------------------------------------------------------------------------
  // StreamingResults
  // ---------------------------------------------------------------------------

  test("StreamingResults - totalSalary (fold)") {
    withConnection { implicit c =>
      val total = StreamingResults.totalSalary()
      assert(total === BigDecimal(546000))
    }
  }

  test("StreamingResults - employeesUntilBudget (foldWhile)") {
    withConnection { implicit c =>
      // employees ordered by salary: Eve(65k), Carol(78k), Frank(88k), Alice(95k), Bob(105k), Dave(115k)
      val names = StreamingResults.employeesUntilBudget(BigDecimal(150000))
      assert(names === List("Eve", "Carol"))
    }
  }

  test("StreamingResults - collectActiveEmployeeNames (withResult cursor)") {
    withConnection { implicit c =>
      val names = StreamingResults.collectActiveEmployeeNames()
      // Frank is inactive
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
      assert(emp.get.hireDate === LocalDate.of(2020, 1, 15))
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
      val before1 = Transactions.getDepartmentBudget(1) // 500k
      val before2 = Transactions.getDepartmentBudget(2) // 300k
      val ok = Transactions.transferBudget(1, 2, BigDecimal(50000))
      assert(ok === true)
      assert(Transactions.getDepartmentBudget(1) === before1 - 50000)
      assert(Transactions.getDepartmentBudget(2) === before2 + 50000)
    }
  }

  test("Transactions - hireWithBudgetCheck (approved)") {
    withConnection { implicit c =>
      val result = Transactions.hireWithBudgetCheck(
        "Grace", "Hopper", "grace@example.com",
        BigDecimal(80000), 2, BigDecimal(500000)
      )
      assert(result.isRight)
    }
  }

  test("Transactions - hireWithBudgetCheck (rejected by budget)") {
    withConnection { implicit c =>
      val result = Transactions.hireWithBudgetCheck(
        "Reject", "Person", "reject@example.com",
        BigDecimal(900000), 2, BigDecimal(100000)
      )
      assert(result.isLeft)
      assert(result.left.getOrElse("").contains("exceed budget"))
    }
  }

  test("Transactions - withSavepoint") {
    withConnection { implicit c =>
      // dept 3 (Sales) has budget 250000
      // +100k => 350k, -500k => would go to -150k (negative, rolled back), +50k => 400k
      val result = Transactions.withSavepoint(3, List(
        BigDecimal(100000), BigDecimal(-500000), BigDecimal(50000)
      ))
      assert(result === BigDecimal(400000))
    }
  }

  // ---------------------------------------------------------------------------
  // BatchOperations
  // ---------------------------------------------------------------------------

  test("BatchOperations - batchInsertDepartments") {
    withConnection { implicit c =>
      val depts = Seq(
        ("R&D",   "Building D", BigDecimal(180000)),
        ("Legal", "Building E", BigDecimal(120000)),
        ("Ops",   "Building F", BigDecimal(90000))
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
      assert(BatchOperations.getSalary(1) === BigDecimal(100000))
      assert(BatchOperations.getSalary(2) === BigDecimal(110000))
      assert(BatchOperations.getSalary(3) === BigDecimal(82000))
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
      assert(count === 9L) // 7 original + 2 new
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
      val id = TimestampQueries.insertEmployeeWithTimestamp(
        "Grace", "Hopper", "grace@example.com",
        LocalDate.of(2024, 6, 15), BigDecimal(120000), 1, createdAt
      )
      assert(id.isDefined)
      val ts = TimestampQueries.getEmployeeCreatedAt(id.get.toInt)
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
      val id = FloatingPointQueries.insertEmployeeWithDoubles(
        "Grace", "Hopper", "grace@example.com",
        LocalDate.of(2024, 1, 1), BigDecimal(120000), 1,
        Some(4.7), 1.20
      )
      assert(id.isDefined)
      val emp = RowParsers.getEmployeeById(id.get.toInt)
      assert(emp.isDefined)
      assert(emp.get.rating === Some(4.7))
      assert(emp.get.bonusMultiplier === 1.20)
    }
  }

  test("FloatingPointQueries - insertEmployeeWithDoubles (null rating)") {
    withConnection { implicit c =>
      val id = FloatingPointQueries.insertEmployeeWithDoubles(
        "Null", "Rating", "null.rating@example.com",
        LocalDate.of(2024, 2, 1), BigDecimal(80000), 2,
        None, 1.0
      )
      assert(id.isDefined)
      val emp = RowParsers.getEmployeeById(id.get.toInt)
      assert(emp.isDefined)
      assert(emp.get.rating === None)
      assert(emp.get.bonusMultiplier === 1.0)
    }
  }
}
