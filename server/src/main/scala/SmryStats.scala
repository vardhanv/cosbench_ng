package cosbench_ng

import java.io.File



case class StatList (sl : List[Stats])

class Stats() extends java.io.Serializable
class GoodStat (val rspStarted: Double, val rspComplete: Double) extends Stats  //status = failed_op or successfull_op
class BadStat() extends Stats
class FinalStat(val opsStartedNCompleted: Int, val opsCompletedStatsNSent : Int, val opsNStarted : Int) extends Stats
case class IntermediateStats (vSum_ : Double = 0, vSumSqr: Double = 0, count: Long =0, k: Option[Double] = None)

object GoodStat  { def apply(rs: Double, rc: Double) = new GoodStat(rs,rc) }
object FinalStat { def apply( r: Int, s: Int, t: Int) = new FinalStat(r,s,t) }
object BadStat   { def apply() = new BadStat() } 


case class Metric(average: Double = 0,
    min: Double = 999999999,
    max: Double = 0,
    inter: IntermediateStats = IntermediateStats()) {

  def merge(newStat: Double): Metric = {
    // calculate summary stats. Variance from 
    // https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance (computing shifted data)
    // variance calculation
    
    val newCount = inter.count +1    
    val local = (newStat - inter.k.getOrElse(newStat))

    val nInter = IntermediateStats(inter.vSum_ + local, 
        inter.vSumSqr + local * local, 
        newCount, Some(inter.k.getOrElse(newStat)))

    new Metric( ((average * inter.count) + newStat) / newCount, //avg 
        if (newStat < min) newStat else min, 
        if (newStat > max) newStat else max, 
        nInter)
  }
  
  def stdDeviation = 
    Math.sqrt( (inter.vSumSqr - (inter.vSum_ * inter.vSum_)/inter.count)/( if (inter.count > 1) (inter.count - 1) else 1))
}
      
object SmryStats {  
  def apply ( a:Int, b:Int, c:Int , f: Int,  m1: Metric, m2: Metric) = new SmryStats(a,b,c,f,m1,m2)
  def apply (c: SmryStats) = new SmryStats(
      c.opsStartedNCompleted,
      c.opsCompletedStatsNSent,
      c.opsNStarted,
      c.failed, c.rspStart, c.rspEnd)
  def apply() = new SmryStats()
}


class SmryStats ( 
    val opsStartedNCompleted: Int    = 0, 
    val opsCompletedStatsNSent : Int = 0, 
    val opsNStarted : Int = 0,   
    val failed: Int       = 0,  
    val rspStart: Metric = new Metric(), 
    val rspEnd: Metric = new Metric()) {

  def merge(g: GoodStat): SmryStats = new SmryStats(opsStartedNCompleted,
    opsCompletedStatsNSent,
    opsNStarted,
    failed,
    rspStart.merge(g.rspStarted),
    rspEnd.merge(g.rspComplete))

  def merge(b: BadStat): SmryStats = new SmryStats(opsStartedNCompleted,
    opsCompletedStatsNSent,
    opsNStarted,
    failed + 1,
    rspStart,
    rspEnd)

  def merge(f: FinalStat): SmryStats = new SmryStats(
    opsStartedNCompleted + f.opsStartedNCompleted,
    opsCompletedStatsNSent + f.opsCompletedStatsNSent,
    opsNStarted + f.opsNStarted,
    failed,
    rspStart,
    rspEnd)
  
    
  // calculate summary stats. Variance from 
  // https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance (computing shifted data)
  def updateSmryStats (x: Stats) : SmryStats = x match { 
    case y: GoodStat  => merge(y)
    case y: BadStat   => merge(y)
    case y: FinalStat => merge(y)
  }

  
  def printSmryStats (runTime: Long) = {
    
    val count = rspStart.inter.count
    val stdDeviation = rspStart.stdDeviation.toLong
    val objRate = count/(runTime/1000)
      
    println ("TTFB (avg,min,max,std)              : (" +  rspStart.average.toLong 
        +  "," + rspStart.min.toLong + "," + rspStart.max.toLong 
        +  "," + rspStart.stdDeviation.toLong +") ms" );

    println ("TTLB (avg,min,max,std)              : (" + rspEnd.average.toLong 
        +  "," + rspEnd.min.toLong 
        + "," + rspEnd.max.toLong +"," 
        + rspEnd.stdDeviation.toLong +") ms" );

    println ("No of ops  (Target, Actual)         : (" + MyConfig.cl.get.maxOps + "," + count +")")    
    println ("Ops/second (Target, Actual)         : (" + MyConfig.cl.get.opsRate +"," + objRate +")")
    println ("Throughput(KB)/sec (Target, Actual) : (" + MyConfig.cl.get.opsRate.toFloat*MyConfig.cl.get.objSize.toFloat/1024 + "MB/s" + "," + 
        objRate.toFloat*MyConfig.cl.get.objSize.toFloat/1024 + "MB/s" + ")")    
    println ("execution time                      : " + runTime/1000 + " seconds")

    println("Expected Errors:")
    println ("+ ops - failed                      : +" + failed.toLong)
    println ("+ ops - queued but not started      : +" + opsNStarted.toLong)
    println ("+ ops - started but not completed   : +" + opsStartedNCompleted.toLong)
    println ("+ ops - completed but stats dropped : +" + opsCompletedStatsNSent.toLong)
    
    if (MyConfig.cl.get.maxOps != (count + failed.toLong + opsNStarted.toLong + opsStartedNCompleted.toLong + opsCompletedStatsNSent.toLong)) {
      println("*** WARNING: OBJECT COUNT TOTALS DONT ADD UP - Add dropped elements and check total *** ")
    }

  }
  
}
    
