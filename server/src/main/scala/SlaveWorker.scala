package cosbench_ng


import akka.actor. { Actor, Props, ActorLogging , Status, ActorRef , Terminated, Cancellable, PoisonPill}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Failure, Success }




object SlaveWorker {
  val props = Props[SlaveWorker] 

  // messages
  case class PutDone(finalReply: ActorRef, stat: GoodStat)    
  case class StopS3Actor()
}

// create constant load on the underlying thread pool
// start with config.threadMaxLoad if config.reserveLoad is reached, 
// refill to config.threadMaxLoad. This way we keep constant preassure
class SlaveWorker extends Actor with ActorLogging {
  
  log.info("Worker: created...")
  
  var accStatsList : List[Stats] = Nil
  
  //Global Config
  var gConfig: Option[Config] = None 
  
  // used to adjust pendingOps dynamically.
  var avgLatency: Double = 0
  var totalOps :  Long   = 0
  var maxPendingOps      = 1
  var shutdownStatus     = false
  
  // track which command we are executing
  case class CurrOp(c: MyCmd, i: Long) // current op and index to where we are
  var opsWaitingToStart  : List[CurrOp] =  Nil
  var opsWaitingToFinish : List[Future[Stats]] =  Nil  // Futures with the completed status

  // check and flush completed messages  
  val countdownToFlushStats = 1.seconds
  var cancelFlushStats : Option[Cancellable] = 
    Some(context.system.scheduler.schedule(countdownToFlushStats,countdownToFlushStats, self, S3OpsFlush()))

  // to send stats responses to
  var mostRecentRouterAddress : Option[ActorRef] = None 
  
  case class DebugStats(totalPendingOps : Int, countMsgsReceived: Int)
   
  var debugStats = DebugStats(0,0)
  
  
  override def postStop = {
    val f = finalStat()
    log.debug("FinalStat(%3d,%3d,%3d)".format(f.opsNStarted, f.opsStartedNCompleted,f.opsCompletedStatsNSent)) 
    log.debug("Worker: is dead.")
    if(shutdownStatus != true) {
      log.debug("Worker: unnatural death - did we get disconnected?")
      shutdown()
    }
  }

