/*
 * Copyright <2017> <Vishnu Vardhan Chandra Kumaran>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of 
 * this software and associated documentation files (the "Software"), to deal in the 
 * Software without restriction, including without limitation the rights to use, copy, 
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, 
 * and to permit persons to whom the Software is furnished to do so, subject to the 
 * following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies
 *  or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, 
 *  INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR 
 *  PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 *   LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT 
 *   OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER 
 *   DEALINGS IN THE SOFTWARE.
*/

package cosbench_ng

// scopt for commandline
import scopt._
import org.apache.log4j._

case class CmdLineConfig(
  master: Option[String] = None,
  localDebug : Int = 0
)

object CmdLineParser {

  def parseCmdLine(a: Array[String]): Option[CmdLineConfig] = {

    val log = Logger.getLogger(this.getClass)

    val cmdLineParser = new scopt.OptionParser[CmdLineConfig]("cosbench_ng") {
      head("Cosbench_ng", "1.0")

      opt[String]('m', "master")
        .valueName("<master_ip>")
        .required()
        .action((x, c) => c.copy(master = Some(x)))
        .text("See master console for the ip")

      opt[Int]('u', "debug")
        .action((x, c) => c.copy(localDebug = x))
        .validate({
          case (x) =>
            if (x != 1 && x != 2) failure("debug value has to be 1 or 2")
            else success
        })          
        .optional
        .text("optional, turn on debugging (1: info, 2: debug)")
        
        
      help("help").text("prints this text")

    }

    cmdLineParser.parse(a, CmdLineConfig()) match {
      case Some(config) =>
        log.debug("Parsed cmdline: " + config)
        Some(config)
      case None =>
        None
    }
  }

}