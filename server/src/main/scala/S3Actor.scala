package cosbench_ng



import org.slf4j.LoggerFactory

import com.amazonaws.ClientConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{ AmazonS3Client, AmazonS3ClientBuilder }
import com.amazonaws.services.s3.model.{ GetObjectRequest, ObjectMetadata }

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.{ Future, blocking, Await }
import scala.util.{ Try, Failure, Success }


import java.io.File
import java.io.IOException

import akka.actor. { Actor, Props, ActorLogging, ActorRef}

import org.apache.commons.lang3.StringUtils
import java.io.ByteArrayInputStream
import java.io.InputStream



case class S3OpsFlush()

object S3Ops {
  
   val s3Buffer : Array[Byte] = Array.ofDim(1024)      
   lazy val executors  = Executors.newFixedThreadPool(MyConfig.maxThreads)
   lazy val blockingEC = ExecutionContext.fromExecutor(executors)  
   val log = LoggerFactory.getLogger(this.getClass)
           
   var config: Option[Config] = None

    def init(c: Config): Boolean = {
      if (config.isEmpty) { config = Some(c) } else require(config.get == c)

      Try {
        if (config.get.fakeS3Latency > 0)
          Thread.sleep(config.get.fakeS3Latency)
        else
          s3Client.listBuckets() 
       } match { // check if S3 is configured
        case Success(v) => true
        case Failure(e) => {
          log.error("""
                                | Something wrong with your S3 configuration. A test list bucket failed. 
                                | Make sure you have either the ~/.aws profile accessible (docker containers 
                                | do not have access to your aws profile). Or have the environment variables 
                                | AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY set""".stripMargin); 
          log.error(e.toString())        
          false
        }
      }
    }
   
   // S3 client with retries disabled
   private lazy val s3Client = AmazonS3ClientBuilder
    .standard()
    .withEndpointConfiguration(new EndpointConfiguration(config.get.endpoint, config.get.region))
    .withCredentials(awsCredentials)
    .withClientConfiguration(new ClientConfiguration().withMaxErrorRetry(0))
    .withPathStyleAccessEnabled(true)
    .build()
    
   lazy val awsCredentials = 
     if (config.get.awsProfile == "default")
       DefaultAWSCredentialsProviderChain.getInstance()
     else
       new ProfileCredentialsProvider(config.get.awsProfile)

    
  def put(bucketName: String, objName:String)  =
    Future {
      blocking {
        Try {
          val totalTime = {
            val startTime = System.nanoTime / 1000000
            
            //val byteArray : ByteArrayInputStream = new ByteArrayInputStream(s3Buffer)
            val omd = new ObjectMetadata()
            omd.setContentLength(config.get.objSize*1024)
              
            if (config.get.fakeS3Latency > 0)
              Thread.sleep(config.get.fakeS3Latency) //fake s3
            else
              s3Client.putObject(bucketName, objName, byteStream(config.get.objSize*1024) , omd)
            
            
            (System.nanoTime() / 1000000) - startTime
          }
          totalTime
        } match {
          case Success(v) => GoodStat(v, v)
          case Failure(e) => 
            log.error(e.toString()) 
            BadStat()
        }
      }
    }(S3Ops.blockingEC)

    
  def get(bucketName: String, objName:String)  =
    Future {
      blocking {
        Try {
          val buffer : Array[Byte] = Array.ofDim(9126)

          val startTime = System.nanoTime / 1000000
          val getObjReq = new GetObjectRequest(bucketName, objName)
          
          // if range read is used
          if (config.get.rangeReadStart > -1)
            getObjReq.setRange(config.get.rangeReadStart, config.get.rangeReadEnd)

          val (receivedTime, completeTime) =
            if (config.get.fakeS3Latency > 0) { //fake s3
              Thread.sleep(config.get.fakeS3Latency) 
              val t = (System.nanoTime() / 1000000)
              (t, t)
            } else { // real s3

              val s3Obj = s3Client.getObject(getObjReq)
              val rt = (System.nanoTime() / 1000000)
              val stream = s3Obj.getObjectContent

              while (s3Obj.getObjectContent.read(buffer) != -1) {}
              val ct = (System.nanoTime() / 1000000)

              stream.close()
              s3Obj.close()
              (rt, ct)
            }

          (receivedTime - startTime, completeTime - startTime) //(rspTime, totalTime)
        } match {
          case Success(v) => GoodStat(v._1, v._2)
          case Failure(e) => 
            S3Ops.log.error("Bucket: " + bucketName + ", object: " + objName + ", " + e.toString) 
            BadStat()
        }
      }
    }(S3Ops.blockingEC)
    
  
  def shutdown() = {
      log.debug("S3Ops has shutdown")
      S3Ops.executors.shutdown()
    }

  // this is our source of infinite bytes
  def byteStream(length: Long): InputStream = new InputStream {

    require(length >= 0)    
    var currPos : Long = 0
    def read(): Int = if (currPos < length) {
      val x : Long = currPos % S3Ops.s3Buffer.size
      currPos += 1
      S3Ops.s3Buffer(x.toInt).toInt
    } else -1
  }
  
}


