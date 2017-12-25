package cosbench_ng



import org.slf4j.LoggerFactory
import com.amazonaws.ClientConfiguration
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.amazonaws.services.s3.model.{ GetObjectRequest, ObjectMetadata }
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials}
import java.util.concurrent.Executors
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

  def get(c: Config) = {
    if (s3Client.isEmpty)
      s3Client = Some(createS3Client(c))

    s3Client.get
  }

  def test: Boolean =
    Try {
      s3Client.get.listBuckets()
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

  private def createS3Client(c: Config): AmazonS3 = {
    
    require (c.aidSkey._1 != "aid")
    val awsCredentials = new BasicAWSCredentials(c.aidSkey._1, c.aidSkey._2)

    val clientConfig = new ClientConfiguration().withMaxErrorRetry(0)
    
    // No SSL-Verify
    clientConfig.getApacheHttpClientConfig()
      .setSslSocketFactory(sslNoVerifyConnFactory())

    // S3 client with retries disabled
    val s3Client = AmazonS3ClientBuilder
      .standard()
      .withEndpointConfiguration(new EndpointConfiguration(c.endpoint, c.region))
      .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
      .withClientConfiguration(clientConfig)
      .withPathStyleAccessEnabled(true)
      .build()

    Try {
      s3Client.listBuckets()
    } match {
      case Success(e) => true
      case Failure(e) =>
        log.error("Problem with S3 configuration, unable to do a test list-bucket. ")
        log.error("Using AID        = " + awsCredentials.getAWSAccessKeyId())
        log.error("Using secret key = " + awsCredentials.getAWSSecretKey())
        log.error("Using endpoint   = " + c.endpoint)
        log.error(e.toString)
        System.exit(1)

        false
    }

    s3Client
  }

}

case class S3OpsFlush()

object S3Ops {
  
   val s3Buffer : Array[Byte] = Array.ofDim(1024)      
   lazy val executors  = Executors.newFixedThreadPool(MyConfig.maxThreads)
   lazy val blockingEC = ExecutionContext.fromExecutor(executors)  
   val log = LoggerFactory.getLogger(this.getClass)
           
   var config: Option[Config] = None

  def init(c: Config): Boolean = {
    if (config.isEmpty) { config = Some(c) } else require(config.get == c)

    if (config.get.fakeS3Latency <= 0)
      GetS3Client.get(c)
  
    true
  }

              
   // S3 client with retries disabled
   private lazy val s3Client = GetS3Client.get(config.get)

    
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
            log.warn("Bucket: " + bucketName + ", object: " + objName + ", " + e.toString()) 
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
            log.warn("Bucket: " + bucketName + ", object: " + objName + ", " + e.toString) 
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


