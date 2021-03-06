package cosbench_ng


import akka.actor. { Actor, Props, ActorLogging , ActorRef, PoisonPill, Cancellable }

import akka.routing.{ Broadcast }

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.routing.{ FromConfig }
import akka.routing.ConsistentHashingRouter._

import MyProtoBufMsg._ 


object MyRouter { def props() = Props(classOf[MyRouter]) }

class MyRouter  extends Actor with ActorLogging {

  log.debug( this.getClass + " created")
  // clustered router
  val routerA = context.actorOf(FromConfig.props(SlaveWorker.props), "workerRouter")
  var statsAcc : Option[ActorRef] = None
  
  val countdownToDie = 1.seconds
  var cancelDie : Option[Cancellable] = None 
  
  var pendingAck : Option[ActorRef] = None
  
  override def postStop() =  {
    // router kills all its resources
    // kills itself
    // after it dies, asks flowcontrol to die
    // flowcontrol will tell reaper to shut everything down
    
    log.debug("MyRouter PostStop Called")
    context.actorSelection("/user/FlowControl") ! PoisonPill    
  }

  
  def receive = {
    case "start" =>
      log.info("router received: start")
      sender ! "ack"

    case aRef: ActorRef => //init case from FlowControlActor
      statsAcc = Some(aRef)
      
    case x: StatListMsg => 
      
      log.debug("received stat response from: " + sender())
      statsAcc.map { _ ! x} // send to stats
      pendingAck.map { _ ! "ack" }

    case x: ConfigMsg => // received from flowcontrolActor -> forward to slaves
      require ( MyConfig.cl.isDefined )
      log.info("router received: Config msg, broadcasting to slaves")
      routerA ! Broadcast(x)
      
      
    case "Slave is not configured" => //send config to slave
      require ( MyConfig.cl.isDefined )
      log.info("router received: slave not configured. Sending config to %s".format(sender().toString()))
      sender() ! (new ConfigMsg(MyConfig.cl.get))

      
    case "done" =>  // upstream done. time to die ?
      log.info("router received: done --------> UPSTREAM COMPLETE")       

      cancelDie = Some(context.system.scheduler.scheduleOnce(countdownToDie, self, "die"))
      routerA ! new Broadcast(SlaveWorker.StopS3Actor())  // ask workers to stop if they can

      if (MyConfig.cl.get.runToCompletion == true)
        log.info("Waiting for Slaves to finish...")
      else
        log.info("Giving workers time to cleanup.. will shutdown in " + countdownToDie + " seconds")

    case "Slave Not Done" => 
      cancelDie.get.cancel() // cancel the existing scheduled death
      cancelDie = Some(context.system.scheduler.scheduleOnce(countdownToDie, self, "done")) // try to die again soon

        
    case "die" =>
      log.info("router received: die")
      
      routerA ! Broadcast(PoisonPill)
      routerA ! PoisonPill
      statsAcc.map { _ ! PoisonPill } // BUG: Lots pending
      self ! PoisonPill
    
      
    case msg: MyCmd =>
      // forward to slaves and wait for a response 
      log.debug("router received: command(%d,%d). Routing to slaves.".format(msg.start,msg.end))
      routerA ! (ConsistentHashableEnvelope(msg, msg)) 
      
      sender ! "ack"
      pendingAck = Some(sender)
           
      
    case x: Any => 
      log.error("should not happen: Received: " + x.toString())
      require(false)
  }
}


