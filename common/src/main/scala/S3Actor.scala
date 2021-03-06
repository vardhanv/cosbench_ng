package cosbench_ng



import org.slf4j.LoggerFactory
import com.amazonaws.ClientConfiguration
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.amazonaws.services.s3.model.{ GetObjectRequest, ObjectMetadata }
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials}

import java.util.concurrent.Executors
import java.io.BufferedReader;
import java.io.InputStreamReader;

import scala.concurrent.ExecutionContext
import scala.util.{ Try, Failure, Success }
import scala.concurrent.{Future, blocking}
import java.io.InputStream
import org.apache.http.conn.ssl.{SSLConnectionSocketFactory }
import org.apache.http.ssl.{SSLContextBuilder, TrustStrategy}
import javax.net.ssl.{HostnameVerifier, SSLSession}
import java.security.cert.X509Certificate


object GetS3Client {
  val log = LoggerFactory.getLogger(this.getClass)

  def init(c:Config) = get(c)
  
  def get(c: Config) = {
    if (s3Client.isEmpty)
      s3Client = createS3Client(c)

    s3Client
  }

  def test(bkt: String) : Boolean =
    Try {
      s3Client.get.listObjects(bkt)
    } match {
      case Success(e) => true
      case Failure(e) =>
        log.error(e.toString)
        false
    }

  private var s3Client: Option[AmazonS3] = None

  private def sslNoVerifyConnFactory(): SSLConnectionSocketFactory = {
    // No ssl verification

    // from: http://literatejava.com/networks/ignore-ssl-certificate-errors-apache-httpclient-4-4/
    // setup a trust strategy that allows all certificates
    val sslContext = SSLContextBuilder.create()
      .useProtocol("SSL")
      .loadTrustMaterial(null, new TrustStrategy() {
        def isTrusted(arg0: Array[X509Certificate], arg1: String) = true
      })
      .build()

    val sslConFactory = new SSLConnectionSocketFactory(
      sslContext,
      new HostnameVerifier { def verify(hostname: String, session: SSLSession) = true })

    return sslConFactory
  }

  private def createS3Client(c: Config): Option[AmazonS3] = {

    if (c.fakeS3Latency > -1) None
    else {
      require(c.aidSkey._1 != "aid")
      val awsCredentials = new BasicAWSCredentials(c.aidSkey._1, c.aidSkey._2)

      
      // retry 10 times if run to completion is set
      val clientConfig =
        if (c.runToCompletion)
          new ClientConfiguration().withMaxErrorRetry(10)
        else
          new ClientConfiguration().withMaxErrorRetry(0)
        

      // No SSL-Verify
      clientConfig.getApacheHttpClientConfig()
        .setSslSocketFactory(sslNoVerifyConnFactory())

      // S3 client with retries based on runToCompletion
      val s3Client = AmazonS3ClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(c.endpoint, c.region))
        .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
        .withClientConfiguration(clientConfig)
        .withPathStyleAccessEnabled(true)
        .build()

      Try {
        s3Client.listObjects(c.bucketName)
      } match {
        case Success(e) => true
        case Failure(e) =>
          log.error("Problem with S3 configuration, unable to do a test list objects on bucket: " + c.bucketName)
          log.error("Using AID        = " + awsCredentials.getAWSAccessKeyId())
          log.error("Using secret key = " + awsCredentials.getAWSSecretKey().charAt(1) + "***")
          log.error("Using endpoint   = " + c.endpoint)
          log.error(e.toString)
          System.exit(1)

          false
      }

      Some(s3Client)
    }
  }

}

case class S3OpsFlush()

object S3Ops {
  
   lazy val executors  = Executors.newFixedThreadPool(MyConfig.maxThreads)
   lazy val blockingEC = ExecutionContext.fromExecutor(executors)  
   val log = LoggerFactory.getLogger(this.getClass)
           
   var config: Option[Config] = None

  def init(c: Config): Boolean = {
    if (config.isEmpty) { config = Some(c) } else require(config.get == c)

    if (config.get.fakeS3Latency < 0)
      GetS3Client.init(c) // initialize S3 if using real S3
  
    true
  }

              
   // S3 client with retries disabled
  private lazy val s3Client = GetS3Client.get(config.get)

