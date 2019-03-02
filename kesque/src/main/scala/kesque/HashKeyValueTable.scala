package kesque

import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.locks.ReentrantReadWriteLock
import kafka.server.LogAppendResult
import kafka.utils.Logging
import org.apache.kafka.common.record.CompressionType
import org.apache.kafka.common.record.SimpleRecord
import scala.collection.mutable

object HashKeyValueTable {
  val fetchMaxBytesInLoadOffsets = 100 * 1024 * 1024 // 100M
  val defaultFetchMaxBytes = 4 * 1024 // 4K the size of SSD block

  def intToBytes(v: Int) = ByteBuffer.allocate(4).putInt(v).array
  def bytesToInt(v: Array[Byte]) = ByteBuffer.wrap(v).getInt

  // --- methods for transforming offsets for 4 log files (file #0 to file #3)

  // 0x00000000 - 0000 0000 0000 0000 0000 0000 0000 0000
  // 0x40000000 - 0100 0000 0000 0000 0000 0000 0000 0000
  // 0x80000000 - 1000 0000 0000 0000 0000 0000 0000 0000
  // 0xC0000000 - 1100 0000 0000 0000 0000 0000 0000 0000
  private val filenoToBitsHeader = Array(0x00000000, 0x80000000)
  private def toMixedOffset(fileno: Int, offset: Int) = {
    val bitsHeader = filenoToBitsHeader(fileno)
    bitsHeader | offset
  }

  private def toFileNoAndOffset(mixedOffset: Int) = {
    val fileno = mixedOffset >>> 31
    val offset = mixedOffset & 0x7FFFFFFF // 0111 1111 1111 1111 1111 1111 1111 1111
    (fileno, offset)
  }
}

/**
 * We use this table to read/write combination of snapshot (in file 0) and post-
 * events. The offsets in caches and hashOffsets(index) are with 1st bit as the
 * fileNo, where, the 0 1st bit indicates that the offset is of file 0, and the
 * 1 1st bit indicates that the offset is of file 1.
 * @see toFileOffset and toOffset
 */
