package cosbench_ng


import akka.actor. { ActorSystem, Actor, ActorLogging, Props, PoisonPill }

//Cluster imports
import akka.cluster.{ Cluster, ClusterEvent }
import akka.cluster.ClusterEvent._
import com.typesafe.config._

import akka.event.LoggingReceive

import java.net.InetAddress

// log4j
import org.slf4j.{LoggerFactory}
import ch.qos.logback.classic.Level


object Remote  extends App {

   val log = LoggerFactory.getLogger(this.getClass)
  
  // parse the command line
  val cmd = CmdLineParser.parseCmdLine(args)
  if (cmd.isEmpty) System.exit(0)

  // set debug based on commandline
  if (cmd.get.debug) {
    log.warn("Setting debug mode")
    LoggerFactory.getLogger("cosbench_ng")
      .asInstanceOf[ch.qos.logback.classic.Logger]
      .setLevel(Level.DEBUG)
  }

  
  val (hostName,bindHostname) = 
    (sys.env.get("HOST_IP_ADDR").getOrElse(InetAddress.getLocalHost.getHostAddress),InetAddress.getLocalHost.getHostAddress)
     
  val portNumber =  sys.env.get("HOST_PORT_NO").getOrElse("0") 
        
        
  // setup configuration
  val myConfig = ConfigFactory.parseString(
      String.format("""
        Slave.akka.remote.artery.canonical.hostname = "%s"
        Slave.akka.remote.artery.canonical.port     = "%s"
        Slave.akka.remote.artery.bind.hostname = "%s"
        Slave.akka.remote.artery.bind.port     = "%s"
        Slave.akka.cluster.seed-nodes = ["%s"]
        """.stripMargin, hostName, portNumber, bindHostname, portNumber, cmd.get.master.get))
        


  // http://doc.akka.io/docs/akka/current/general/configuration.html#Reading_configuration_from_a_custom_location
  val config = ConfigFactory.load( myConfig.withFallback( ConfigFactory.load() ) )

  // actors in this actor system are created remotely
  implicit val system = ActorSystem("system",config.getConfig("Slave").withFallback(config))
  val reaper = system.actorOf(Reaper.props,"Reaper")

  
  val cluster = Cluster.get(system)

  println("Slave is up at: " + cluster.selfUniqueAddress.address) 
  println("Connecting to master: " + cmd.get.master.get)  
  
  cluster.subscribe(system.actorOf(MyClusterMonitor.props(config)),
      ClusterEvent.initialStateAsEvents,  
      classOf[MemberEvent],
      classOf[UnreachableMember])
   
}

object MyClusterMonitor {
  def props (c: com.typesafe.config.Config) : Props = Props(classOf[MyClusterMonitor],c)
}

class MyClusterMonitor (c: com.typesafe.config.Config) extends Actor with ActorLogging {
  
  var seedNodeUp = false
  
  
  def receive = LoggingReceive {
    case x: MemberUp          =>
      seedNodeUp = isSeedPresent(x.member.address.toString )

    case x: MemberExited      =>  selfExitOnSeedExit(x.member.address.toString)
    case x: MemberRemoved     =>  selfExitOnSeedExit(x.member.address.toString)
    case x: UnreachableMember =>  selfExitOnSeedExit(x.member.address.toString)            
    case x: Any               =>  { log.debug("MyClusterMonitor received: " + x) }      
  }

  def selfExitOnSeedExit(addr: String) =  if(isSeedPresent(addr))  { 
    context.system.actorSelection("/user/Reaper") ! PoisonPill
    log.debug("Slave exiting, since seed has exited")
  }
    
  def isSeedPresent(addr: String) = c.getList("Slave.akka.cluster.seed-nodes").unwrapped().contains(addr)
}
