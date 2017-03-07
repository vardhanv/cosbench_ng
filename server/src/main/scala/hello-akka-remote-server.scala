package cosbench_ng

import akka.NotUsed
import akka.pattern.ask

import com.typesafe.config.ConfigFactory

import akka.stream.{IOResult, ActorMaterializer, ClosedShape, DelayOverflowStrategy, OverflowStrategy}
import akka.stream.{ActorAttributes, ThrottleMode, Supervision, FlowShape, Fusing}

import akka.stream.scaladsl.{Source, Sink, FileIO, Flow, RunnableGraph, GraphDSL}
import akka.stream.scaladsl.{Broadcast, Balance, Merge, Keep}
import akka.stream.Attributes._

import akka.actor.{ Identify, ActorIdentity, PoisonPill }
import akka.actor.ActorSystem

import akka.util.{ ByteString, Timeout }
import scala.util.{ Success, Failure }
import scala.concurrent.Future
import scala.concurrent.duration._
import java.nio.file.Paths
import scala.BigInt
import java.util.concurrent.atomic.AtomicLong


import scala.math.BigInt.int2bigInt


case class SmryStats (sum: Double, count: Double)
case class Stats (duration: Double)
case class IntermediateStats (sum_ : Double, sumSqr: Double, k: Option[Double]) 
case class SmryStats_1 (average: Double, count: Long, min: Double, max: Double, variance: Double, inter: IntermediateStats)



object MyConfig {
  val config = ConfigFactory.load().getConfig("Cosbench_ng")

  val maxThreads : Int = config.getInt("maxThreads")
  val opsRate  : Int = config.getInt("opsRate")
  val maxOps   : Long = config.getInt("maxOps")
  val reserveLoad  = config.getInt("reserveLoad") // number of pending ops before we generate new load
}

object Main {
  def main(args: Array[String]): Unit = {

    import system.dispatcher
    implicit val system = ActorSystem("system")
    implicit val materializer = ActorMaterializer()
    implicit val timeout = Timeout(5.seconds)

    val localReaper = system.actorOf(Reaper.props)
    val remoteReaper = system.actorOf(Reaper.props, "RemoteReaper")

    val localRouter = system.actorOf(MyRouter.props, "Router")
    val remoteRouter = system.actorOf(MyRouter.props, "remote_agent")

    // calculate summary stats. Variance from https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance (computing shifted data)
    val nullSmryStats = SmryStats_1(average = 0, count = 0,
      min = 999999999, max = 0, variance = 0, IntermediateStats(0, 0, None))

    val statsSink: Sink[List[Stats], Future[SmryStats_1]] = Sink.fold(nullSmryStats)((s, x) => {
      x.foldLeft(s)(updateSmryStats)
    })

    // get one integer every second, each integer maps to 100 operations
    val putStream = Source.fromIterator(() => Iterator.from(0))
      .throttle(MyConfig.opsRate / 100, 1.second, 1, ThrottleMode.Shaping)
      .take(MyConfig.maxOps / 100)

    // adding flow attributes 
    // specifically a resuming decider so that we can ignore errors in a particular flow and keep going
    // this was discovered when there was a network outage and the remote system was not responding
    // doing the simplest recovery right now
    // see: http://doc.akka.io/docs/akka/2.4.16/scala/stream/stream-error.html
    val flowAttributes = ActorAttributes.supervisionStrategy(Supervision.resumingDecider)

    val localFlow = Flow[Int]
      .mapAsyncUnordered(1)(x => (localRouter ? x) // async will lose msgs if remote is down
        .mapTo[List[Stats]])
      .withAttributes(flowAttributes) // resume on failure
      .buffer(1, OverflowStrategy.backpressure) // back pressure if small buffer is full

    val remoteFlow = Flow[Int]
      .mapAsyncUnordered(1)(x => (remoteRouter ? x)
        .mapTo[List[Stats]])
      .withAttributes(flowAttributes)
      .buffer(1, OverflowStrategy.backpressure) // back pressure if small buffer is full

    val damFlow = Fusing.aggressive(GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits._

        // Acts as a dam that prevents a fast producer from overwhelming our slow consumers
        // 1. get the input elements, and if you can't forward them, start dropping them
        // 2. create a side stream of the number of dropped elements
        //    2a. Create a sink of the dropped elements
        // 3. a stream of current elements for further processing

        case class DrpdItms(smry: Int, curr: Int)

        val dropItems = builder.add(
          Flow[Int].conflateWithSeed(curr => DrpdItms(0, curr))((missed, x) => DrpdItms(missed.smry + 1, x)))

        // the flow that of the number of dropped elements                             
        val dropSmry = builder.add(
          Flow.fromFunction { elem: DrpdItms => elem.smry })

        // flow of the available elements
        val nextElem = builder.add(
          Flow.fromFunction { elem: DrpdItms => elem.curr })

        val broadcast = builder.add(Broadcast[DrpdItms](2))
        val totalDroppedSink = builder.add(
          Sink.fold(zero = 0) { (s, c: Int) =>
            if (c > 0) println("dropping: " + c * 100 + " elements. Total dropped: " + (s + c) * 100)
            s + c
          })

        dropItems ~> broadcast ~> dropSmry ~> totalDroppedSink
        broadcast ~> nextElem
        FlowShape(dropItems.in, nextElem.out)
    })

