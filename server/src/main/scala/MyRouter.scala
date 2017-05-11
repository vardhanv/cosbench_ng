package cosbench_ng


import akka.actor. { Actor, Props, ActorLogging , Status, ActorRef, ActorSystem, Terminated, PoisonPill, Cancellable}
import akka.routing.{ ActorRefRoutee, SmallestMailboxRoutingLogic , Router, Broadcast }
import akka.stream._

import scala.util.Failure
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.stream.scaladsl.{ Source, SourceQueue, SourceQueueWithComplete }


import java.io.File

import akka.routing.{ FromConfig }
import akka.routing.ConsistentHashingRouter._

import scala.concurrent.Future


object MyRouter { def props() = Props(classOf[MyRouter]) }

class MyRouter  extends Actor with ActorLogging {

  log.debug( this.getClass + " created")
  // clustered router
  val routerA = context.actorOf(FromConfig.props(SlaveWorker.props), "workerRouter")
  var statsAcc : Option[ActorRef] = None
  
  val countdownToDie = 1.seconds
  var cancelDie : Option[Cancellable] = None 
  
  override def postStop() =  log.debug("MyRouter PostStop Called")

  
  def receive = {
    case "start" =>
      log.debug("router received: start")
      sender ! "ack"

    case aRef: ActorRef => //init case 
      statsAcc = Some(aRef)
      
    case x: StatList => 
      
      log.debug("received stat response from: " + sender())
      statsAcc.map { _ ! x} // send to stats
      
    case "Slave is not configured" => //send config to slave
      require ( MyConfig.cl.isDefined )
      sender() ! (new ConfigMsg(MyConfig.cl.get))
      
    case "done" =>  // upstream done. time to die ?
      log.debug("router received: done")       

      cancelDie = Some(context.system.scheduler.scheduleOnce(countdownToDie, self, "die"))
      routerA ! new Broadcast(SlaveWorker.StopS3Actor())  // ask workers to stop if they can

      if (MyConfig.cl.get.runToCompletion == true)
        log.warning("Waiting for Slaves to finish...")
      else
        log.warning("Giving workers time to cleanup.. will shutdown in " + countdownToDie + " seconds")

    case "Slave Not Done" => 
      cancelDie.get.cancel() // cancel the existing scheduled death
      cancelDie = Some(context.system.scheduler.scheduleOnce(countdownToDie, self, "done")) // try to die again soon

        
    case "die" =>
      log.info("router received: die")
      
      routerA ! new Broadcast(PoisonPill)
      routerA ! PoisonPill
      context.watch(routerA)
      
    case msg: MyCmd =>
      // forward to slaves and wait for a response 
      log.debug("router received: command(%d,%d)".format(msg.start,msg.end))
      routerA ! (ConsistentHashableEnvelope(msg, msg)) 
      sender  ! "ack"
           

    case x: Terminated => 
      log.debug("router terminated, shutting down")
      statsAcc.map { _ ! PoisonPill } // BUG: Lots pending    
      
      
    case x: Any => 
      log.error("should not happen: Received: " + x.toString())
      require(false)
  }
}



// reaper to terminate my application
// see http://letitcrash.com/post/30165507578/shutdown-patterns-in-akka-2
object Reaper {  val props = Props[Reaper]  }

class Reaper extends Actor with ActorLogging {  

  def receive = { case x: Any  => require(false)  }
  override def postStop = { 
    log.debug ("Reaper shutting down...")
    context.system.terminate()
  }
}

