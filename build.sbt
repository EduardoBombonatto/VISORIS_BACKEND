val Http4sVersion = "0.23.36"
val CirceVersion = "0.14.14"
val MunitVersion = "1.3.4"
val LogbackVersion = "1.6.0"
val DoobieVersion = "1.0.0-RC12"
val MunitCatsEffectVersion = "2.2.0"
val CirisVersion = "3.15.0"
val FlywayVersion = "13.0.0"
val JwtScalaVersion = "11.0.4"
val SwaggerUiVersion = "5.32.13"
val PostgresVersion = "42.7.13"
val JBcryptVersion = "0.4"

lazy val root = (project in file("."))
  .settings(
    organization := "com.visoris",
    name := "backend",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "3.3.6",
    libraryDependencies ++= Seq(
      "org.http4s"      %% "http4s-ember-server" % Http4sVersion,
      "org.http4s"      %% "http4s-ember-client" % Http4sVersion,
      "org.http4s"      %% "http4s-circe"        % Http4sVersion,
      "io.circe"        %% "circe-generic"       % CirceVersion,
      "org.http4s"      %% "http4s-dsl"          % Http4sVersion,
      "is.cir"          %% "ciris"               % CirisVersion,
      "org.tpolecat"    %% "doobie-core"         % DoobieVersion,
      "org.tpolecat"    %% "doobie-h2"           % DoobieVersion,
      "org.tpolecat"    %% "doobie-hikari"       % DoobieVersion,
      "org.tpolecat"    %% "doobie-postgres"     % DoobieVersion,
      "org.flywaydb"    % "flyway-core"         % FlywayVersion,
      "org.flywaydb"   % "flyway-database-postgresql" % FlywayVersion,
      "org.postgresql"  %  "postgresql"          % PostgresVersion,
      "org.mindrot"     %  "jbcrypt"             % "0.4",
      "com.github.jwt-scala" %% "jwt-circe"      % JwtScalaVersion,
      "org.mindrot" % "jbcrypt" % JBcryptVersion,
      "org.webjars"  % "swagger-ui"             % SwaggerUiVersion,
      "org.scalameta"   %% "munit"               % MunitVersion           % Test,
      "org.typelevel"   %% "munit-cats-effect"   % MunitCatsEffectVersion % Test,
      "ch.qos.logback"  %  "logback-classic"     % LogbackVersion         % Runtime,
    ),
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case x => (assembly / assemblyMergeStrategy).value.apply(x)
    },
    assembly / assemblyJarName := "app.jar"
  )
