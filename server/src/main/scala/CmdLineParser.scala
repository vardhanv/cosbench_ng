
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
        .valueName("<PUT/GET>")
        .required()
        .action((x, c) => c.copy(cmd = x))
        .validate({
          case "PUT" => { success }
          case "GET" => { success }
          case _ => { failure("Unknown command. Must be one of  PUT/GET") }
        })
        .text("command to execute. One of PUT/GET")

      opt[Int]('m', "maxOps")
        .action((x, c) => c.copy(maxOps = x))
        .required()
        .validate({
          case (x) =>
            if (x <= -1L) failure("maxOps >= 0 <= 1 billion")
            else success
        })
        .text("execute \"maxOps\" number of s3 operations.")

      opt[Int]('r', "opsRate")
        .action((x, c) => c.copy(opsRate = x))
        .required()
        .validate({
          case (x) =>
            if (x <= -1L || x >= 100000L) failure("opsRate <= 100000 >= 0")
            else success
        })
        .text("execute s3 operations at a rate of \"opsRate\"/sec. ")

      opt[String]('e', "ep-url")
         .valueName("<url>")
        .action((x, c) => c.copy(endpoint = x))
        .optional()
        .text("optional, endpoint. default = https://s3.amazonaws.com")

      opt[Unit]('f', "finish-all-ops")
        .action((_, c) => c.copy(runToCompletion = true))
        .optional
        .text("optional, changes how we terminate and forces completion of all s3Ops")

      opt[Map[String, Int]]('g', "range-read")
        .valueName("<start=v1,end=v2>")
        .action( (x,c) => { 
          c.copy(rangeReadStart = x("start"))
           .copy(rangeReadEnd   = x("end"))
        })
        .validate( x => {
          if(x.keySet.count(_ => true) != 2 
              || x.keySet.contains("start") == false 
              || x.keySet.contains("end")   == false 
              || x("end") < x("start"))
            failure("Invalid range-read values")
            else success
          /*
          case (k, v) =>
            if (k > 0 && v > 0 && k < v) { success }
            else failure("Invalid range read values %d,%d".format(k, v))*/
        })
        .optional
        .text("optional, range-read. Example (-g:start=200,end=400), all values in bytes")

      opt[Int]('k', "fakeS3")
        .valueName("<milliseconds>")
        .action((x, c) => c.copy(fakeS3Latency = x))
        .optional
        .text("optional, fake s3 with 'value' ms of fake latency")

      /*
       *  Commenting out, since it can cause confusion because my
       *  docker containers don't have access to the profile yet
       *
      opt[String]('p', "profile")
        .action((x, c) => {

            val (accessId,secretKey) = {
             // lets check if AWS configuration is correct
             val awsCredentials =
               if (x == "default")
                 DefaultAWSCredentialsProviderChain.getInstance()
               else
                 new ProfileCredentialsProvider(x)
              (awsCredentials.getCredentials.getAWSAccessKeyId, awsCredentials.getCredentials.getAWSSecretKey)
            }

            c.copy(aidSkey = (accessId,secretKey))
          })
        .optional()
        .text("optional, aws profile with aws credentials. create using \"aws --configure\"")
        *
        */

      opt[String]('i', "region")
        .action((x, c) => c.copy(region = x))
        .optional()
        .text("optional, s3 region. default = us-east-1")

      opt[Int]('s', "slaves")
        .action((x, c) => c.copy(minSlaves = x))
        .optional
        .text("optional, minimum number of slaves. default = 0. More slaves = more performance")

      opt[String]('t', "testtag")
        .action((x, c) => c.copy(testTag = x))
        .optional
        .text("optional, Tag your test with a string which shows up in your results file")

      opt[Int]('z', "objSize")
        .action((x, c) => c.copy(objSize = x))
        .optional
        .text("optional, object size in KB. default = 1")

      opt[Int]('u', "debug")
        .action((x, c) => c.copy(debug = x))
        .validate({
          case (x) =>
            if (x != 1 && x != 2) failure("debug value has to be 1 or 2")
            else success
        })          
        .optional
        .text("optional, turn on debugging (1: info, 2: debug)")
        
        
      help("help").text("prints help text")

      checkConfig(c =>
        if (c.rangeReadStart > c.rangeReadEnd) {
          failure("rangeReadEnd has to be greater than or equal to rangeReadStart")
        } else if (c.maxOps < c.opsRate) {
          failure("maxOps has to be greater than opsRate")
        } else if (c.rangeReadStart > -1  && c.cmd == "PUT") // TODO: Needs better handling
          failure("cannot specify PUT and do a range-read") 
        else success )
        
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