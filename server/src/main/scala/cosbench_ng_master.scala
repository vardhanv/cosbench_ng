package cosbench_ng



import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorSystem, PoisonPill }
import akka.event.Logging.DebugLevel

//Cluster imports
import akka.cluster.{ Cluster, ClusterEvent }
import akka.cluster.ClusterEvent._
import akka.cluster.singleton._

// log4j
import org.slf4j.{LoggerFactory}
import ch.qos.logback.classic.Level

import java.net.InetAddress

import akka.util.{ Timeout }
import scala.concurrent.duration._

import com.amazonaws.auth.{DefaultAWSCredentialsProviderChain, BasicAWSCredentials }



object LogFile { val directory = "/tmp/cosbench_ng" }

object Main {
  def main(args: Array[String]): Unit = {

    val log = LoggerFactory.getLogger(this.getClass)
    
    processCmdLine(args)    

    val c = MyConfig.cl.get
    
    val t = "Cosbench_ng Executing: %s".format(MyConfig.rawCl.get.mkString(" "))
    log.warn(t)
    println(t)
    
    // set debug based on commandline
    if (c.debug > 0) {
      val l = if (c.debug == 1) Level.INFO else Level.DEBUG
      LoggerFactory.getLogger("cosbench_ng")
        .asInstanceOf[ch.qos.logback.classic.Logger]
        .setLevel(l)
    }
    
    
    // set s3 credentials    
    val s3Cred =
        if (c.aidSkey._1 == "aid") // still the default value
          DefaultAWSCredentialsProviderChain.getInstance().getCredentials
        else
          new BasicAWSCredentials(c.aidSkey._1, c.aidSkey._2)

    val aidSkey = (s3Cred.getAWSAccessKeyId, s3Cred.getAWSSecretKey)

    // create a new config except for the access id
    MyConfig.cl = Some(Config(
      c.bucketName, c.cmd,
      c.testTag, c.opsRate, c.maxOps, c.objSize,
      c.rangeReadStart, c.rangeReadEnd, c.endpoint, c.region,
      aidSkey,
      c.fakeS3Latency, c.runToCompletion, c.minSlaves, c.debug))  
        
    GetS3Client.get(MyConfig.cl.get)
      
    if (c.fakeS3Latency > -1)
      println("Using fake s3 and ignoring s3 config")
    else {
      println("Valdating s3 config by doing a listObject on the specified bucket")
      if (GetS3Client.test(c.bucketName) == false) {
        println("S3 configuration error")
        System.exit(1)        
      }
      println("s3 config okay..")
    }
        
              
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

    println("Status in log directory  : %s".format(LogFile.directory) )    
    println("Cosbench_ng master UP at : akka://system@%s:%d".format(hostName,portNumber))

        
    implicit val asystem = ActorSystem("system",config.getConfig("Master").withFallback(config))    
    
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

  def processCmdLine(args: Array[String]) = {

    MyConfig.rawCl = Some(args)
    MyConfig.cl = CmdLineParser.parseCmdLine(args)

    if (MyConfig.cl.isDefined) { } // all okay
    else System.exit(0)
  }

}


