package cosbench_ng


import akka.NotUsed
import akka.pattern.ask
import akka.stream.{IOResult, ActorMaterializer, ClosedShape, DelayOverflowStrategy, ThrottleMode}
import akka.stream.scaladsl.{Source, Sink, FileIO, Flow, RunnableGraph, GraphDSL, Broadcast, Balance, Merge}
import akka.stream.Attributes._
import akka.actor. { ActorSystem, Actor, ActorLogging, Props }
import akka.util.{ ByteString, Timeout }
import scala.util.{ Success, Failure }
import scala.concurrent.Future
import scala.concurrent.duration._
import java.nio.file.Paths
import scala.BigInt
import scala.math.BigInt.int2bigInt




object Remote  extends App {

  // actors in this actor system are created remotely
  implicit val system = ActorSystem("remote-actorsystem")  
   
  println("Remote is up...")
  
}


// gracefully terminate this actor system
object RemoteReaper { 
  val props = Props[RemoteReaper]
}

class RemoteReaper extends Actor with ActorLogging { 
  def receive = { case x: Any => log.error("Unexpected msg received: " + x) }
  override def postStop = { context.system.terminate }  
}


