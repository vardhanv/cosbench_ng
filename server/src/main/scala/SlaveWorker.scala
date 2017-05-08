package cosbench_ng


import java.io.File
import akka.actor. { Actor, Props, ActorLogging , Status, ActorRef , Terminated}
import scala.collection.mutable.ListBuffer

import scala.collection.mutable.ListBuffer


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
  var pendingOps         = 0    
  var maxPendingOps      = 1
  var shutdownStatus     = false
  
  // track which command are we executing
  case class CurrOp(c: MyCmd, finalReply: ActorRef, i: Long) // current op and index to where we are
  var pendingOpList  : List[CurrOp] =  Nil
  
  
  case class DebugStats(totalPendingOps : Int, countMsgsReceived: Int)
   
  var debugStats = DebugStats(0,0)
  
  
  override def postStop = {
    val f = finalStat()
    log.debug("FinalStat(%3d,%3d,%3d)".format(f.opsNStarted, f.opsStartedNCompleted,f.opsCompletedStatsNSent)) 
    log.debug("Worker: is dead.")
    require(shutdownStatus == true)
  }

 def finalStat() : FinalStat = 
      FinalStat(pendingOps,  accStatsList.length, pendingOpList.foldRight(0)( (s,x) => { 
        x + ((s.c.end+1-s.c.start) - (s.i - s.c.start)).toInt
      }))

      
  def calcMaxPendingOps() =
    if (avgLatency > 0) {
      if (1000 / avgLatency > 1)
        (1000 / avgLatency).toInt * MyConfig.maxThreads * 3
      else
        MyConfig.maxThreads/5  // this needs bandwidth calculation
    } else MyConfig.maxThreads/5 
 
    
  def receive = {
    case x: MyCmd  =>
      
        require(shutdownStatus == false)
        debugStats = DebugStats( debugStats.totalPendingOps + pendingOps, debugStats.countMsgsReceived + 1)
              
        pendingOpList = pendingOpList :+ CurrOp(x, sender, x.start)
        
        if(gConfig.isEmpty)
          sender() ! "Slave is not configured" // ask for config
        else 
          pendingOpList = generateLoad(pendingOpList)  // generate a load
                
        log.warning("currLoad: x=(%6d,%6d), pendingOps = %4d, maxPendingOps = %4d, avgLatency = %4d".format(
         x.start,x.end,pendingOps,maxPendingOps,avgLatency.toLong))
                                  
    case x: ConfigMsg => 
      gConfig = Some(x.config)
      S3Ops.init(gConfig.get)
      log.debug("Slave is configured")
      pendingOpList = generateLoad(pendingOpList)  // generate a load
      
         
    case S3OpsDoneMsg(h, stat) => 
      accStatsList = stat :: accStatsList
      pendingOps -= 1

      // update stats to update maxPendingPuts
      stat match { 
        case y: GoodStat =>
          avgLatency = (avgLatency * totalOps + y.rspComplete) / (totalOps + 1)
          totalOps += 1
        case _: Any => {}  
      }

      //adjust maxPendingOps
      val oldMaxPendingOps = maxPendingOps
      maxPendingOps = calcMaxPendingOps()
      if (oldMaxPendingOps != maxPendingOps)
        log.debug("adjusting maxPendingOps from " + oldMaxPendingOps + " to " + maxPendingOps)

      pendingOpList = generateLoad(pendingOpList)

      
      // send stats if we accumulated enough
      if (accStatsList.length > 100) {      
        h.finalReply ! StatList(accStatsList.toList)                
        accStatsList = Nil
      }
      

    case x: Status.Failure =>
      log.error("status.failure recieved: " + x) // if a limit is reached, just ignore

    case x: Terminated =>
      log.error("I received a Terminated: " + x)      
      shutdown()      
      context.stop(self)

    case x: SlaveWorker.StopS3Actor =>
      if (gConfig.isDefined && gConfig.get.runToCompletion && pendingOps > 0 ) {
        sender ! "Slave Not Done"
        
        val outstandingOps = 
          if (pendingOpList.length > 0 )
            (pendingOpList.length* (pendingOpList.head.c.end - pendingOpList.head.c.start + 1)) + pendingOps
          else
            pendingOps
                    
        log.warning("Slave has " + outstandingOps + " pending operations")
      }
      else { 
        log.debug("shutting down woker")
        shutdown()
        context.stop(self)
      }
    
      
    case x: Any => log.error("unexpected message: " + x)
  }

  
  def generateLoad(pl: List[CurrOp]): List[CurrOp] = pl match {
    case x :: remainingList => {
      val nIndx = generateLoadOp(x)
      if (nIndx > x.c.end) generateLoad(remainingList)
      else CurrOp(x.c, x.finalReply, nIndx) :: remainingList
    }
    case Nil => List()
  }

  def generateLoadOp(c: CurrOp): Long = { // recursive for
    val bucketName = gConfig.get.bucketName

    if (pendingOps < maxPendingOps && c.i <= c.c.end) {
      val objName = c.i.toString

      pendingOps += 1

      gConfig.get.cmd match {
        case "PUT" => S3Ops.put(bucketName, objName, self, OpsComplete(c.finalReply, objName))
        case "GET" => S3Ops.get(bucketName, objName, self, OpsComplete(c.finalReply, objName))
        case _: Any => log.error("Unknown command"); require(false)
      }

      generateLoadOp(CurrOp(c.c, c.finalReply ,c.i + 1))
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
  }

  
  
  
}