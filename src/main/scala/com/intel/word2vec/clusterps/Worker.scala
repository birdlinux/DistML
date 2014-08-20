package com.intel.word2vec.clusterps

import scala.collection.mutable._
import java.nio.ByteBuffer
import java.util.Date
import scala.collection.mutable
import java.net.Socket
import java.io.{ObjectOutputStream, DataOutputStream, DataInputStream}
import java.util
import com.intel.word2vec.common.{Utils, FloatOps}


/**
 * Created by He Yunlong on 7/12/14.
 *
 * One worker has two pools:
 *  current pool:  for training
 *  buffered pool: for param fetching and delta pushing
 *
 * The main loops are:
 *
 *  (1) training,  update parameters and delta  (current pool)
 *  (2) fetching   fetch parameters             (buffered pool)
 *  (3) pushing    push delta                   (buffered pool)
 *
 * when this loop done, exchange the pools:
 *  (1) unlink pool from word tree              (current pool)
 *  (2) reset "firstInvalid" to allow pushing   (current pool)
 *  (3) reset "firstFree" to allow fetching     (current pool)
 *  (4) clear "lines" to allow fetching update  (current pool)
 *  (5) change references
 *  (6) apply pool to current                   (current pool)
 *
 */
class Worker (
index : Int,
driver : String,
servers : Array[ServerInfo],
expTable: Array[Float],
w : WordTree,
wordMap : HashMap[String, Int],
totalWords : Long,
lines : Iterator[String],
startingAlpha : Float,
outputFolder : String
) {

//  Utils.debug("worker created: " + this + ", " + wordTree)

  val wordTree = w.clone()

  val FETCH_TRAIN_BULK_LINES = 200

  var currentPool = new W2VWorkerPool("pool A")
  var bufferedPool = new W2VWorkerPool("pool B")

  var fetchIndexList = new LinkedList[Int]
  var fetchers = new ListBuffer[Fetcher]
  var fetchRunnable = new FetchRunnable()

  var trainRunnable = new TrainRunnable()

  var pushers = new ListBuffer[Pusher]
  var pushRunnable = new PushRunnable()

  var fetchedLines = 0L
  var trainedWords = 0L
  var reporter = new Reporter()

  var workDone = false

  def init() {
    for (s <- servers) {
      fetchers += new Fetcher(index, s, this)
      pushers += new Pusher(s, this)
    }

    workDone = false
  }


  def workNow() {
    for (p <- pushers) {
      p.start()
    }
    for (f <- fetchers) {
      f.start()
    }
    reporter.start()

    for (p <- pushers) {
      while(!p.running) {
        Thread.sleep(10)
      }
    }
    for (f <- fetchers) {
      while(!f.running) {
        Thread.sleep(10)
      }
    }

    fetchedLines = 1
    while(fetchedLines > 0) {
      trainAndFetch()
      exchangePool()
    }

    println("traing done, exiting...")
    workDone = true
    reporter.join()
    for (p <- pushers) {
      p.stopWork()
      p.join()
    }
    for (f <- fetchers) {
      f.stopWork()
      f.join()
    }
    println("job finished, worker is going to stop")
  }

  def exchangePool() {
    println("exchangePool started: " + this)

    currentPool.clear()

    var t = bufferedPool
    bufferedPool = currentPool
    currentPool = t

    currentPool.apply()
    println("exchangePool done: " + this)
  }

  def trainAndFetch() {
    trainedWords = 0L

    var t1 = new Thread(fetchRunnable)
    var t2 = new Thread(trainRunnable)
    var t3 = new Thread(pushRunnable)
    println("train and fetch started: " + this + ", " + t1 + ", " + t2 + ", " + t3)
    t1.start()
    t2.start()
    t3.start()

    t1.join()
    t2.join()
    t3.join()
    reporter.report(trainedWords)
    println("train and fetch done: " + this + ", " + t1 + ", " + t2 + ", " + t3)
  }

  class TrainRunnable() extends Runnable {

    final val EXP_TABLE_SIZE: Int = 1000
    final val MAX_EXP: Int = 6

    val sample: Double = 1e-3
    var windowSize: Int = 5
    var vectorSize: Int = Constants.MODEL_DIMENSION

    var nextRandom: Long = 5
    var alphaThreshold: Float = 0.0001f

    val partitionIndex = index

    var neu1e = new Array[Float](Constants.MODEL_DIMENSION)
    var neu1eBuf: ByteBuffer = null

    def skipGram(index: Int,
                 sentence: MutableList[WordNode],
                 b: Int) {

      val word = sentence(index)
      var a: Int = 0
      var c: Int = 0
      var tmp = 0.0f

      for (a <- b to windowSize * 2 - b) {
        c = index - windowSize + a
        if ((a != windowSize) && (c >= 0 && c < sentence.size)) {

          val lastWord = sentence(c).data.asInstanceOf[W2VWorkerNodeData]

          for (c <- 0 to vectorSize-1) {
            neu1e(c) = 0.0f
          }

          for (d <- 0 to word.codeLen-1) {
            val out = wordTree.getWord(word.point(d)).data.asInstanceOf[W2VWorkerNodeData]
            if (lastWord == null)
              Utils.debug("" + Worker.this + ". data fail: " + sentence(c).index)
            if (out == null)
              Utils.debug("" + Worker.this + ". data fail: " + word.point(d))

            var f: Float = 0

            for (j <- 0 to vectorSize-1) {
              f += lastWord.syn0(j) * out.syn1(j)
            }

            if (f > -MAX_EXP && f < MAX_EXP) {
              f = (f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2)
              f = expTable(f.asInstanceOf[Int])
              var g = 1.0f - word.code(d) - f

              for (c <- 0 to vectorSize-1) {
                neu1e(c) += g * out.syn1(c)
              }

              for (c <- 0 to vectorSize-1) {
                tmp = g * lastWord.syn0(c) * out.alpha1(c)
                out.syn1(c) += tmp
                out.delta1(c) += tmp
              }
            }
          }

          for (j <- 0 to vectorSize-1) {
            tmp = neu1e(j) * lastWord.alpha0(j)
            lastWord.syn0(j) += tmp
            lastWord.delta0(j) += tmp
          }
        }
      }
    }


    override def run() {
      var startTime= System.currentTimeMillis()

//      currentPool.deltaList.clear()
      println("train thread started")

      for (line <- currentPool.lines) {
        //println("train with line: " + line)
        val sentence = new mutable.MutableList[WordNode]
        var tokens = line.split(" ").filter( s => s.length > 0)
        for (token <- tokens) {
          var indexer = wordMap.get(token)
          if (!indexer.isEmpty) {
            val entryIndex = wordMap.get(token).get
            if (entryIndex != -1) {
              val entry = wordTree.getWord(entryIndex)
              if (entry != null) {
                  //println("train entry index: " + entryIndex)
                  sentence += entry
                  trainedWords += 1
              }
            }
          }
        }
        //trainedWords += tokens.size

        for (sentence_pos <- 0 to sentence.size - 1) {
          nextRandom = nextRandom * 25214903917L + 11
          var b = (nextRandom % windowSize).toInt
          //var b = 3
          skipGram(sentence_pos, sentence, b)
        }
      }

      val cost = System.currentTimeMillis() - startTime
      println("train thread done, trained lines: " + currentPool.lines.size + ", trained words: " + trainedWords
              + ", time: " + cost/1000.0f)
    }
/*
    def addDletaToPusher(d : DeltaData) {
      var added = false
      var pusherIndex = 0
      while ((!added) && (pusherIndex < pushers.size)) {
        var f = pushers(pusherIndex)
        if (f.addIndex(d)) {
          added = true
        }
        pusherIndex += 1
      }

      added
    }
*/
  }

  class FetchRunnable extends Runnable {
    override def run()  {
      //println("fetch thread started")
      var startTime= System.currentTimeMillis()

      fetchedLines = 0L
      while ((fetchedLines < FETCH_TRAIN_BULK_LINES) && lines.hasNext) {
        val line = lines.next()
        bufferedPool.lines += line

        val sentence = new MutableList[Int]
        var tokens = line.split(" ").filter( s => s.length > 0)
        for (token <- tokens) {
          var indexer = wordMap.get(token)
          if (!indexer.isEmpty) {
            val entryIndex = wordMap.get(token).get
            if (entryIndex != -1) {
              addIndextoFetch(entryIndex)

              var w = wordTree.getWord(entryIndex)
              for (d <- 0 to w.codeLen-1) {
                addIndextoFetch(w.point(d))
              }

            }
          }
        }
        fetchedLines += 1
      }

      //println("starting fetchers to fetch data, total words: " + wordCount)
      var wordCount = 0L
      for (f <- fetchers) {
        wordCount += f.queue.size
        f.startFetch()
      }

      for (f <- fetchers) {
        while(!f.idle) {
          Thread.sleep(10)
        }
        //println("fetcher is not idle now: " + f.server.address + ", " + f.idle)
      }
      val cost = System.currentTimeMillis() - startTime
      println("fetch thread done, fetched lines: " + fetchedLines + ", fetched words: " + wordCount
        + ", time: " + cost/1000.0f)


    }
  }

  class PushRunnable extends Runnable {
    override def run()  {
      //println("push thread started")
      var startTime= System.currentTimeMillis()

      var d = bufferedPool.dataPool
      while (d != bufferedPool.firstInvalidData) {
        var added = false
        var serverIndex = 0
        while ((!added) && (serverIndex < fetchers.size)) {
          var p = pushers(serverIndex)
          added = p.addIndex(d)
          serverIndex += 1
        }

        d = d.next
      }

      var count = 0L
      for (p <- pushers) {
        count += p.queue.size
        p.startPush()
      }

      for (p <- pushers) {
        while(!p.idle) {
          Thread.sleep(10)
        }
      }

      val cost = System.currentTimeMillis() - startTime
      println("push done, pushed count: " + count + ", time: " + cost/1000.0f)
    }
  }

  def addIndextoFetch(index : Int) {
    //println("fectch: " + index)
    var added = false
    var serverIndex = 0
    while ((!added) && (serverIndex < fetchers.size)) {
      var f = fetchers(serverIndex)
      added = f.addIndex(index)
      serverIndex += 1
    }
  }

  def getFreeData() : WordNodeData = {
    return bufferedPool.getFreeData()
  }

  class W2VWorkerPool(name : String) {
    var dataPool : W2VWorkerNodeData = null
    var poolTail : W2VWorkerNodeData = null

    var firstFreeData : W2VWorkerNodeData = null     // used to indicate from where to append fetched data
    var firstInvalidData : W2VWorkerNodeData = null  // used to indicate stop for delta pushing

    var lines = new ListBuffer[String]

    // we don't use delta list, because it's too long when training
    //var deltaList = new Queue[DeltaData]()

    def clear() {
      //println("clearing " + name)
      var d = dataPool
      var w : WordNode = null

      //print("" + Worker.this + " clear word: ")
      while ((d != null) && (d != firstFreeData)) {
        //print(" " + d.index)
        w = wordTree.getWord(d.index)
        w.data = null
        d.deltaIndex = d.index
        d.index = -1
        d = d.next
      }
      //println
      firstInvalidData = firstFreeData
      firstFreeData = dataPool
      lines.clear()
    }

    def apply() {
      //println("applying " + name)
      var d = dataPool
      var w : WordNode = null

      //print("" + Worker.this + " apply word: ")
      while ((d != null) && (d != firstFreeData)) {
        //print(" " + d.index)
        w = wordTree.getWord(d.index)
        w.data = d
        for (i <- 0 to Constants.MODEL_DIMENSION-1) {
          d.delta0(i) = 0.0f
          d.delta1(i) = 0.0f
        }
        d = d.next
      }
      //println("")
    }

    def getFreeData() : W2VWorkerNodeData = { this.synchronized{
      if (firstFreeData == null) {
        var d = new W2VWorkerNodeData(Constants.MODEL_DIMENSION)
        if (poolTail == null) {
          dataPool = d
          poolTail = d
        }
        else {
          poolTail.next = d
          poolTail = d
        }
        return d
      }

      var d = firstFreeData
      firstFreeData = firstFreeData.next
      return d
    }}

  }


  class Reporter extends Thread {
    var trained = 0L

    def report(t : Long) {
      trained = t
    }

    override def run()  {
      val socket = new Socket(driver, Constants.MONITOR_PORT)
      //var dis = new DataInputStream(socket.getInputStream())
      var dos = new DataOutputStream(socket.getOutputStream())
      dos.writeInt(Constants.NODE_TYPE_WORKER)
      dos.writeInt(index)

      while(!workDone) {
        if (trained > 0L) {
          dos.writeLong(trained)
          trained = 0L
        }
        else {
          Thread.sleep(100)
        }
      }

      if (trained > 0L) {
        dos.writeLong(trained)
        trained = 0L
      }

      dos.writeLong(-1)  // work done indicator

      dos.close()
      socket.close()
    }
  }
}
