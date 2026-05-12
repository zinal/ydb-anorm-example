package example.anorm.pg

import java.time.{LocalDate, LocalDateTime}

case class Department(
    id: Int,
    name: String,
    location: Option[String],
    budget: BigDecimal
)

case class Employee(
    id: Int,
    firstName: String,
    lastName: String,
    email: String,
    hireDate: LocalDate,
    salary: BigDecimal,
    departmentId: Option[Int],
    isActive: Boolean,
    notes: Option[String],
    createdAt: LocalDateTime
)

case class Project(
    id: Int,
    name: String,
    budget: Option[BigDecimal],
    startDate: LocalDate,
    endDate: Option[LocalDate],
    description: Option[String]
)

case class EmployeeProject(
    employeeId: Int,
    projectId: Int,
    role: String,
    assignedDate: LocalDate
)
