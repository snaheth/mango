/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.mango.layout

import htsjdk.samtools.{ Cigar, CigarOperator, CigarElement, TextCigarCodec }
import org.apache.spark.Logging
import org.bdgenomics.adam.models.ReferenceRegion
import org.bdgenomics.formats.avro.{ AlignmentRecord, Contig }
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object MismatchLayout extends Logging {
  /**
   * An implementation of MismatchLayout which takes in an alignmentRecord, reference and region
   * and finds all indels and mismatches
   *
   * @param record: AlignmentRecord
   * @param reference: reference string used to calculate mismatches
   * @param region: ReferenceRegion to be viewed
   * @return List of MisMatches
   */
  def apply(record: AlignmentRecord, reference: String, region: ReferenceRegion): List[MisMatch] = {
    alignMismatchesToRead(record, reference, region)
  }

  /**
   * An implementation of AlignmentRecordLayout which takes in an Iterator of (ReferenceRegion, AlignmentRecord) tuples, the reference String
   * over the region, and the region viewed.
   *
   * @param iter: Iterator of (ReferenceRegion, AlignmentRecord) tuples
   * @param reference: reference string used to calculate mismatches
   * @param region: ReferenceRegion to be viewed
   * @return Iterator of Read Tracks containing json for reads, mismatches and mate pairs
   */
  def apply(iter: Iterator[(ReferenceRegion, AlignmentRecord)], reference: String, region: ReferenceRegion): Iterator[(String, List[MisMatch])] = {
    val alignments: List[AlignmentRecord] = iter.toList.map(_._2)
    // get all mismatches for each read
    alignments.map(r => (r.getRecordGroupSample, MismatchLayout(r, reference, region))).toIterator
  }

  /**
   * Finds and returns all indels and mismatches of a given alignment record from an overlapping reference string.
   * Must take into account overlapping regions that are not covered by both the reference and record sequence.
   *
   * @param rec: AlignmentRecord
   * @param ref: reference string used to calculate mismatches
   * @param region: ReferenceRegion to be viewed
   * @return List of MisMatches
   */
  def alignMismatchesToRead(rec: AlignmentRecord, ref: String, region: ReferenceRegion): List[MisMatch] = {

    val regionSize = region.end - region.start

    var misMatches: ListBuffer[MisMatch] = new ListBuffer[MisMatch]()

    if (rec.getReadNegativeStrand == true) {
      return misMatches.toList
    }

    val cigar = TextCigarCodec.decode(rec.getCigar).getCigarElements()

    var refIdx = 0L
    var recIdx = 0L

    // calculate start value
    if (rec.getStart >= region.start) {
      refIdx = rec.getStart + 1
      recIdx = rec.getStart
    } else {
      refIdx = region.start + 1
      recIdx = region.start
    }

    cigar.foreach {
      e =>
        {
          var misLen = 0
          var op: CigarOperator = null
          var refBase: Char = 'M'
          var recBase: Char = 'M'
          try {
            misLen = e.getLength
            op = e.getOperator
            recBase = rec.getSequence.charAt(getPosition(recIdx, rec.getStart))
            refBase = ref.charAt(getPosition(refIdx, region.start))
          } catch {
            case e: Exception => misMatches.toList
          }
          if (op == CigarOperator.X || op == CigarOperator.M) {
            try {
              for (i <- 0 to misLen - 1) {
                // if index position is not within region
                if (refIdx <= region.end && refIdx >= region.start) {
                  val recBase = rec.getSequence.charAt(getPosition(recIdx, rec.getStart))
                  val refBase = ref.charAt(getPosition(refIdx, region.start))
                  if (refBase != recBase) {
                    val start = recIdx
                    misMatches += new MisMatch(op.toString, refIdx, start, start + 1, recBase.toString, refBase.toString)
                  }
                }
                recIdx += 1
                refIdx += 1
              }
            } catch {
              case e: Exception => log.warn(e.toString)
            }
          } else if (op == CigarOperator.I) {
            try {
              val end = recIdx + misLen
              val stringStart = (recIdx - rec.getStart).toInt
              val indel = rec.getSequence.substring(stringStart, stringStart + misLen)
              misMatches += new MisMatch(op.toString, refIdx, recIdx, end, indel, null)
              recIdx += misLen
            } catch {
              case e: Exception => log.warn(e.toString)
            }
          } else if (op == CigarOperator.D || op == CigarOperator.N) {
            val end = recIdx + misLen
            val stringStart = getPosition(recIdx, rec.getStart)
            val indel = rec.getSequence.substring(stringStart, stringStart + misLen)
            misMatches += new MisMatch(op.toString, refIdx, recIdx, end, indel, null)
            refIdx += misLen
          } else if (op == CigarOperator.S) {
            recIdx += misLen
          }

        }
    }
    misMatches.toList
  }

  /**
   * Determines weather a given AlignmentRecord contains indels using its cigar
   *
   * @param rec: AlignmentRecord
   * @return Boolean whether record contains any indels
   */
  def containsIndels(rec: AlignmentRecord): Boolean = {
    rec.getCigar.contains("I") || rec.getCigar.contains("D")
  }

  /**
   * Calculates the genetic complement of a strand
   *
   * @param sequence: genetic string
   * @return String: complement of sequence
   */
  def complement(sequence: String): String = {
    sequence.map {
      case 'A' => 'T'
      case 'T' => 'A'
      case 'C' => 'G'
      case 'G' => 'C'
      case 'W' | 'S' | 'Y' | 'R' | 'M' | 'K' | 'B' | 'D' | 'V' | 'H' | 'N' => 'N'
      case _ => 'N'
    }
  }

  private def getPosition(idx: Long, start: Long): Int = (idx - start).toInt
}

