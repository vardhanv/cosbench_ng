
package cosbench_ng

// scopt for commandline
import scopt._
import org.slf4j.LoggerFactory


object CmdLineParser {

  def parseCmdLine(a: Array[String]): Option[Config] = {

    val log = LoggerFactory.getLogger(this.getClass)      

    val cmdLineParser = new scopt.OptionParser[Config]("cosbench_ng") {
      head("cosbench_ng", "1.0")

      opt[String]('b', "bucket")
        .required()
        .action((x, c) => c.copy(bucketName = x))
        .text("target s3 bucket")


      opt[String]('c', "cmd")
        .required()
        .action((x, c) => c.copy(cmd = x))
        .validate( { 
          case "PUT" => { success }
          case "GET" => { success }
          case _  => { failure("Unknown command. Must be one of  PUT/GET") }
        })
        .text("command to execute. One of PUT/GET")

      opt[(Int, Int)]('o', "ops")
        .action({ case ((k, v), c) => c.copy(maxOps = k, opsRate = v) })
        .required()
        .validate({
          case (k, v) =>
            if (k <= -1L) failure("maxOps >= 0 <= 1 billion")
            else if (v <= -1L || v >= 1000L) failure("opsRate <= 100000 >= 10")
            else success
        })
        .keyValueName("maxOps", "opsRate")
        .text("execute \"maxOps\" s3Ops at a rate of \"opsRate\"/sec. example (-o:5000=200) ")
        

        opt[Int]('z', "objSize")
        .action((x, c) => c.copy(objSize = x))
        .optional
        .text("optional, object size in KB. default = 1")
        
      opt[(Int,Int)]('g', "rangeRead")
         .action({ case ((k, v), c) => c.copy(rangeReadStart = k, rangeReadEnd = v) })
        .validate({
          case (k,v) => 
            if (k > 0 && v > 0 && k < v) { success }
            else failure("Invalid range read values %d,%d".format(k,v))
        })
        .optional
        .text("optional, read from a sepcific offset to a particular offset. example (-g:200=400)")

                
      opt[String]('e', "ep-url")
        .action((x, c) => c.copy(endpoint = x))
        .optional()
        .text("optional, endpoint. default = https://s3.amazonaws.com")

      opt[String]('r', "region")
        .action((x, c) => c.copy(region = x))
        .optional()
        .text("optional, s3 region. default = us-east-1")
                
      opt[String]('p', "profile")
        .action((x, c) => c.copy(awsProfile = x))
        .optional()
        .text("optional, aws profile with aws credentials. default = default. create using \"aws --configure\"")


      opt[Unit]('f', "runtocomplete")
        .action((_, c) => c.copy(runToCompletion = true))
        .optional
        .text("optional, changes how we terminate and forces completion of all s3Ops")

      opt[Int]('s', "slaves")
        .action((x, c) => c.copy(minSlaves = x))
        .optional
        .text("optional, minimum number of slaves. default = 0. More slaves = more performance")

      opt[String]('t', "testtag")
        .action((x, c) => c.copy(testTag = x))
        .optional
        .text("optional, Tag your test with a string which shows up in your results file")
        

      help("help").text("prints help text")
      
      checkConfig(c =>
        if (c.rangeReadStart > c.rangeReadEnd) {
           failure("rangeReadEnd has to be greater than or equal to rangeReadStart")
        }
        else if(c.maxOps < c.opsRate) {
          failure("maxOps has to be greater than opsRate")
        }
        else
          success
      )


    }

    cmdLineParser.parse(a, Config()) match {
      case Some(config) =>
        log.debug("Parsed cmdline: " + config)
        Some(config)
      case None =>
        None
    }

  }

}