  def put(bucketName: String, objName: String) =
    Try {
      Future {
        blocking {
          Try {
            val totalTime = {
              val startTime = System.nanoTime / 1000000

              val omd = new ObjectMetadata()
              omd.setContentLength(config.get.objSize * 1024)

              if (config.get.fakeS3Latency > -1)
                Thread.sleep(config.get.fakeS3Latency) //fake s3
              else
                s3Client.get.putObject(bucketName, objName, byteStream(config.get.objSize * 1024), omd)

              (System.nanoTime() / 1000000) - startTime
            }
            GoodStat(totalTime, totalTime)
          } match {
            case Success(v) => v
            case Failure(e) => 
               log.warn("PUT Failed - Bucket: " + bucketName + ", object: " + objName + ", " + e)
               BadStat()
          }
        }
      }(S3Ops.blockingEC)
    } match {
      case Success(v) => v
      case Failure(e) =>
         log.warn("PUT Execution Failed - Bucket: " + bucketName + ", object: " + objName + ", " + e)
         Future.successful(BadStat())
    }

  def get(bucketName: String, objName: String) =
    Try {
      Future {
        blocking {
          Try {
            var buffer: Array[Byte] = Array.ofDim(9126)

            val startTime = System.nanoTime / 1000000
            val getObjReq = new GetObjectRequest(bucketName, objName)
            var totalBytesRead =  0

            //if range read is used
            val expectedBytes =
              if (config.get.rangeReadStart > -1) {
                getObjReq.setRange(config.get.rangeReadStart, config.get.rangeReadEnd)
                config.get.rangeReadEnd - config.get.rangeReadStart + 1
              } else
                1024 * config.get.objSize

              
            val rtnVal =
              if (config.get.fakeS3Latency > -1) { //fake s3
                Thread.sleep(config.get.fakeS3Latency)
                val t = (System.nanoTime() / 1000000)
                GoodStat(t - startTime, t - startTime)
              } else { // real s3

                val s3Obj = s3Client.get.getObject(getObjReq)
                val stream = s3Obj.getObjectContent

                val firstByte = stream.read()
                var bytesRead = if (firstByte >=0 ) 1 else 0 // stream.read returns the byte, not the number of bytes read.                

                // time to first byte                
                val rt = (System.nanoTime() / 1000000)

                while(bytesRead >= 0) {          
                  totalBytesRead = bytesRead + totalBytesRead                                                      
                  bytesRead = stream.read(buffer)                  
                }                                
                                                   
                val ct = (System.nanoTime() / 1000000)

                stream.close()
                s3Obj.close()

                if (totalBytesRead != expectedBytes) {
                  log.error("unexpected object size read.  Got: %d bytes, Expected %d bytes".format(totalBytesRead, expectedBytes))
                  BadStat() // return bad stat
                } else
                  GoodStat(rt - startTime, ct - startTime) //(rspTime, totalTime)
              }
            
            rtnVal
          } match {
            case Success(v) => v
            case Failure(e) => 
               log.warn("GET Failed - Bucket: " + bucketName + ", object: " + objName + ", " + e)
               BadStat()
          }
        }
      }(S3Ops.blockingEC)
    } match {
      case Success(v) => v
      case Failure(e) =>
         log.warn("GET Execution Failed - Bucket: " + bucketName + ", object: " + objName + ", " + e)
         Future.successful(BadStat())
    }
    
    
  
  def shutdown() = {
      log.debug("S3Ops has shutdown")
      S3Ops.executors.shutdown()
    }

  // this is our source of infinite bytes
  val s3Buffer : Array[Byte] = (for (i <- 0 to 1023) yield 'b'.toByte).toArray    

  def byteStream(length: Long): InputStream = new InputStream {

    require(length >= 0)    
    var currPos : Long = 0
    def read(): Int = if (currPos < length) {
      currPos += 1      
      S3Ops.s3Buffer((currPos % S3Ops.s3Buffer.size).toInt).toInt
    } else -1
  }
  
}


