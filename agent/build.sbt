name := """cosbench_ng-slave"""
version := "0.9"


// Docker specific configuration
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerRepository := Some("vardhanv")
dockerUpdateLatest in Docker := true

fork in run := true
