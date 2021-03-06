package de.bankmark.pdgf

import javassist.{ClassClassPath, ClassPool}
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import pdgf.ui.cli.Controller

object SparkExec {

  /** Method that just returns the current active/registered executors
   * excluding the driver.
   * @param sc The spark context to retrieve registered executors.
   * @return a list of executors each in the form of host:port.
   */
  def currentActiveExecutors(sc: SparkContext, includeDriver: Boolean = true): Seq[String] = {
    val allExecutors = sc.getExecutorMemoryStatus.keys
    val driverHost = sc.getConf.get("spark.driver.host");
    if (includeDriver) {
      allExecutors.toSeq
    } else {
      allExecutors.filter(!_.split(":")(0).equals(driverHost)).toSeq
    }
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("PDGFSparkExec")
      .getOrCreate()
    val sparkContext = spark.sparkContext

    // get the current active executors, these are the nodes used by pdgf
    val executors = currentActiveExecutors(sparkContext, true)
    val numCoresPerExecutor = sparkContext.getConf.getInt("spark.executor.cores", -1)
    val numNodes = executors.length
    println(s"found $numNodes executors:")
    println(executors.mkString(","))

    // create an RDD with #`numNodes` elements
    val rdd = sparkContext.parallelize(executors, numNodes)
    // on each partition run a pdgf instance and set the number of workers, i.e. the threads spawned by pdgf accordingly
    val datagen = rdd.zipWithIndex().map(executorWithIdx => {
      val executor = executorWithIdx._1
      val executorNum = executorWithIdx._2 + 1

      // make the PDGF parameters for distributed data generation
      val workers =  if (numCoresPerExecutor > 0) numCoresPerExecutor else Runtime.getRuntime.availableProcessors()
      val pdgfDistributedArgs = s"-nn $executorNum -nc $numNodes -w $workers"
      val pdgfArgs = args ++ pdgfDistributedArgs.split(" ")

      val prettyArgs = pdgfArgs.map("\"" + _ + "\"").mkString("Array(", ",", ")")
      println(s"run pdgf on $executor: Controller.main(${prettyArgs})")
      // add PDGF to javassist classpool
      // when running on Spark, PDGF is not part of the system classpath but the Spark classpath
      // hence its classes cannot be found by javassist
      ClassPool.getDefault.appendClassPath(new ClassClassPath(classOf[Controller]))

      // call pdgf with the command line args + the distributed args
      Controller.main(pdgfArgs)
    })

    // run the mappers
    val results = datagen.collect()
    println("RESULTS")
    results.foreach(println)
  }
}