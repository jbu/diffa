/**
 * Copyright (C) 2010-2012 LShift Ltd.
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
package net.lshift.diffa.kernel.util.db

import net.lshift.diffa.kernel.util.db.{HibernateQueryUtils => HQU}
import net.lshift.diffa.kernel.util.db.SessionHelper._

import org.hibernate.SessionFactory

class HibernateDatabaseFacade(factory:SessionFactory) extends DatabaseFacade {

  def listQuery[ReturnType](queryName: String, params: Map[String, Any], firstResult: Option[Int], maxResults: Option[Int]) = {
    factory.withSession(s => HQU.listQuery(s, queryName, params, firstResult, maxResults))
  }

  def delete(queryName: String, params: Map[String, Any]) = {
    factory.withSession( s => HQU.listQuery(s, queryName, params).foreach( x => s.delete(x) ) )
  }
}