 def finalStat() : FinalStat = 
      FinalStat(opsWaitingToFinish.length,  accStatsList.length, opsWaitingToStart.foldRight(0)( (s,x) => { 
        x + ((s.c.end+1-s.c.start) - (s.i - s.c.start)).toInt
      }))

      
  def calcMaxPendingOps() =
    if (avgLatency > 0) {
      if (1000 / avgLatency > 1)
        (1000 / avgLatency).toInt * MyConfig.maxThreads * 3
      else
        MyConfig.maxThreads/5  // this needs bandwidth calculation
    } else MyConfig.maxThreads/5 
 
    
  def receive =  {
    case x: MyCmd  =>
      
        require(shutdownStatus == false)
        debugStats = DebugStats( debugStats.totalPendingOps + opsWaitingToFinish.length, debugStats.countMsgsReceived + 1)
        
        mostRecentRouterAddress = Some(sender())
              
        opsWaitingToStart = opsWaitingToStart :+ CurrOp(x, x.start)
        
        if(gConfig.isEmpty) {
          sender() ! "Slave is not configured" // ask for config
          log.info("Slave requesting configuration from %s".format(sender().toString()))
        }
        else
          opsWaitingToStart = generateLoad(opsWaitingToStart)  // generate a load

                
        log.info("currLoad: x=(%6d,%6d), opsWaitingToFinish = %4d, maxPendingOps = %4d, avgLatency = %4d".format(
         x.start,x.end,opsWaitingToFinish.length,maxPendingOps,avgLatency.toLong))
                                  
    case x: ConfigMsg => 
      
      log.info("Slave received config message")
      gConfig = 
        if (S3Ops.init(x.config))
          Some(x.config)
        else {          
          context.system.actorSelection("/user/Reaper") ! PoisonPill
          shutdown()
          None
        }
      
      if(gConfig.isDefined) {
         log.info("Slave is now configured")
         opsWaitingToStart = generateLoad(opsWaitingToStart)  // generate a load
      }
            
         
    case S3OpsFlush() => 
            
      val completedOps   = opsWaitingToFinish.filter {_.isCompleted }
      opsWaitingToFinish = opsWaitingToFinish.filter {_.isCompleted == false }

      // update stats to update maxPendingPuts
      
      val statList = completedOps.map( f => f.value.get match {
        case Success(y: Stats) => y
        case Failure(e) => log.error("received unexpexted bad stat"); require(false); BadStat()
      })
                  
      statList.map { 
        case y: GoodStat =>
          avgLatency = (avgLatency * totalOps + y.rspComplete) / (totalOps + 1)
          totalOps += 1
        case _: Any => {}  
      }

      // append newly completed stats to the existing list
      accStatsList = statList ::: accStatsList
      
      //log.debug("S3OpsFlush: current completed stats: %d, stats waiting to be flushed: %d".format(statList.length,accStatsList.length))
      
      //adjust maxPendingOps
      val oldMaxPendingOps = maxPendingOps
      maxPendingOps = calcMaxPendingOps()

      if (oldMaxPendingOps != maxPendingOps)
        log.debug("adjusting maxPendingOps from " + oldMaxPendingOps + " to " + maxPendingOps)
        
      // generate load
      opsWaitingToStart = generateLoad(opsWaitingToStart)

      
      // TODO change length to suit large file and small file workloads
      if (accStatsList.length > 50) {     
        log.debug("sending %d stats to router".format(accStatsList.length))
        mostRecentRouterAddress.map { x => x ! StatList(accStatsList.toList) }                
        accStatsList = Nil
      }
      

    case x: Status.Failure =>
      log.error("status.failure recieved: " + x) // if a limit is reached, just ignore

    case x: Terminated =>
      log.error("I received a Terminated: " + x)      
      shutdown()      


    case x: SlaveWorker.StopS3Actor =>
      log.debug("SlaveWorker.StopS3Actor Recived")
      
      if (gConfig.isDefined && gConfig.get.runToCompletion && opsWaitingToFinish.length > 0 ) {
        sender ! "Slave Not Done"
        
        val outstandingOps = 
          if (opsWaitingToStart.length > 0 )
            (opsWaitingToStart.length* (opsWaitingToStart.head.c.end - opsWaitingToStart.head.c.start + 1)) + opsWaitingToFinish.length
          else
            opsWaitingToFinish.length
                    
        log.warning("Slave has " + outstandingOps + " pending operations")
      } 
      else shutdown()
      
    
      
    case x: Any => log.error("unexpected message: " + x)
  }

  
  def generateLoad(pl: List[CurrOp]): List[CurrOp] = pl match {
    case x :: remainingList => {
      val nIndx = generateLoadOp(x)
      if (nIndx > x.c.end) generateLoad(remainingList)
      else CurrOp(x.c, nIndx) :: remainingList
    }
    case Nil => List()
  }

  def generateLoadOp(c: CurrOp): Long = { // recursive for
    val bucketName = gConfig.get.bucketName

    if (opsWaitingToFinish.length < maxPendingOps && c.i <= c.c.end) {
      val objName = c.i.toString
      
      opsWaitingToFinish = { gConfig.get.cmd match {
        case "PUT" => S3Ops.put(bucketName, objName)
        case "GET" => S3Ops.get(bucketName, objName)
      }} :: opsWaitingToFinish
            
      generateLoadOp(CurrOp(c.c ,c.i + 1))
    } else
      c.i
  }
  
  def shutdown () = {
    
    shutdownStatus = true
    
    sender() ! StatList(accStatsList.toList)
    accStatsList = Nil      
    
    val fStat = finalStat()

    log.warning("Slave shutting down")    
    log.warning("Slave: s3Ops queued but not started            =  " + fStat.opsNStarted)    
    log.warning("Slave: s3Ops started but not completed         =  " + fStat.opsStartedNCompleted)
    log.warning("Slave: s3Ops completed but stats dropped       =  " + fStat.opsCompletedStatsNSent)
        
    log.debug("Slave: totalMsgsReceived,avgPendingPuts: " +
        debugStats.countMsgsReceived + ", " +
        (if(debugStats.countMsgsReceived ==0) 0 else debugStats.totalPendingOps/debugStats.countMsgsReceived))
    
    sender() ! StatList( List(fStat) )

    context.stop(self)
  }   
}


// reaper to terminate my application
// see http://letitcrash.com/post/30165507578/shutdown-patterns-in-akka-2
object Reaper {  val props = Props[Reaper]  }

class Reaper extends Actor with ActorLogging {  

  def receive = { case x: Any  => require(false)  }
  override def postStop = { 
    log.debug ("Reaper shutting down...")

    S3Ops.shutdown()
    context.system.terminate()
  }
}