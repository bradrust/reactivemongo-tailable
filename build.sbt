lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := """reactivemongo-playground""",
    organization := "com.example",
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.13.1",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test,
      "org.reactivemongo" %% "reactivemongo" % "1.0.0",
      "org.reactivemongo" %% "play2-reactivemongo" % "1.0.0-play28",
      "org.reactivemongo" %% "reactivemongo-akkastream" % "1.0.0",
    ),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-Xfatal-warnings"
    )
  )
