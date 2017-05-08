
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
        
      opt[Int]('t', "rangeReadStart")
        .action((x, c) => c.copy(rangeReadStart = x))
        .validate({
          case a if a > 0 => { success }
        })
        .optional
        .text("optional, range read start offset. if not specified the full object is read")

      opt[Int]('d', "rangeReadEnd")
        .action((x, c) => c.copy(rangeReadEnd = x))
        .optional
        .validate({
          case a if a > 0 => { success }
        })        
        .text("optional, range read end offset")
                
      opt[String]('e', "ep-url")
        .action((x, c) => c.copy(endpoint = x))
        .optional()
        .text("optional, endpoint. default = https://s3.amazonaws.com")

      opt[String]('r', "region")
        .action((x, c) => c.copy(region = x))
        .optional()
        .text("optional, s3 region. default = us-east-1")
                
      opt[String]('f', "profile")
        .action((x, c) => c.copy(awsProfile = x))
        .optional()
        .text("optional, aws profile with aws credentials. default = default. create using \"aws --configure\"")


      opt[Unit]('r', "runtocomplete")
        .action((_, c) => c.copy(runToCompletion = true))
        .optional
        .text("optional, changes how we terminate and forces completion of all s3Ops")

      opt[Int]('s', "slaves")
        .action((x, c) => c.copy(minSlaves = x))
        .optional
        .text("optional, minimum number of slaves. default = 0. More slaves = more performance")
        

      help("help").text("prints help text")
      
      checkConfig(c =>
        if (c.rangeReadStart < c.rangeReadEnd) {
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