final class HashKeyValueTable private[kesque] (
    topics:          Array[String],
    db:              Kesque,
    withTimeToKey:   Boolean,
    fetchMaxBytes:   Int             = HashKeyValueTable.defaultFetchMaxBytes,
    compressionType: CompressionType = CompressionType.NONE,
    cacheSize:       Int             = 10000
) extends Logging {
  import HashKeyValueTable._

  private val hashOffsets = new HashOffsets(200, topics.length)

  /* time to key table, should be the first topic to initially create it */
  private var timeIndex = Array.ofDim[Array[Byte]](200)

  private val caches = Array.ofDim[FIFOCache[Hash, (TVal, Int)]](topics.length)
  private val (topicToCol, _) = topics.foldLeft(Map[String, Int](), 0) {
    case ((map, i), topic) => (map + (topic -> i), i + 1)
  }

  private def indexTopic(topic: String) = topic + "_idx"
  private def postTopic(topic: String) = topic + "~"

  private val postTopics = topics map postTopic
  private val indexTopics = topics map indexTopic
  private val postIndexTopics = postTopics map indexTopic

  private val topicsOfFileno = Array(topics, postTopics)
  private val indexTopicsOfFileno = Array(indexTopics, postIndexTopics)

  private val lock = new ReentrantReadWriteLock()
  private val readLock = lock.readLock
  private val writeLock = lock.writeLock

  private class LoadIndexesTask(col: Int, topic: String) extends Thread {
    override def run() {
      loadOffsets(col)
    }
  }

  loadIndexes()

  private def loadIndexes() {
    var tasks = List[Thread]()
    var n = 0
    while (n < topics.length) {
      val topic = topics(n)
      caches(n) = new FIFOCache[Hash, (TVal, Int)](cacheSize)

      tasks = new LoadIndexesTask(n, topic) :: tasks

      n += 1
    }

    val timeIndexTask = if (withTimeToKey) {
      List(new Thread() {
        override def run() {
          loadTimeIndex()
        }
      })
    } else {
      Nil
    }

    timeIndexTask ::: tasks foreach { _.start() }
    timeIndexTask ::: tasks foreach { _.join() }
  }

  private def loadOffsets(col: Int) {
    info(s"Loading index of ${topics(col)}")
    val start = System.nanoTime

    val initCounts = Array.fill[Int](indexTopicsOfFileno.length)(0)
    val (_, counts) = indexTopicsOfFileno.foldLeft(0, initCounts) {
      case ((fileno, counts), idxTps) =>
        db.iterateOver(idxTps(col), 0, fetchMaxBytesInLoadOffsets) {
          case (offset, TKeyVal(hash, recordOffset, timestamp)) =>
            if (hash != null && recordOffset != null) {
              hashOffsets.put(bytesToInt(hash), toMixedOffset(fileno, bytesToInt(recordOffset)), col)
              counts(fileno) += 1
            }
        }
        (fileno + 1, counts)
    }

    info(s"Loaded index of ${topics(col)} in ${(System.nanoTime - start) / 1000000} ms, count ${counts.mkString("(", ",", ")")}, size ${hashOffsets.size}")
  }

  private def loadTimeIndex() {
    info(s"Loading time index from ${topics(0)}")
    val start = System.nanoTime

    var count = 0
    topicsOfFileno foreach { tps =>
      db.iterateOver(tps(0), 0, fetchMaxBytesInLoadOffsets) {
        case (offset, TKeyVal(key, value, timestamp)) =>
          if (key != null && value != null) {
            putTimeToKey(timestamp, key)
            count += 1
          }
      }
    }

    info(s"Loaded time index from ${topics(0)} in ${(System.nanoTime - start) / 1000000} ms, count $count")
  }

  def putTimeToKey(timestamp: Long, key: Array[Byte]) {
    try {
      writeLock.lock()

      if (timestamp > timeIndex.length - 1) {
        val newArr = Array.ofDim[Array[Byte]]((timeIndex.length * 1.2).toInt)
        System.arraycopy(timeIndex, 0, newArr, 0, timeIndex.length)
        timeIndex = newArr
      }
      timeIndex(timestamp.toInt) = key
    } finally {
      writeLock.unlock()
    }
  }

  def getKeyByTime(timestamp: Long): Option[Array[Byte]] = {
    try {
      readLock.lock()

      if (!withTimeToKey) {
        None
      } else {
        if (timestamp >= 0 && timestamp < timeIndex.length) {
          Option(timeIndex(timestamp.toInt))
        } else {
          None
        }
      }
    } finally {
      readLock.unlock()
    }
  }

  def read(keyBytes: Array[Byte], topic: String, bypassCache: Boolean = false): Option[TVal] = {
    try {
      readLock.lock

      val col = topicToCol(topic)
      val key = Hash(keyBytes)
      caches(col).get(key) match {
        case None =>
          val hash = key.hashCode
          hashOffsets.get(hash, col) match {
            case IntIntsMap.NO_VALUE => None
            case offsets =>
              var foundValue: Option[TVal] = None
              var foundOffset = Int.MinValue
              var i = offsets.length - 1 // loop backward to find the newest one  
              while (i >= 0 && foundValue.isEmpty) {
                val fileOffset = offsets(i)
                val (fileno, offset) = toFileNoAndOffset(fileOffset)
                val theTopic = topicsOfFileno(fileno)(col)
                val (topicPartition, result) = db.read(theTopic, offset, fetchMaxBytes).head
                val recs = result.info.records.records.iterator
                // NOTE: the records usally do not start from the fecth-offset, 
                // the expected record may be near the tail of recs
                //debug(s"======== $offset ${result.info.fetchOffsetMetadata} ")
                while (recs.hasNext) {
                  val rec = recs.next
                  //debug(s"${rec.offset}")
                  if (rec.offset == offset && Arrays.equals(kesque.getBytes(rec.key), keyBytes)) {
                    foundOffset = offset
                    foundValue = if (rec.hasValue) Some(TVal(kesque.getBytes(rec.value), rec.timestamp)) else None
                  }
                }
                i -= 1
              }

              if (!bypassCache) {
                foundValue foreach { tv =>
                  caches(col).put(key, (tv, foundOffset))
                }
              }

              foundValue
          }
        case Some((value, offset)) => Some(value)
      }
    } finally {
      readLock.unlock()
    }
  }

  def writePost(kvs: Iterable[TKeyVal], topic: String) = write(kvs, topic, fileno = 1)
  def writeSnap(kvs: Iterable[TKeyVal], topic: String) = write(kvs, topic, fileno = 0)
  def write(kvs: Iterable[TKeyVal], topic: String): Vector[Iterable[Int]] = write(kvs, topic, fileno = 0)
  private def write(kvs: Iterable[TKeyVal], topic: String, fileno: Int): Vector[Iterable[Int]] = {
    val col = topicToCol(topic)

    // prepare simple records, filter no changed ones
    var recordBatches = Vector[(List[TKeyVal], List[SimpleRecord], Map[Hash, Int])]()
    var tkvs = List[TKeyVal]()
    var records = List[SimpleRecord]()
    var keyToPrevOffsets = Map[Hash, Int]()
    val itr = kvs.iterator
    while (itr.hasNext) {
      val tkv @ TKeyVal(keyBytes, value, timestamp) = itr.next()
      val key = Hash(keyBytes)
      caches(col).get(key) match {
        case Some((TVal(prevValue, _), prevOffset)) =>
          if (isValueChanged(value, prevValue)) {
            val rec = if (timestamp < 0) new SimpleRecord(keyBytes, value) else new SimpleRecord(timestamp, keyBytes, value)
            tkvs ::= tkv
            records ::= rec
            keyToPrevOffsets += key -> prevOffset
            // TODO should only happen when value is set to empty, i.e. removed?
            // remove records of prevOffset from memory?
          } else {
            debug(s"$topic: value not changed. cache: hit ${caches(col).hitRate}, miss ${caches(col).missRate}}")
          }
        case None =>
          val rec = if (timestamp < 0) new SimpleRecord(keyBytes, value) else new SimpleRecord(timestamp, keyBytes, value)
          tkvs ::= tkv
          records ::= rec
      }
    }

    if (records.nonEmpty) {
      recordBatches :+= (tkvs, records, keyToPrevOffsets)
    }

    debug(s"${recordBatches.map(x => x._1.size).mkString(",")}")

    // write to log file
    recordBatches map { case (tkvs, records, keyToPrevOffsets) => writeRecords(tkvs, records, keyToPrevOffsets, col, fileno) }
  }

  private def writeRecords(tkvs: List[TKeyVal], records: List[SimpleRecord], keyToPrevOffsets: Map[Hash, Int], col: Int, fileno: Int): Iterable[Int] = {
    try {
      writeLock.lock()

      val topic = topicsOfFileno(fileno)(col)
      // write simple records and create index records
      val indexRecords = db.write(topic, records, compressionType).foldLeft(Vector[Vector[SimpleRecord]]()) {
        case (indexRecords, (topicPartition, LogAppendResult(appendInfo, Some(ex)))) =>
          error(ex.getMessage, ex) // TODO
          indexRecords

        case (indexRecords, (topicPartition, LogAppendResult(appendInfo, None))) =>
          if (appendInfo.numMessages > 0) {
            val firstOffert = appendInfo.firstOffset.get
            val (lastOffset, idxRecords) = tkvs.foldLeft(firstOffert, Vector[SimpleRecord]()) {
              case ((offset, idxRecords), TKeyVal(keyBytes, value, timestamp)) =>
                val key = Hash(keyBytes)
                val hash = key.hashCode
                val indexRecord = new SimpleRecord(intToBytes(hash), intToBytes(offset.toInt))

                val mixedOffset = toMixedOffset(fileno, offset.toInt)
                keyToPrevOffsets.get(key) match {
                  case Some(prevOffset) => // there is prevOffset, will also remove it (replace it with current one)
                    hashOffsets.replace(hash, prevOffset, mixedOffset, col)
                  case None => // there is none prevOffset
                    hashOffsets.put(hash, mixedOffset, col)
                }
                caches(col).put(key, (TVal(value, timestamp), mixedOffset))
                (offset + 1, idxRecords :+ indexRecord)
            }

            assert(appendInfo.lastOffset == lastOffset - 1, s"lastOffset(${appendInfo.lastOffset}) != ${lastOffset - 1}, firstOffset is ${appendInfo.firstOffset}, numOfMessages is ${appendInfo.numMessages}, numRecords is ${records.size}, appendInfo: $appendInfo")

            indexRecords :+ idxRecords
          } else {
            indexRecords
          }
      }

      // write index records
      val theIndexTopic = indexTopicsOfFileno(fileno)(col)
      db.write(theIndexTopic, indexRecords.flatten, compressionType) map {
        case (topicPartition, LogAppendResult(appendInfo, Some(ex))) =>
          error(ex.getMessage, ex) // TODO
        case (topicPartition, LogAppendResult(appendInfo, None)) =>
          debug(s"$topic: append index records ${indexRecords.size}")
      }

      indexRecords.map(_.size)
    } finally {
      writeLock.unlock()
    }
  }

  private def isValueChanged(v1: Array[Byte], v2: Array[Byte]) = {
    if ((v1 eq null) && (v2 eq null)) {
      false
    } else if ((v1 eq null) || (v2 eq null)) {
      true
    } else {
      !Arrays.equals(v1, v2)
    }
  }

  def remove(keys: Seq[Array[Byte]], topic: String): Iterable[Int] = {
    try {
      writeLock.lock()

      val col = topicToCol(topic)
      caches(col).remove(keys.map(Hash(_)))

      // create simple records
      val records = keys map { key => key -> new SimpleRecord(key, null) }

      // write simple records and create index records
      val indexRecords = db.write(topic, records.map(_._2), compressionType).foldLeft(Vector[Vector[SimpleRecord]]()) {
        case (indexRecords, (topicPartition, LogAppendResult(appendInfo, Some(ex)))) =>
          error(ex.getMessage, ex) // TODO
          indexRecords

        case (indexRecords, (topicPartition, LogAppendResult(appendInfo, None))) =>
          if (appendInfo.numMessages > 0) {
            val firstOffert = appendInfo.firstOffset.get
            val (lastOffset, idxRecords) = records.foldLeft(firstOffert, Vector[SimpleRecord]()) {
              case ((offset, idxRecords), (key, _)) =>
                val keyh = Hash(key)
                val hash = keyh.hashCode
                val indexRecord = new SimpleRecord(intToBytes(hash), intToBytes(offset.toInt))

                // remove action always happens on file 1
                val mixedOffset = toMixedOffset(1, offset.toInt)
                hashOffsets.put(hash, mixedOffset, col)
                // should also find and remove previous offsets? it will cost 
                // a i/o reading. Not necessary or do it in write
                (offset + 1, idxRecords :+ indexRecord)
            }

            assert(appendInfo.lastOffset == lastOffset - 1, s"lastOffset(${appendInfo.lastOffset}) != ${lastOffset - 1}, firstOffset is ${appendInfo.firstOffset}, numOfMessages is ${appendInfo.numMessages}, numRecords is ${keys.size}, appendInfo: $appendInfo")

            indexRecords :+ idxRecords
          } else {
            indexRecords
          }
      }

      // write index records
      db.write(indexTopics(col), indexRecords.flatten, compressionType) map {
        case (topicPartition, LogAppendResult(appendInfo, Some(ex))) =>
          error(ex.getMessage, ex) // TODO
        case (topicPartition, LogAppendResult(appendInfo, None)) =>
          debug(s"$topic: append index records $indexRecords")
      }

      indexRecords.map(_.size)
    } finally {
      writeLock.unlock()
    }
  }

  def iterateOver(fetchOffset: Long, topic: String)(op: (Long, TKeyVal) => Unit) = {
    try {
      readLock.lock()

      db.iterateOver(topic, fetchOffset, fetchMaxBytes)(op)
    } finally {
      readLock.unlock()
    }
  }

  def readOnce(fetchOffset: Long, topic: String)(op: (Long, TKeyVal) => Unit) = {
    try {
      readLock.lock()

      db.readOnce(topic, fetchOffset, fetchMaxBytes)(op)
    } finally {
      readLock.unlock()
    }
  }

  def cacheHitRate(topic: String) = caches(topicToCol(topic)).hitRate
  def cacheReadCount(topic: String) = caches(topicToCol(topic)).readCount
  def resetCacheHitRate(topic: String) = caches(topicToCol(topic)).resetHitRate()
}
