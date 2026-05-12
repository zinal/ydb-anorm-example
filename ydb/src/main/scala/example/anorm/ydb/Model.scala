package example.anorm.ydb

import java.time.LocalDate

case class Department(
    id: Int,
    name: String,
    location: Option[String],
    budget: Double
)

case class Employee(
    id: Int,
    firstName: String,
    lastName: String,
    email: String,
    hireDate: LocalDate,
    salary: Double,
    departmentId: Option[Int],
    isActive: Boolean,
    notes: Option[String]
)

case class Project(
    id: Int,
    name: String,
    budget: Option[Double],
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
