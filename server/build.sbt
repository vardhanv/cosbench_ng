name := """cosbench_ng"""

version := "0.8"

// Docker specific configuration
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

fork in run := true
