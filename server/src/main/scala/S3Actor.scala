package cosbench_ng


import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.transfer.{ TransferManager, TransferManagerBuilder }
import com.amazonaws.services.s3.{ AmazonS3Client, AmazonS3ClientBuilder }
import com.amazonaws.event.{ProgressListener, ProgressEvent, ProgressEventType}
import com.amazonaws.event.ProgressEventType._

import java.util.concurrent.Executors


import java.util.concurrent.Executors

import java.io.File


import akka.actor. { Actor, Props, ActorLogging , Status, ActorRef, ActorSystem, Terminated, Stash}

import scala.util. { Try, Failure, Success }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global



// this actor knows he is part of a stream and is replying explicitly
// now he is also intentionally delaying execution

object S3Actor {
  val props = Props[S3Actor] 
  
   private val s3Client = AmazonS3ClientBuilder
    .standard()
    .withRegion(Regions.US_EAST_1)
    .build()

  // building a custom thread pool for transfer manager to use.
  // the default has too few threads (10 i think)
  // see: https://github.com/aws/aws-sdk-java/issues/896

  private val s3TxfrMgrBuilder = TransferManagerBuilder.standard()
    .withMinimumUploadPartSize(1024 * 1024 * 1024) // no multi-part for files less than 1GB
    .withDisableParallelDownloads(true) // keeping things predictable
    .withS3Client(s3Client)
    .withExecutorFactory(new com.amazonaws.client.builder.ExecutorFactory { // anonymous class
      override def newExecutor() = Executors.newFixedThreadPool(MyConfig.maxThreads) // threads
    })

  val s3TxfrMgr = s3TxfrMgrBuilder.build()  
  
  // messages
  case class PutDone(finalReply: ActorRef, stat: Stats)
}


// create constant load on the underlying thread pool
// start with config.threadMaxLoad if config.reserveLoad is reached, 
// refill to config.threadMaxLoad. This way we keep constant preassure
class S3Actor extends Actor with ActorLogging with Stash {
  
  var accStatsList : List[Stats] = Nil
  var nxtPrefix = 0
  var pendingPuts = 0 
  
  override def postStop = {
    log.debug("I am now dead.")
  }

      
  def receive = {
    case x: Int  =>

      log.debug("Generating load")
      generateLoad(x) // we need to generate a load

    case x: S3Actor.PutDone => 
      accStatsList = x.stat::accStatsList
      pendingPuts -= 1
      
      if (pendingPuts <= MyConfig.reserveLoad ) {     
        log.debug("Unstashing any old message")
        unstashAll() // we can create more load, lets start work on any pending work                
        x.finalReply ! accStatsList.toList //and ask for more  
        accStatsList = Nil
      }

    case x: Status.Failure =>
      log.error("status.failure recieved: " + x) // if a limit is reached, just ignore

    case x: Terminated =>
      log.error("I received a Terminated: " + x)
      context.stop(self)

    case x: Any => log.error("unexpected message: " + x)
  }


  def generateLoad(startPrefix: Int) {

    val finalReply = sender()
    val mySelf = self

    val bucketName = "Vishnu_test"
    val filePath = "/Users/vardhan/play/scala/akka/remoting/testData"

    for { currIndx <- 0 to 99  }  { 
      // executing a max of 100 puts at a time
      // keeping it really simple. may exceed maxThreadLoad

      val objName = (startPrefix * 100 + currIndx).toString
      
      log.debug("Starting PUT: " + objName)


      Try {
        pendingPuts += 1
        S3Actor.s3TxfrMgr.upload(bucketName, objName, new File(filePath))
      }.map { // lets add a listener waiting for completion
        pl => pl.addProgressListener(new TrackRequestProgress(mySelf, finalReply, objName))
      } match { // print any exceptions
        case Failure(e) => log.error(e.toString)
        case Success(s) => {}
      }
    } // end for          
  }

  
  // keeps track of a request's progress, and sends a message back when done
  private class TrackRequestProgress(me: ActorRef, srcActor: ActorRef, objName: String)
      extends ProgressListener {
    var startTime: Float = 0
    override def progressChanged(pe: ProgressEvent) =
      pe.getEventType match {
        case CLIENT_REQUEST_STARTED_EVENT =>
          startTime = System.nanoTime / 1000000

        case CLIENT_REQUEST_SUCCESS_EVENT =>
          val endTime: Float = System.nanoTime / 1000000
          log.debug("PUT completed: " + objName)
          me ! S3Actor.PutDone(srcActor, Stats(endTime - startTime))
        case _ => {}
      }
  }
  
}