object MisMatchJson {

  /**
   * An implementation of MismatchJson which converts a list of Mismatches into MisMatch Json
   *
   * @param recs The list of MisMatches to lay out in json
   * @param track js track number
   * @return List of MisMatch Json objects
   */
  def apply(recs: List[MisMatch], track: Int): List[MisMatchJson] = {
    recs.map(rec => MisMatchJson(rec, track))
  }

  /**
   * An implementation of MismatchJson which converts a single Mismatch into MisMatch Json
   *
   * @param rec The single MisMatch to lay out in json
   * @param track js track number
   * @return List of MisMatch Json objects
   */
  def apply(rec: MisMatch, track: Int): MisMatchJson = {
    new MisMatchJson(rec.op, rec.refCurr, rec.start, rec.end, rec.sequence, rec.refBase, track)
  }
}

object PointMisMatch {

  /**
   * aggregated point mismatch at a specific location
   *
   * @param mismatches: List of mismatches to be grouped by start value
   * @return List of aggregated mismatches and their corresponding counts
   */
  def apply(mismatches: List[MisMatch]): List[MutationCount] = {
    val grouped = mismatches.groupBy(_.start)
    grouped.mapValues(reducePoints(_)).values.toList
  }

  /**
   * aggregated point mismatch at a specific location
   *
   * @param mismatches: List of mismatches to be grouped by start value
   * @return aggregated mismatches and their corresponding counts
   */
  private def reducePoints(mismatches: List[MisMatch]): MutationCount = {

    val refCurr = mismatches.head.refCurr
    val refBase = mismatches.head.refBase // TODO: this will cause an issue if you have an indel and mismatch at the same location
    val start = mismatches.head.start
    val end = mismatches.head.end // TODO: this may vary

    // count each occurrence of a mismatch
    val ms: Map[String, Long] = mismatches.filter(_.op == "M").map(r => (r.sequence, 1L))
      .groupBy(_._1)
      .map { case (group: String, traversable) => traversable.reduce { (a, b) => (a._1, a._2 + b._2) } }

    if (!ms.isEmpty) {
      return MisMatchCount("M", refCurr, start, end, refBase, ms)
    } else {
      val iCount = mismatches.filter(_.op == "I").size
      val dCount = mismatches.filter(_.op == "D").size
      val nCount = mismatches.filter(_.op == "N").size

      if (iCount > 0) {
        IndelCount("I", refCurr, start, end, iCount)
      } else if (dCount > 0) {
        IndelCount("D", refCurr, start, end, dCount)
      } else if (nCount > 0) {
        IndelCount("N", refCurr, start, end, nCount)
      } else {
        null
      }
    }

  }

}

// tracked MisMatch Json Object
case class MisMatchJson(op: String, refCurr: Long, start: Long, end: Long, sequence: String, refBase: String, track: Long)

/**
 * aggregated point mismatch at a specific location
 *
 * @param refCurr: location of reference corresponding to mismatch
 * @param refBase: base at reference corresponding to mismatch
 * @param start: start of mismatch or indel
 * @param end: end of mismatch or indel
 * @param mismatches: Map of either [String, Long] for I,D or N or [String, (sequence, Long)] for M
 */
case class PointMisMatch(refCurr: Long, refBase: String, start: Long, end: Long, indels: Map[String, Long], mismatches: Map[String, Long])

// untracked Mismatch Json Object
case class MisMatch(op: String, refCurr: Long, start: Long, end: Long, sequence: String, refBase: String)

// counts is a map of sequence, count for mismatches
case class MisMatchCount(op: String, refCurr: Long, start: Long, end: Long, refBase: String, count: Map[String, Long]) extends MutationCount
case class IndelCount(op: String, refCurr: Long, start: Long, end: Long, count: Long) extends MutationCount

trait MutationCount {
  def op: String
  def start: Long
  def end: Long
  def refCurr: Long
  def count: Any
}