    val runStartTime = System.nanoTime / 1000000

    val g1 = RunnableGraph.fromGraph(GraphDSL.create(putStream, damFlow, localFlow, remoteFlow, statsSink)((_, _, _, _, z) => z) {
      implicit builder =>
        (pStream, damProducer, localF, remoteF, dSink) =>
          import GraphDSL.Implicits._

          val balance = builder.add(Balance[Int](2))
          val merge = builder.add(Merge[List[Stats]](2))

          pStream ~> damProducer ~> balance ~> Flow[Int].log("MyLogLocal") ~> localF ~> merge ~> Flow[List[Stats]].log("MyLogLocal") ~> dSink
          balance ~> Flow[Int].log("MyLogRemote") ~> remoteF ~> merge

          ClosedShape
    })

    g1.run().onComplete {
      case Success(v) =>
        println("\nStream done with summary stats: ");

        val runEndTime = System.nanoTime() / 1000000

        printSmryStats(v, runEndTime - runStartTime)
        localReaper ! PoisonPill

      case Failure(v) => println("Stream done with error: " + v.getMessage); system.terminate
    }
  }

    

  
  
  def updateSmryStats (s: SmryStats_1, x: Stats) : SmryStats_1 = {
    
    val min   = if (x.duration < s.min) x.duration else s.min
    val max   = if (x.duration > s.max) x.duration else s.max
    val count = s.count + 1
        
    val local = (x.duration - s.inter.k.getOrElse(x.duration))
    val sum_     = s.inter.sum_ + local
    val sumSq    = s.inter.sumSqr + local * local
    
    val variance = (sumSq - (sum_ * sum_)/count)/( if (count > 1) (count - 1) else 1)
    val average  = (sum_ + s.inter.k.getOrElse(x.duration)*count)/count
    
    val nInter = IntermediateStats(sum_, sumSq, Some(s.inter.k.getOrElse(x.duration)))
    
    SmryStats_1(average,count,min,max,variance,nInter)    
  } 
  
  def printSmryStats (s: SmryStats_1, runTime: Long) = {
    println ("latency(avg,min,max) : (" + 
        s.average.toLong +  "," + s.min + "," + s.max +") ms" );
    println ("no of objs           : " + s.count + " objects")
    println ("Target ops rate      : " + MyConfig.opsRate + "/second");
    println ("Actual objs rate     : " + s.count/(runTime/1000) +"/second")
    println ("execution time       : " + runTime/1000 + " seconds")
    println ("variance             : " + s.variance.toLong)                
  }
  
}


