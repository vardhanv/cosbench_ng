package cosbench_ng


import akka.actor. { Actor, Props, ActorLogging , Status, ActorRef, ActorSystem, Terminated}
import akka.routing.{ ActorRefRoutee, SmallestMailboxRoutingLogic , Router }
import akka.stream._

import scala.util.Failure
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


import java.io.File


// very simple router for now, just forwarding, does not handle routee death
// see http://doc.akka.io/docs/akka/current/scala/routing.html

object MyRouter {
  val props = Props[MyRouter]
}

class MyRouter extends Actor {

    
  val routees = Vector( 
      ActorRefRoutee(context.actorOf(S3Actor.props)))
  
  val router =  Router(SmallestMailboxRoutingLogic(), routees)
  
  // right now we just forward messages
  def receive = { case msg: Any => router.route(msg,sender()) }
  
  
}



// reaper to terminate my application
// see http://letitcrash.com/post/30165507578/shutdown-patterns-in-akka-2

object Reaper {
  val props = Props[Reaper]
}

class Reaper extends Actor with ActorLogging {      
  def receive = { case x: Any  => log.error("unexpected message in reaper: " + x)  }
  override def postStop = { println ("shutting down...") ;  context.system.terminate(); }  
}

