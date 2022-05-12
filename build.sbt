val Http4sV = "0.23.11"
val CirceV = "0.14.1"
val MunitV = "0.7.29"
val LogbackV = "1.2.10"
val MunitCatsEffectV = "1.0.7"
val SubsV = "20.2.0"
val ScalaTagsV = "0.8.2"
val KindProjectorV = "0.13.2"
val BetterMonadicForV = "0.3.1"
val CatsEffectV = "2.5.3"
val ScalaJSReactV = "2.1.1"

val ReactV = "18.1.0"

lazy val commonSettings = Seq(
  organization := "gov.usa.apollo",
  version := "0.0.1-SNAPSHOT",
  scalaVersion := "2.13.8"
)

lazy val server = (project in file("server"))
  .settings(
    commonSettings,
    name := "server",
    libraryDependencies ++= Seq(
      "org.http4s"      %% "http4s-ember-server" % Http4sV,
      "org.http4s"      %% "http4s-ember-client" % Http4sV,
      "org.http4s"      %% "http4s-circe"        % Http4sV,
      "org.http4s"      %% "http4s-dsl"          % Http4sV,
      "io.circe"        %% "circe-generic"       % CirceV,
      "org.scalameta"   %% "munit"               % MunitV           % Test,
      "org.typelevel"   %% "munit-cats-effect-3" % MunitCatsEffectV % Test,
      "ch.qos.logback"  %  "logback-classic"     % LogbackV         % Runtime,
      "org.scalameta"   %% "svm-subs"            % SubsV,
      "com.lihaoyi"     %% "scalatags"           % ScalaTagsV
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % KindProjectorV cross CrossVersion.full),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % BetterMonadicForV),
    testFrameworks += new TestFramework("munit.Framework"),
    // make unmanagedResources from the client available to serve
    Compile / unmanagedResourceDirectories ++= (client / Compile / unmanagedResourceDirectories).value,
    // make compiled assets available
    Compile / resources ++= (client / Compile / fullOptJS / webpack).value.map(_.data),
    // do a fastOptJS on reStart
    reStart := (reStart dependsOn (client / Compile / fastOptJS / webpack)).evaluated,
    // This settings makes reStart to rebuild if a scala.js file changes on the client
    watchSources ++= (client / watchSources).value,
  )

lazy val client = (project in file("client"))
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)
  .settings(
    commonSettings,
    name := "client",
    webpackConfigFile := Some(baseDirectory.value / "webpack.config.js"),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % CatsEffectV,
      "com.github.japgolly.scalajs-react" %%% "core" % ScalaJSReactV
    ),
    Compile / npmDependencies ++= Seq(
      "react" -> ReactV,
      "react-dom" -> ReactV,
      "webpack-merge" -> "5.8.0"
    )
  )