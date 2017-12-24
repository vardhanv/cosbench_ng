package cosbench_ng

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, Props, ActorRef, PoisonPill }

import akka.cluster.ClusterEvent._

import akka.stream.{ ActorMaterializer }
import akka.stream.{ ThrottleMode }
import akka.stream.{ ClosedShape }

import akka.stream.scaladsl.{ Source, Sink, Flow, RunnableGraph, GraphDSL }
import akka.stream.scaladsl.{ Keep }

import scala.concurrent.{ Future }
import scala.concurrent.duration._
import scala.util.{ Success, Failure }


object FlowControlActor {
  val props = Props(classOf[FlowControlActor])
}

class FlowControlActor extends Actor with ActorLogging {
  var totalSlaves = 0
  var localRouter: Option[ActorRef] = None
  var graphToRun: Option[akka.stream.scaladsl.RunnableGraph[NotUsed]] = None
  var statFlowComplete: Option[Future[SmryStats]] = None

  val asystem = context.system

  import asystem.dispatcher

  implicit val materializer = ActorMaterializer()

   override def postStop = {
      log.debug("FlowControlActor post stop")
      materializer.shutdown()
      context.actorSelection("/user/Reaper") ! PoisonPill              
  }
  

  def receive = {
    case x: MemberUp =>
      totalSlaves += (if (x.member.roles.contains("slave")) 1 else 0)
      println("Member " + x.member.uniqueAddress.address + " joined")

      if (MyConfig.cl.get.minSlaves == totalSlaves) {
        println("All slaves are here. Starting test..")

        val runStartTime = System.nanoTime / 1000000

        require(graphToRun.isDefined && statFlowComplete.isDefined)

        graphToRun.map(_.run())
        
        statFlowComplete.map(_.onComplete {
          case Success(v) =>
            log.debug("Stream successfully completed")
            val runEndTime = System.nanoTime() / 1000000

            v.printSmryStats(runEndTime - runStartTime)

          case Failure(v) => 
            log.error("Master terminating with an error");
            log.error("Stream done with error: " + v.getMessage); 
            context.actorSelection("/user/Reaper") ! PoisonPill
            
        })
      } else if (MyConfig.cl.get.minSlaves > totalSlaves){
        println("Waiting for " + 
                 (MyConfig.cl.get.minSlaves - totalSlaves) + " more slave/slaves."
                 + " Total slaves = " + totalSlaves)
      } 

    case lr: ActorRef => {
      
      // this is the constructor. The router sent from main 
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
      statFlowComplete = Some(statsCompleteF)

      val routerSink = Sink.actorRefWithAck(localRouter.get, "start", "ack", "done", (e) => log.error(e.toString()))

      // putStream --> routerSink --> statsFlowActor
      // Put source

      // control rate of internal ops, otherwise we will generate too many messages
      val opsRateFactor =
        if (MyConfig.cl.get.opsRate < 10) 1
        else if (MyConfig.cl.get.opsRate < 100) 4
        else 100
        
      val putStream = Source.fromIterator(() => MyCmdIter(0,opsRateFactor-1) )
        .throttle(MyConfig.cl.get.opsRate/opsRateFactor, 1.second, 1, ThrottleMode.Shaping)
        .take(MyConfig.cl.get.maxOps/(opsRateFactor) )

        
      graphToRun = Some(RunnableGraph.fromGraph(GraphDSL.create(putStream, routerSink)((_, z) => z) {
        implicit builder =>
          (pStream, routerS) =>
            import GraphDSL.Implicits._

            // count how much progress in % have we made
            val countProgress = builder.add(Flow[MyCmd].scan(MyCmd(0,0))((s, n) => {
              // calc % complete                            
              log.info("Completed TBD")
              n
            }).drop(1))

            pStream ~> countProgress ~> routerS

                                                     
            ClosedShape
      }))
    }
    case x: Any => { log.debug("FlowControlActor received: " + x) }
  }
}