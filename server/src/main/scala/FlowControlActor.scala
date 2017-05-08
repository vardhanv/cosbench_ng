package cosbench_ng

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, Props, ActorRef, PoisonPill }

import akka.cluster.ClusterEvent._

import akka.stream.{ IOResult, ActorMaterializer, ActorMaterializerSettings }
import akka.stream.{ ActorAttributes, ThrottleMode, Supervision, FlowShape, Fusing }
import akka.stream.{ ClosedShape, DelayOverflowStrategy, OverflowStrategy }

import akka.stream.scaladsl.{ Source, Sink, FileIO, Flow, RunnableGraph, GraphDSL }
import akka.stream.scaladsl.{ Broadcast, Balance, Merge, Keep }
import akka.stream.Attributes
import akka.stream.Attributes._

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

import akka.cluster.singleton._


object FlowControlActor {
  val props = Props(classOf[FlowControlActor])
}

class FlowControlActor extends Actor with ActorLogging {
  var totalSlaves = 0
  var localRouter: Option[ActorRef] = None
  var graphToRun: Option[akka.stream.scaladsl.RunnableGraph[NotUsed]] = None
  var flowComplete: Option[Future[SmryStats]] = None

  val asystem = context.system

  import asystem.dispatcher

  implicit val materializer = ActorMaterializer()

  // init actions
  val localReaper = asystem.actorOf(Reaper.props) // to terminate at the end

  def receive = {
    case x: MemberUp =>
      totalSlaves += (if (x.member.roles.contains("slave")) 1 else 0)
      println("Member " + x.member.uniqueAddress.address + " joined")

      if (MyConfig.cl.get.minSlaves == totalSlaves) {
        println("All slaves are here. Starting test..")

        val runStartTime = System.nanoTime / 1000000

        require(graphToRun.isDefined && flowComplete.isDefined)

        graphToRun.map(_.run())
        
        flowComplete.map(_.onComplete {
          case Success(v) =>
            log.error("\nStream done with summary stats: ");

            val runEndTime = System.nanoTime() / 1000000

            v.printSmryStats(runEndTime - runStartTime)

            S3Ops.shutdown()
            context.system.actorSelection("/user/*") ! PoisonPill

          case Failure(v) => log.error("Stream done with error: " + v.getMessage); asystem.terminate
        })
      } else if (MyConfig.cl.get.minSlaves > totalSlaves){
        println("Member joined. Total slaves = " + totalSlaves
          + ". Waiting for quorom slaves(pending: " + (MyConfig.cl.get.minSlaves - totalSlaves) + ") ...")
      } 

    case lr: ActorRef => {
      // lets setup the flow

      // got the router
      localRouter = Some(lr)

      require(localRouter.isDefined)

      // setup statistics calculator

      val statsSink: Sink[StatList, Future[SmryStats]] =
        Sink.fold(new SmryStats())((s, x) => x.sl.foldLeft(s)((s, n) => s.updateSmryStats(n)))

      // get the actor which will be a sink for the stats
      // and a future that marks everything complete
      val (statsFlowActor, statsCompleteF) =
        Source.actorRef(100, akka.stream.OverflowStrategy.fail)
          .toMat(statsSink)(Keep.both).run()

      localRouter.get ! statsFlowActor
      flowComplete = Some(statsCompleteF)

      val routerSink = Sink.actorRefWithAck(localRouter.get, "start", "ack", "done", (e) => log.error(e.toString()))
       
      
      // Put source
      
      // helps control our internal operations
      val opsRateFactor = 
      if (MyConfig.cl.get.opsRate < 100)
        4
      else 
        100
        
      val putStream = Source.fromIterator(() => MyCmdIter(0,opsRateFactor-1) )
        .throttle(MyConfig.cl.get.opsRate/opsRateFactor, 1.second, 1, ThrottleMode.Shaping)
        .take(MyConfig.cl.get.maxOps/(opsRateFactor) )

        
      graphToRun = Some(RunnableGraph.fromGraph(GraphDSL.create(putStream, routerSink)((_, z) => z) {
        implicit builder =>
          (pStream, routerS) =>
            import GraphDSL.Implicits._

            // create all the pieces and then stitch them together in the end

            case class DrpdItms(smry: Int, curr: MyCmd) // to track dropped items

            // count how much progress in % have we made
            val countProgress = builder.add(Flow[MyCmd].scan(MyCmd(0,0))((s, n) => {
              // calc % complete                            
              log.info("Completed TBD")
              n
            }).drop(1))

            // drop items if we are unable to consume them fast enough
            val dropItems = builder.add(Flow[MyCmd].conflateWithSeed(curr =>
              DrpdItms(0, curr))((missed, x) => {
                DrpdItms(missed.smry + 1, x)})
              .withAttributes(Attributes.inputBuffer(1, 1)))

            // count the total dropped items
            val totalDroppedSink = builder.add(
              Sink.fold(zero = 0) { (s, c: DrpdItms) =>
                if (c.smry > 0) println("Request rate too fast, add workers? Dropping: " + c.smry * (opsRateFactor) + " elements. Total dropped: " + ((s + c.smry) * opsRateFactor))
                s + c.smry
              })

            // generic connector
            val broadcast = builder.add(Broadcast[DrpdItms](2))
              
            // the next item, once we can consume them
            val nextElem = builder.add(Flow.fromFunction { elem: DrpdItms => {
              elem.curr }})
            

            // the final flow
            pStream ~> countProgress ~> dropItems ~> broadcast ~> totalDroppedSink
                                                     broadcast ~> nextElem ~> routerS

            ClosedShape
      }))
    }
    case x: Any => { log.debug("FlowControlActor received: " + x) }
  }
}