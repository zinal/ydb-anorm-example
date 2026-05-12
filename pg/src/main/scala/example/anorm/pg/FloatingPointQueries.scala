package example.anorm.pg

import java.sql.Connection
import anorm._
import anorm.SqlParser._

/** Demonstrates working with DOUBLE PRECISION (IEEE 754 floating-point) columns:
  * inserting, selecting, filtering by range, nullable vs non-null handling,
  * and aggregate functions on floating-point data.
  */
object FloatingPointQueries {

  def getRating(empId: Int)(implicit c: Connection): Option[Double] =
    SQL"SELECT rating FROM employees WHERE id = $empId"
      .as(get[Option[Double]]("rating").single)

  def getBonusMultiplier(empId: Int)(implicit c: Connection): Double =
    SQL"SELECT bonus_multiplier FROM employees WHERE id = $empId"
      .as(get[Double]("bonus_multiplier").single)

  def getNamesAndRatings()(implicit c: Connection): List[(String, Option[Double])] = {
    val parser = str("first_name") ~ get[Option[Double]]("rating") map SqlParser.flatten
    SQL"SELECT first_name, rating FROM employees ORDER BY first_name"
      .as(parser.*)
  }

  def findByMinRating(minRating: Double)(implicit c: Connection): List[(String, Double)] = {
    val parser = str("first_name") ~ get[Double]("rating") map SqlParser.flatten
    SQL"SELECT first_name, rating FROM employees WHERE rating >= $minRating ORDER BY rating DESC"
      .as(parser.*)
  }

  def findByRatingRange(min: Double, max: Double)(implicit c: Connection): List[(String, Double)] = {
    val parser = str("first_name") ~ get[Double]("rating") map SqlParser.flatten
    SQL"SELECT first_name, rating FROM employees WHERE rating >= $min AND rating <= $max ORDER BY rating"
      .as(parser.*)
  }

  def findByBonusMultiplierAbove(threshold: Double)(implicit c: Connection): List[(String, Double)] = {
    val parser = str("first_name") ~ get[Double]("bonus_multiplier") map SqlParser.flatten
    SQL"SELECT first_name, bonus_multiplier FROM employees WHERE bonus_multiplier > $threshold ORDER BY bonus_multiplier DESC"
      .as(parser.*)
  }

  def findWithNullRating()(implicit c: Connection): List[String] =
    SQL"SELECT first_name FROM employees WHERE rating IS NULL ORDER BY first_name"
      .as(str("first_name").*)

  def findWithNonNullRating()(implicit c: Connection): List[String] =
    SQL"SELECT first_name FROM employees WHERE rating IS NOT NULL ORDER BY first_name"
      .as(str("first_name").*)

  def averageRating()(implicit c: Connection): Option[Double] =
    SQL"SELECT AVG(rating) AS avg_rating FROM employees"
      .as(get[Option[Double]]("avg_rating").single)

  def sumBonusMultipliers()(implicit c: Connection): Double =
    SQL"SELECT COALESCE(SUM(bonus_multiplier), 0.0) AS total FROM employees"
      .as(get[Double]("total").single)

  def maxRating()(implicit c: Connection): Option[Double] =
    SQL"SELECT MAX(rating) AS max_r FROM employees"
      .as(get[Option[Double]]("max_r").single)

  def updateRating(empId: Int, newRating: Option[Double])(implicit c: Connection): Int =
    newRating match {
      case Some(r) =>
        SQL"UPDATE employees SET rating = $r WHERE id = $empId".executeUpdate()
      case None =>
        SQL"UPDATE employees SET rating = NULL WHERE id = $empId".executeUpdate()
    }

  def updateBonusMultiplier(empId: Int, multiplier: Double)(implicit c: Connection): Int =
    SQL"UPDATE employees SET bonus_multiplier = $multiplier WHERE id = $empId"
      .executeUpdate()

  def insertEmployeeWithDoubles(
      firstName: String,
      lastName: String,
      email: String,
      hireDate: java.time.LocalDate,
      salary: BigDecimal,
      departmentId: Int,
      rating: Option[Double],
      bonusMultiplier: Double
  )(implicit c: Connection): Option[Long] =
    SQL"""INSERT INTO employees(first_name, last_name, email, hire_date, salary,
            department_id, rating, bonus_multiplier)
          VALUES ($firstName, $lastName, $email, $hireDate, $salary,
            $departmentId, $rating, $bonusMultiplier)"""
      .executeInsert()
}
