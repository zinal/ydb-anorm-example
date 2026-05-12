name := "anorm-pg-example"

version := "0.1.0"

scalaVersion := "2.13.15"

libraryDependencies ++= Seq(
  "org.playframework.anorm" %% "anorm"                            % "2.7.0",
  "org.postgresql"           % "postgresql"                        % "42.7.4",
  "org.scalatest"           %% "scalatest"                         % "3.2.19"  % Test,
  "com.dimafeng"            %% "testcontainers-scala-scalatest"    % "0.41.4"  % Test,
  "com.dimafeng"            %% "testcontainers-scala-postgresql"   % "0.41.4"  % Test,
  "org.slf4j"                % "slf4j-simple"                      % "2.0.16"  % Test
)

Test / fork := true
