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

  def apply(record: AlignmentRecord, reference: String, region: ReferenceRegion): List[MisMatch] = {
    alignMismatchesToRead(record, reference, region)
  }

  def alignMismatchesToRead(rec: AlignmentRecord, reference: String, region: ReferenceRegion): List[MisMatch] = {
    var ref: String =
      if (rec.readNegativeStrand) {
        // get new reference sequence complementary to the given reference
        complement(reference)
      } else reference

    var misMatches: ListBuffer[MisMatch] = new ListBuffer[MisMatch]()
    val cigar = TextCigarCodec.decode(rec.cigar).getCigarElements()
    var refIdx = rec.start + 1
    var recIdx = rec.start

    cigar.foreach {
      e =>
        {
          var misLen = e.getLength
          var op: CigarOperator = e.getOperator
          if (op == CigarOperator.X || op == CigarOperator.M) {
            try {
              for (i <- 0 to misLen - 1) {
                // if index position is not within region
                if (refIdx <= region.end && refIdx >= region.start) {
                  val recBase = rec.sequence.charAt(getPosition(recIdx, rec.start))
                  val refBase = ref.charAt(getPosition(refIdx, region.start))
                  if (refBase != recBase) {
                    val start = recIdx
                    val end = start + 1
                    misMatches += new MisMatch(op.toString, refIdx, start, end, recBase.toString, refBase.toString)
                  }
                }
                recIdx += 1
                refIdx += 1
              }
            } catch {
              case iobe: StringIndexOutOfBoundsException => {
                log.warn("Record Sequence " + rec.sequence + " at index " + recIdx)
                log.warn(" Reference Sequence " + ref + " at index " + refIdx)
                log.warn("Cigar" + rec.cigar)
              }
              case e: Exception => log.warn(e.toString)
            }
          } else if (op == CigarOperator.I) {
            val end = recIdx + misLen
            val stringStart = (recIdx - rec.start).toInt
            val indel = rec.sequence.substring(stringStart, stringStart + misLen)
            misMatches += new MisMatch(op.toString, refIdx, recIdx, end, indel, null)
            recIdx += misLen
          } else if (op == CigarOperator.D || op == CigarOperator.N) {
            val end = recIdx + misLen
            val stringStart = getPosition(recIdx, rec.start)
            val indel = rec.sequence.substring(stringStart, stringStart + misLen)
            misMatches += new MisMatch(op.toString, refIdx, recIdx, end, indel, null)
            refIdx += misLen
          }

        }
    }
    misMatches.toList
  }

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

// temporary objects for alignmentrecord visual data
case class MisMatch(op: String, refCurr: Long, start: Long, end: Long, sequence: String, refBase: String)
