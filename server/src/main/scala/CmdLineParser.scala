
package cosbench_ng

// scopt for commandline
import scopt._
import org.slf4j.LoggerFactory

//AWS
import com.amazonaws.ClientConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{ AmazonS3Client, AmazonS3ClientBuilder }

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain

import scala.util.{Try, Success, Failure }


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

      opt[Int]('m', "maxOps")
        .action((x, c) => c.copy(maxOps = x) )
        .required()
        .validate({
          case (x) =>
            if (x <= -1L) failure("maxOps >= 0 <= 1 billion")
            else success
        })
        .text("execute \"maxOps\" s3Ops.")

      opt[Int]('o', "opsRate")
        .action((x, c) => c.copy(opsRate = x) )
        .required()
        .validate({
          case (x) =>
            if (x <= -1L || x >= 100000L) failure("opsRate <= 100000 >= 0")
            else success
        })
        .text("execute s3Ops at a rate of \"opsRate\"/sec. ")
                
                        
      opt[String]('e', "ep-url")
        .action((x, c) => c.copy(endpoint = x))
        .optional()
        .text("optional, endpoint. default = https://s3.amazonaws.com")        

      opt[Unit]('f', "runtocomplete")
        .action((_, c) => c.copy(runToCompletion = true))
        .optional
        .text("optional, changes how we terminate and forces completion of all s3Ops")

        
      opt[(Int,Int)]('g', "rangeRead")
         .action({ case ((k, v), c) => c.copy(rangeReadStart = k, rangeReadEnd = v) })
        .validate({
          case (k,v) => 
            if (k > 0 && v > 0 && k < v) { success }
            else failure("Invalid range read values %d,%d".format(k,v))
        })
        .optional
        .text("optional, read from a sepcific offset to a particular offset. example (-g:200=400)")

        
      opt[Int]('k', "fakeS3")
        .action((x, c) => c.copy(fakeS3Latency = x))
        .optional
        .text("optional, fake s3 with 'value' ms of fake latency")

        
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

      opt[String]('r', "region")
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
        

      help("help").text("prints help text")
      
      checkConfig(c =>
        if (c.rangeReadStart > c.rangeReadEnd) {
           failure("rangeReadEnd has to be greater than or equal to rangeReadStart")
        }
        else if(c.maxOps < c.opsRate) {
          failure("maxOps has to be greater than opsRate")
        }
        else Try { 
           val awsCredentials = 
             if(c.aidSkey._1 == "aid") // still the default value
                 DefaultAWSCredentialsProviderChain.getInstance().getCredentials
             else
                 new BasicAWSCredentials(c.aidSkey._1,c.aidSkey._2)
           
           val s3Client = AmazonS3ClientBuilder
              .standard()
              .withEndpointConfiguration(new EndpointConfiguration(c.endpoint, c.region))
              .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
              .withClientConfiguration(new ClientConfiguration().withMaxErrorRetry(0))
              .withPathStyleAccessEnabled(true)
              .build()
      
            s3Client.listBuckets() 
          } match {
            case Success(e) => success
            case Failure(e) => 
              log.error(e.toString()) 
              log.error("Using AID = " + c.aidSkey._1)
              log.error("Using secret key = " + c.aidSkey._2)
              failure("Problem with S3 configuration, unable to do a test list-bucket. ")
          }
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