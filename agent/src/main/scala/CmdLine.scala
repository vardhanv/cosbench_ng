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
  debug : Boolean = false
)

object CmdLineParser {

  def parseCmdLine(a: Array[String]): Option[CmdLineConfig] = {

    val log = Logger.getLogger(this.getClass)

    val cmdLineParser = new scopt.OptionParser[CmdLineConfig]("cosbench_ng") {
      head("Cosbench_ng", "1.0")

      opt[String]('m', "master")
        .required()
        .action((x, c) => c.copy(master = Some(x)))
        .text("\"akka://master_ip:port\". See master console for text with \"Cosbench_ng master UP at: akka://\" ")

      opt[Unit]('d', "debug")
        .action((_, c) => c.copy(debug = true))
        .optional
        .text("optional, turn on debugging. Logs are in /tmp/cosbench_ng")
        
        
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