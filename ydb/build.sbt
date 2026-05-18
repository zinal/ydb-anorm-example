name := "anorm-ydb-example"

version := "0.1.0"

scalaVersion := "2.13.15"

// JDBC driver version and ydb-sdk-topic must stay aligned with the driver's managed SDK BOM
// (`ydb.sdk.version` in `tech.ydb.jdbc:ydb-jdbc-driver-parent`), as in the jdbc-basic sample:
// https://github.com/zinal/ydb-snippets/tree/main/apps/jdbc-basic
val ydbJdbcVersion = "2.3.25"
val ydbSdkVersion  = "2.3.33"

libraryDependencies ++= Seq(
  "org.playframework.anorm" %% "anorm"                 % "2.7.0",
  "tech.ydb.jdbc"             % "ydb-jdbc-driver"       % ydbJdbcVersion,
  "tech.ydb"                  % "ydb-sdk-topic"         % ydbSdkVersion,
  "org.scalatest"            %% "scalatest"             % "3.2.19" % Test,
  "org.testcontainers"        % "testcontainers"        % "1.19.8" % Test,
  "org.slf4j"                 % "slf4j-simple"          % "2.0.16" % Test
)

Test / fork := true
