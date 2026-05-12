name := "anorm-ydb-example"

version := "0.1.0"

scalaVersion := "2.13.15"

libraryDependencies ++= Seq(
  "org.playframework.anorm" %% "anorm"                            % "2.7.0",
  "tech.ydb.jdbc"            % "ydb-jdbc-driver-shaded"            % "2.3.24",
  "org.scalatest"           %% "scalatest"                         % "3.2.19"  % Test,
  "org.testcontainers"       % "testcontainers"                    % "1.19.8"  % Test,
  "org.slf4j"                % "slf4j-simple"                      % "2.0.16"  % Test
)

Test / fork := true
