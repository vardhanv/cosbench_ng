package cosbench_ng


import akka.NotUsed
import akka.pattern.ask
import akka.stream.{IOResult, ActorMaterializer, ClosedShape, DelayOverflowStrategy, ThrottleMode}
import akka.stream.scaladsl.{Source, Sink, FileIO, Flow, RunnableGraph, GraphDSL, Broadcast, Balance, Merge}
import akka.stream.Attributes._
import akka.actor. { ActorSystem, Actor, ActorLogging, Props, Address, PoisonPill }
import akka.util.{ ByteString, Timeout }
import scala.util.{ Success, Failure }
import scala.concurrent.Future
import scala.concurrent.duration._
import java.nio.file.Paths
import scala.BigInt
import scala.math.BigInt.int2bigInt

//Cluster imports
import akka.cluster.{ Cluster, ClusterEvent }
import akka.cluster.ClusterEvent._
import akka.cluster.singleton._
import com.typesafe.config._

import java.net.InetAddress

object Remote  extends App {

  // parse the command line
  val cmd = CmdLineParser.parseCmdLine(args)
  if (cmd.isEmpty) System.exit(1)
  
  // setup configuration
  val myConfig = ConfigFactory.parseString(
      String.format("""
        akka.remote.artery.canonical.hostname = "%s"
        akka.cluster.seed-nodes = ["%s"]
        """.stripMargin, InetAddress.getLocalHost.getHostAddress, cmd.get.master.get))
        
  println("Connecting to master: " + cmd.get.master.get)


  // http://doc.akka.io/docs/akka/current/general/configuration.html#Reading_configuration_from_a_custom_location
  val config = ConfigFactory.load( myConfig.withFallback( ConfigFactory.load() ) )

  // actors in this actor system are created remotely
  implicit val system = ActorSystem("system",config)
  
  val cluster = Cluster.get(system)  
  println("Slave is up at: " + cluster.selfUniqueAddress.address)
  
  
}
