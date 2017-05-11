package cosbench_ng


import akka.pattern.ask

import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorSystem, PoisonPill }

//Cluster imports
import akka.cluster.{ Cluster, ClusterEvent }
import akka.cluster.ClusterEvent._
import akka.cluster.singleton._

// log4j
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.InetAddress

import akka.util.{ Timeout }
import scala.concurrent.duration._




object MyConfig {
  val config = ConfigFactory.load().getConfig("Cosbench_ng")
  var cl: Option[Config] = None  // command line

  // internal config
  val maxThreads : Int = config.getInt("maxThreads")  
}


case class Config(
  bucketName       : String = "Vishnu_test",
  cmd              : String = "PUT",  // "PUT" or "GET" - action to execute
  testTag          : String = "NOTAG",
  
  opsRate          : Int    =  200,     // target ops per second 
  maxOps           : Long   =  5000,    // total ops
  
      
  objSize          : Long   =  1,      // Obj size in KB
  rangeReadStart   : Long   =  0,      // range read start. 0 = read the whole object
  rangeReadEnd     : Long   =  0,     // range read end

  endpoint         : String = "https://s3.amazonaws.com",
  region           : String = "us-east-1", 
  awsProfile       : String = "default",  
  
  runToCompletion  : Boolean = false,  // don't exit, but wait for everything to complete
  minSlaves        : Long    =  0)   // minimum slaves to wait before we start work

  
class ConfigMsg (c: Config) extends java.io.Serializable { val config = c }

object MyCmd { def apply(s: Int, e: Int) = new MyCmd(s,e) }
class  MyCmd(val start : Int = 0, val end : Int = 99) extends java.io.Serializable {}

object MyCmdIter { def apply(i: Int, inc: Int) = new MyCmdIter(i,inc) }
class MyCmdIter(val start: Int, val inc: Int) extends Iterator[MyCmd] {
  
  var index : Option[MyCmd] = None
  
  def hasNext = true
  def next = {
    val nI = index.getOrElse(MyCmd(start,start+inc))
    index = Some(MyCmd(nI.start+inc+1, nI.end+inc+1))
    nI
  }
}


object Main {
  def main(args: Array[String]): Unit = {

    val log = LoggerFactory.getLogger(this.getClass)
    
    val cmdLine = CmdLineParser.parseCmdLine(args)
    if (cmdLine.isDefined)
      MyConfig.cl = Some(cmdLine.get)
    else
      System.exit(0)
    
    
    
    // validate and setup config file        
    val portNumber = ConfigFactory.load().getInt("akka.remote.artery.canonical.port")
    val hostName = InetAddress.getLocalHost.getHostAddress
    val myConfig = ConfigFactory.parseString(
      String.format("""
        akka.remote.artery.canonical.hostname = "%s"
        akka.cluster.seed-nodes = ["akka://system@%s:%s"]
        """.stripMargin, hostName, hostName, portNumber.toString()))
        
    // http://doc.akka.io/docs/akka/current/general/configuration.html#Reading_configuration_from_a_custom_location
    val config = ConfigFactory.load(myConfig.withFallback(ConfigFactory.load()))
        
    if (config.getStringList("akka.cluster.seed-nodes").size() > 1) {
      log.error("too many seed nodes in config file")
      System.exit(1)
    }
    
    println("Cosbench_ng master is UP at: " + config.getStringList("akka.cluster.seed-nodes").get(0))
    println("Status in log directory: /tmp/cosbench_ng")
        
    implicit val asystem = ActorSystem("system",config)
    implicit val timeout = Timeout(5.seconds)
      
    val cluster = Cluster.get(asystem)
    val flowControlActor = asystem.actorOf(FlowControlActor.props)
    
    
    // create the flow that does the work
    // global singleton actor that routes messages to multiple workers
    // and collates results into a statistics queue      

    asystem.actorOf(ClusterSingletonManager.props(
      singletonProps = MyRouter.props(),
      terminationMessage = PoisonPill,
      settings = ClusterSingletonManagerSettings(asystem).withRole("router")),
      name = "myRouter")
    
    val localRouter = asystem.actorOf(ClusterSingletonProxy.props(singletonManagerPath = "/user/myRouter",
      settings = ClusterSingletonProxySettings(asystem).withRole("router")),
      name = "myRouterProxy")
      
    flowControlActor ! localRouter  // set the stats actor in the localRouter

    // start listening for slaves to come up
    cluster.subscribe(flowControlActor, ClusterEvent.initialStateAsEvents,  classOf[MemberEvent])

    // wait for everything to run and then shutdown    
  }  
}


