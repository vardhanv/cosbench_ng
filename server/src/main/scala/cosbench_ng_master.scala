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
  val config = ConfigFactory.load().getConfig("Master.Cosbench_ng")
  var cl: Option[Config]           = None // parsed command line
  var rawCl: Option[Array[String]] = None // raw cmd line

  // internal config
  val maxThreads : Int = config.getInt("maxThreads")  
}

case class Config(
  bucketName       : String = "Vishnu_test",
  cmd              : String = "PUT",   // "PUT" or "GET" - action to execute
  testTag          : String = "NOTAG",
  
  opsRate          : Int    =  200,    // target ops per second 
  maxOps           : Long   =  5000,   // total ops
          
  objSize          : Long   =  1,      // Obj size in KB
  rangeReadStart   : Long   =  0,      // range read start. 0 = read the whole object
  rangeReadEnd     : Long   =  0,      // range read end

  endpoint         : String = "https://s3.amazonaws.com",
  region           : String = "us-east-1", 
  awsProfile       : String = "default",
  fakeS3Latency    : Long   =  0,      // fake s3 latency
  
  runToCompletion  : Boolean = false,  // don't exit, but wait for everything to complete
  minSlaves        : Long    =  0      // minimum slaves to wait before we start work
)
  
class  ConfigMsg (c: Config) extends java.io.Serializable { val config = c }

object MyCmd { def apply(s: Int, e: Int) = new MyCmd(s,e) }
class  MyCmd(val start : Int = 0, val end : Int = 99) extends java.io.Serializable {}

object MyCmdIter { def apply(i: Int, inc: Int) = new MyCmdIter(i,inc) }
class  MyCmdIter(val start: Int, val inc: Int) extends Iterator[MyCmd] { 
  var index : Option[MyCmd] = None  
  def hasNext = true
  def next = {
    val nI = index.getOrElse(MyCmd(start,start+inc))
    index = Some(MyCmd(nI.start+inc+1, nI.end+inc+1))
    nI
  }
}

object LogFile { val directory = "/tmp/cosbench_ng" }

object Main {
  def main(args: Array[String]): Unit = {

    val log = LoggerFactory.getLogger(this.getClass)
    
    val cmdLine = CmdLineParser.parseCmdLine(args)

    MyConfig.rawCl = Some(args)
    MyConfig.cl    =
      if (cmdLine.isDefined)   Some(cmdLine.get)
      else { System.exit(0) ;  None }
          
    // validate and setup config file        
    val portNumber   = ConfigFactory.load().getInt("Master.akka.remote.artery.canonical.port")
    val hostName     = sys.env.get("HOST_IP_ADDR").getOrElse(InetAddress.getLocalHost.getHostAddress) 
    val bindHostname = InetAddress.getLocalHost.getHostAddress
      
    val myConfig = ConfigFactory.parseString(
      String.format("""
        Master.akka.remote.artery.canonical.hostname = "%s"
        Master.akka.remote.artery.bind.hostname = "%s"
        Master.akka.remote.artery.bind.port = 25521
        Master.akka.cluster.seed-nodes = ["akka://system@%s:%s"]
        """.stripMargin, hostName, bindHostname, hostName, portNumber.toString()))
        
    // http://doc.akka.io/docs/akka/current/general/configuration.html#Reading_configuration_from_a_custom_location
    val config = ConfigFactory.load(myConfig.withFallback(ConfigFactory.load()))
        
    if (config.getStringList("akka.cluster.seed-nodes").size() > 1) {
      log.error("too many seed nodes in config file")
      System.exit(1)
    }
    
    println("Cosbench_ng master UP at: akka://system@%s:%d".format(hostName,portNumber))
    println("Status in log directory: %s".format(LogFile.directory) )
        
    implicit val asystem = ActorSystem("system",config.getConfig("Master").withFallback(config))
    implicit val timeout = Timeout(5.seconds)      
    val localReaper      = asystem.actorOf(Reaper.props,"Reaper") // to terminate at the end    
    val cluster          = Cluster.get(asystem)
    val flowControlActor = asystem.actorOf(FlowControlActor.props,"FlowControl")
        
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
      
    flowControlActor ! localRouter  // set the local singleton proxy actor in the flowControlActor

    // start listening for slaves to come up
    cluster.subscribe(flowControlActor, ClusterEvent.initialStateAsEvents,  classOf[MemberEvent])

    // wait for everything to run and then shutdown    
  }  
}


