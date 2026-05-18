package example.anorm.ydb

import java.sql.Connection

import tech.ydb.common.transaction.YdbTransaction
import tech.ydb.core.grpc.GrpcTransport

object YdbTopicJdbc {

  def currentYdbTransaction(connection: Connection): YdbTransaction =
    connection.unwrap(classOf[YdbTransaction])

  def grpcTransport(connection: Connection): GrpcTransport =
    connection.unwrap(classOf[GrpcTransport])
}
