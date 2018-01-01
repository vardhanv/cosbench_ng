package cosbench_ng

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, Props, ActorRef, PoisonPill }

import akka.cluster.ClusterEvent._
import akka.cluster.Cluster

import akka.stream.{ ActorMaterializer }
import akka.stream.{ ThrottleMode }
import akka.stream.{ ClosedShape }

import akka.stream.scaladsl.{ Source, Sink, Flow, RunnableGraph, GraphDSL }
import akka.stream.scaladsl.{ Keep }

import scala.concurrent.{ Future }
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

import MyProtoBufMsg._




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
      println("Member " + x.member.uniqueAddress.address + " joined. Waiting for " +
        (MyConfig.cl.get.minSlaves - totalSlaves) + " more slaves ")

        if ( (MyConfig.cl.get.minSlaves - totalSlaves) < 0)
           println("Warning - unexpected slave has joined. Proceeding regardless...")

      if (MyConfig.cl.get.minSlaves == totalSlaves) {
        println("All " + totalSlaves + " slaves are here. Starting test..")

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
      } 

    case lr: ActorRef => {
      
      // this is the constructor. The router sent from main 
      localRouter = Some(lr)

      require(localRouter.isDefined)

      // setup statistics calculator

      val statsSink: Sink[StatListMsg, Future[SmryStats]] =
        Sink.fold(new SmryStats())((s, x) => {
          
          // returns a summary stat from the good stats
          val s1 = x.goodStatList.foldLeft(s)((sl, n) => sl.updateSmryStats( n match {
            case g:  GoodStatMsg => GoodStat(g.rspStarted,g.rspComplete)
          })) 
          
          // uses the prior good summary stat, and merges the bad into that
          val s2 = x.badStatList.foldLeft(s1)((sl, n) => sl.updateSmryStats( n match {
            case b:  BadStatMsg => BadStat()
          })) 
          
          // merges the currStatus if required, returns the  summary 
          // to be used the next time we get a StatListMsg
          if (x.currStatus.isDefined) {
            val f = x.currStatus.get
            s2.updateSmryStats(FinalStat(f.opsStartedNCompleted, f.opsCompletedStatsNSent, f.opsNStarted))
          }
          else
            s2
          
//          x.sl.foldLeft(s)((s, n) => s.updateSmryStats(n))
          })

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

      // control rate of internal ops
      // < 10  :   1  s3op per internal msg
      // < 100 :  25 s3ops per internal msg
      // > 100 : 100 s3ops per internal msg
      val opsRateFactor =
        if (MyConfig.cl.get.opsRate < 10) 1
        else if (MyConfig.cl.get.opsRate < 100) 4
        else 100
        
      // MyCmd (x: start offset, y: Number of puts)
      // Per s3Cmd, we will start s3Ops from (x*opsRate) object name
      // for y number of S3ops. 
      val putStream = Source.fromIterator(() => MyCmdIter(0,opsRateFactor-1) )
        .throttle(MyConfig.cl.get.opsRate/opsRateFactor, 1.second, 1, ThrottleMode.Shaping)
        .take(MyConfig.cl.get.maxOps/(opsRateFactor) )

        
      graphToRun = Some(RunnableGraph.fromGraph(GraphDSL.create(putStream, routerSink)((_, z) => z) {
        implicit builder =>
          (pStream, routerS) =>
            import GraphDSL.Implicits._

            // count how much progress in % have we made
            val countProgress = builder.add(Flow[MyCmd].scan(MyCmd(0,0))((s, n) => {
              
              //if(System.nanoTime() % 3 == 0 ) // don't print everything
                 print("\rStarted: %d %% s3ops".format( (n.start * 100)/MyConfig.cl.get.maxOps))
                 
              n
            }).drop(1))

            pStream ~> countProgress ~> routerS

                                                     
            ClosedShape
      }))
    }
    case x: UnreachableMember => { 
      log.debug(x.toString())
      log.warning("Removing " + x.member.uniqueAddress + " from this cluster, since it is unreachable")
      Cluster.get(context.system).down(x.member.address)
      Cluster.get(context.system).leave(x.member.address)
    }
    case x: MemberLeft    =>  {  log.warning(x.toString)  }
    case x: MemberExited  =>  {  log.warning(x.toString)  }
    case x: MemberRemoved =>  {  log.warning(x.toString)  }
    case x: MemberJoined  =>  {  log.debug(x.toString)  }
    case x: Any           =>  {  log.error(x.toString) }
  }
}