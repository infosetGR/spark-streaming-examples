
package co.infoset.sparkstreaming

import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.storage.StorageLevel

import java.util.regex.Pattern
import java.util.regex.Matcher

import Utilities._

import java.util.concurrent._
import java.util.concurrent.atomic._

/**c:\nmap-7.92\ncat.exe -kl 9999 < access_log.txt**/
/** Monitors a stream of Apache access logs on port 9999, and prints an alarm
 *  if an excessive ratio of errors is encountered.
 */
object LogAlarmer {
  
  def main(args: Array[String]) {

    var rate:Double=0
    var host:String="127.0.0.1"
    var port:Int = 9999
    var winlength:Int=30
    
    if (args.length > 2) {
      host = args(0)
      port = args(1).toInt
      winlength = args(2).toInt
    }
    
    var lastdata = java.lang.System.currentTimeMillis();
        
    // Create the context with a 1 second batch size
    val ssc = new StreamingContext("local[*]", "LogAlarmer", Seconds(1))
    
    setupLogging()
    
    // Construct a regular expression (regex) to extract fields from raw Apache log lines
    val pattern = apacheLogPattern()

    // Create a socket stream to read log data published via netcat on port 9999 locally
    val lines = ssc.socketTextStream(host, port, StorageLevel.MEMORY_AND_DISK_SER)
    
    // Extract the status field from each log line
    val statuses = lines.map(x => {
          val matcher:Matcher = pattern.matcher(x); 
          if (matcher.matches()) matcher.group(6) else "[error]"
        }
    )
    
    // Now map these status results to success and failure
    val successFailure = statuses.map(x => {
      val statusCode = util.Try(x.toInt) getOrElse 0
      if (statusCode >= 200 && statusCode < 300) {
        "Success"
      } else if (statusCode >= 500 && statusCode < 600) {
        "Failure"
      } else {
        "Other"
      }
    })
    
    // Tally up statuses over a 5-minute window sliding every second
    val statusCounts = successFailure.countByValueAndWindow(Seconds(winlength), Seconds(1))
    
    var alert = new AtomicInteger(0)
    
    // For each batch, get the RDD's representing data from our current window
    statusCounts.foreachRDD((rdd, time) => {
      // Keep track of total success and error codes from each RDD
      var totalSuccess:Long = 0
      var totalError:Long = 0

      if (rdd.count() > 0) {
        lastdata = java.lang.System.currentTimeMillis();
        
        val elements = rdd.collect()
        for (element <- elements) {
          val result = element._1
          val count = element._2
          if (result == "Success") {
            totalSuccess += count
          }
          if (result == "Failure") {
            totalError += count
          }
        }
      }
      
     if ( java.lang.System.currentTimeMillis()-lastdata>10000)
      {
        println(java.lang.System.currentTimeMillis()-lastdata);
         println("No data for sometime!!")
      }

      // Print totals from current window
      println("Total success: " + totalSuccess + " Total failure: " + totalError + " Alert: " + alert.get())
      
      // Don't alarm unless we have some minimum amount of data to work with
      if (totalError + totalSuccess > 100) {
        // Compute the error rate
        
        // Note use of util.Try to handle potential divide by zero exception
        val ratio:Double = util.Try( totalError.toDouble / totalSuccess.toDouble ) getOrElse 1.0
        // If there are more errors than successes, wake someone up
      
        if (ratio > 0.5 & alert.get()==0) {
          alert.getAndAdd(1)
          // In real life, you'd use JavaMail or Scala's courier library to send an
          // email that causes somebody's phone to make annoying noises, and you'd
          // make sure these alarms are only sent at most every half hour or something.
          println("Wake somebody up! Something is horribly wrong.")
  
        } else if (ratio < 0.5) {
          alert.set(0)
          println("All systems go.")
  
        }
      }
    })
    
    // Also in real life, you'd need to monitor the case of your site freezing entirely
    // and traffic stopping. In other words, don't use this script to monitor a real
    // production website! There's more you need.
    
    // Kick it off
    ssc.checkpoint("C:/checkpoint/")
    ssc.start()
    ssc.awaitTermination()
  }
}

