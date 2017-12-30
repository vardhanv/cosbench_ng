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



object MyConfig {
  val config = ConfigFactory.load().getConfig("Cosbench_ng.common")
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
  rangeReadStart   : Long   =  -1,      // range read start. -1 = no range read 
  rangeReadEnd     : Long   =  -1,      // range read end

  endpoint         : String = "https://s3.amazonaws.com",
  region           : String = "us-east-1", 
  aidSkey          : (String, String) = ("aid", "skey"),
  fakeS3Latency    : Long   =  -1,      // fake s3 latency
  
  runToCompletion  : Boolean = false,  // don't exit, but wait for everything to complete
  minSlaves        : Long    =  0,     // minimum slaves to wait before we start work
  debug            : Int     =  0 
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



case class StatList (sl : List[Stats])

class Stats() extends java.io.Serializable
class GoodStat (val rspStarted: Double, val rspComplete: Double) extends Stats  //status = failed_op or successfull_op
class BadStat() extends Stats

object GoodStat  { def apply(rs: Double, rc: Double) = new GoodStat(rs,rc) }
object BadStat   { def apply() = new BadStat() } 

object FinalStat { def apply( r: Int, s: Int, t: Int) = new FinalStat(r,s,t) }
class FinalStat(val opsStartedNCompleted: Int, val opsCompletedStatsNSent : Int, val opsNStarted : Int) extends Stats

