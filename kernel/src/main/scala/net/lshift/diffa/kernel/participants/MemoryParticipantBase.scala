/**
 * Copyright (C) 2010-2011 LShift Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lshift.diffa.kernel.participants

import org.joda.time.DateTime
import collection.mutable.HashMap
import org.slf4j.LoggerFactory
import net.lshift.diffa.kernel.differencing.{AttributesUtil, DigestBuilder}
import javax.servlet.http.HttpServletRequest
import net.lshift.diffa.participant.scanning._
import scala.collection.JavaConversions._

/**
 * Base class for test participants.
 */
class MemoryParticipantBase(nativeVsnGen: String => String) extends ScanningParticipantRequestHandler {

  val log = LoggerFactory.getLogger(getClass)

  protected val entities = new HashMap[String, TestEntity]

  def entityIds: Seq[String] = entities.keys.toList

  def queryEntityVersions(constraints:Seq[QueryConstraint]) : Seq[EntityVersion] = {
    log.trace("Running version query: " + constraints)
    val constrained = constrainEntities(constraints)
    constrained.map(e => EntityVersion(e.id, AttributesUtil.toSeq(e.attributes), e.lastUpdated,nativeVsnGen(e.body)))
  }

  def queryAggregateDigests(bucketing:Map[String, CategoryFunction], constraints:Seq[QueryConstraint]) : Seq[AggregateDigest] = {
    log.trace("Running aggregate query: " + constraints)
    val constrained = constrainEntities(constraints)
    val b = new DigestBuilder(bucketing)
    constrained foreach (ent => b.add(ent.id, ent.attributes, ent.lastUpdated, nativeVsnGen(ent.body)))
    b.digests
  }

  def constrainEntities(constraints:Seq[QueryConstraint]) = {
    // Filter on date interval & sort entries by ID into a list
    // TODO [#2] this is not really constraining yet
    val entitiesInRange = entities.values.filter(e => true).toList
    entitiesInRange.sort(_.id < _.id)
  }

  def retrieveContent(identifier: String) = entities.get(identifier) match {
    case Some(entity) => entity.body
    case None => null
  }

  def addEntity(id: String, attributes:Map[String, String], lastUpdated:DateTime, body: String): Unit = {
    entities += ((id, TestEntity(id, attributes, lastUpdated, body)))
  }

  def removeEntity(id:String) {
    entities.remove(id)
  }

  def clearEntities {
    entities.clear
  }

  def close() = entities.clear

  protected override def determineAggregations(req: HttpServletRequest) = {
    val builder = new AggregationBuilder(req)
      // No aggregations supported yet
    builder.toList
  }

  protected override def determineConstraints(req: HttpServletRequest) = {
    val builder = new ConstraintsBuilder(req)
      // No constraints supported yet
    builder.toList
  }

  protected def doQuery(constraints: java.util.List[ScanConstraint], aggregations: java.util.List[ScanAggregation]):java.util.List[ScanResultEntry] = {
    val entitiesInRange = entities.values.toList    // TODO: Constrain when participant has a proper data model
    entitiesInRange.sortWith(_.id < _.id).map { e => new ScanResultEntry(e.id, e.body, e.lastUpdated, e.attributes) }
  }
}

case class TestEntity(id: String, attributes:Map[String, String], lastUpdated:DateTime, body: String)