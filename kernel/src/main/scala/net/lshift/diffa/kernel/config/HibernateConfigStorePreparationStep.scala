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

package net.lshift.diffa.kernel.config

import org.hibernate.SessionFactory
import org.hibernate.jdbc.Work
import org.hibernate.dialect.Dialect
import org.slf4j.{LoggerFactory, Logger}
import net.lshift.diffa.kernel.util.SessionHelper._
import org.hibernate.mapping.{Column, Table, PrimaryKey, ForeignKey}
import org.hibernate.tool.hbm2ddl.{DatabaseMetadata, SchemaExport}
import org.hibernate.cfg.{Environment, Configuration}
import collection.mutable.ListBuffer
import java.sql.{Types, Connection}
import net.lshift.diffa.kernel.differencing.VersionCorrelationStore

/**
 * Preparation step to ensure that the configuration for the Hibernate Config Store is in place.
 */
class HibernateConfigStorePreparationStep
    extends HibernatePreparationStep {

  val log:Logger = LoggerFactory.getLogger(getClass)

  // The migration steps necessary to bring a hibernate configuration up-to-date. Note that these steps should be
  // in strictly ascending order.
  val migrationSteps:Seq[HibernateMigrationStep] = Seq(
    RemoveGroupsMigrationStep,
    AddSchemaVersionMigrationStep,
    AddDomainsMigrationStep
  )

  def prepare(sf: SessionFactory, config: Configuration) {

    detectVersion(sf, config) match {
      case None          => {
        (new SchemaExport(config)).create(false, true)

        // Since we are creating a fresh schema, we need to populate the schema version as well as inserting the default domain

        val createStmt = AddSchemaVersionMigrationStep.schemaVersionCreateStatement(Dialect.getDialect(config.getProperties))
        val insertSchemaVersion = HibernatePreparationUtils.schemaVersionInsertStatement(migrationSteps.last.versionId)
        val insertDefaultDomain = HibernatePreparationUtils.domainInsertStatement(Domain.DEFAULT_DOMAIN)
        val insertCorrelationSchemaVersion = HibernatePreparationUtils.correlationStoreVersionInsertStatement

        sf.withSession(s => {
          s.doWork(new Work() {
            def execute(connection: Connection) {
              val stmt = connection.createStatement()

              try {
                stmt.execute(createStmt)
                // Make sure that the DB has a version and that the default domain is in the DB
                stmt.execute(insertSchemaVersion)
                stmt.execute(insertDefaultDomain)
                // Make sure that we have the correct version for the correlation store
                stmt.execute(insertCorrelationSchemaVersion)
              } catch {
                case ex =>
                  println("Failed to prepare the schema_version table")
                  throw ex      // Higher level code will log the exception
              }

              stmt.close()
            }
          })
        })

        log.info("Applied initial database schema")
      }
      case Some(version) => {
        // Upgrade the schema if the current version is older than the last known migration step
        sf.withSession(s => {

          log.info("Current database version is " + version)

          val firstStepIdx = migrationSteps.indexWhere(step => step.versionId > version)
          if (firstStepIdx != -1) {
            s.doWork(new Work {
              def execute(connection: Connection) {
                migrationSteps.slice(firstStepIdx, migrationSteps.length).foreach(step => {
                  step.migrate(config, connection)
                  log.info("Upgraded database to version " + step.versionId)
                  if (step.versionId > 1) {
                    s.createSQLQuery(HibernatePreparationUtils.schemaVersionUpdateStatement(step.versionId)).executeUpdate()
                    s.flush
                  }
                })
              }
            })
          }
        })
      }
    }
  }

  /**
   * Determines whether the given table exists in the underlying DB
   */
  def tableExists(sf: SessionFactory, config:Configuration, tableName:String) : Boolean = {
    var hasTable:Boolean = false

    sf.withSession(s => {
      s.doWork(new Work {
        def execute(connection: Connection) = {
          val props = config.getProperties
          val dbMetadata = new DatabaseMetadata(connection, Dialect.getDialect(props))

          val defaultCatalog = props.getProperty(Environment.DEFAULT_CATALOG)
          val defaultSchema = props.getProperty(Environment.DEFAULT_SCHEMA)

          hasTable = (dbMetadata.getTableMetadata(tableName, defaultSchema, defaultCatalog, false) != null)
        }
      })
    })

    hasTable
  }

  /**
   * Detects the version of the schema using native SQL
   */
  def detectVersion(sf: SessionFactory, config:Configuration) : Option[Int] = {
    // Attempt to read the schema_version table, if it exists
    if (tableExists(sf, config, "schema_version") ) {
      Some(sf.withSession(_.createSQLQuery("select max(version) from schema_version").uniqueResult().asInstanceOf[Int]))
    }
    // The schema_version table doesn't exist, so look at the config_options table
    else if (tableExists(sf, config, "config_options") ) {
      //Prior to version 2 of the database, the schema version was kept in the ConfigOptions table
      val query = "select opt_val from config_options where opt_key = 'configStore.schemaVersion'"
      Some(sf.withSession(_.createSQLQuery(query).uniqueResult().asInstanceOf[String].toInt))
    }
    else {
      // No known table was available to read a schema version
      None
    }
  }
}

/**
 * A set of helper functions to build portable SQL strings
 */
object HibernatePreparationUtils {

  val correlationStoreVersion = VersionCorrelationStore.currentSchemaVersion.toString
  val correlationStoreSchemaKey = VersionCorrelationStore.schemaVersionKey

  /**
   * Generates a CREATE TABLE statement based on the descriptor and dialect
   */
  def generateCreateSQL(dialect:Dialect, descriptor:TableDescriptor) : String = {
    val buffer = new StringBuffer(dialect.getCreateTableString())
        .append(' ').append(dialect.quote(descriptor.name)).append(" (")

    descriptor.columns.foreach(col => {
      generateColumnString(dialect, buffer, col, true)
      buffer.append(", ")
    })

    buffer.append(descriptor.primaryKey.sqlConstraintString(dialect))

    buffer.append(")")
    buffer.toString
  }

  def generateColumnString(dialect:Dialect, buffer:StringBuffer, col:Column, newTable:Boolean) = {
    buffer.append(col.getName).append(" ")
    buffer.append(dialect.getTypeName(col.getSqlTypeCode, col.getLength, col.getPrecision, col.getScale))
    if (!col.isNullable) {
      buffer.append(" not null")
    }
    if (!newTable && col.getDefaultValue == null && !col.isNullable) {
      throw new IllegalArgumentException("Cannot have a null default value for a non-nullable column when altering a table: " + col)
    }
    if (col.getDefaultValue != null) {
      buffer.append(" default ")
      val defaultQuote = col.getSqlTypeCode.intValue() match {
        case Types.VARCHAR => "'"
        case _             => ""
      }
      buffer.append(defaultQuote)
      buffer.append(col.getDefaultValue)
      buffer.append(defaultQuote)
    }
  }

  /**
   * Generates a DROP COLUMN statement based on the descriptor and dialect
   */
  def generateDropColumnSQL(config:Configuration, tableName:String, columnName:String) : String = {
    val dialect = Dialect.getDialect(config.getProperties)
    val buffer = new StringBuffer("alter table ").append(qualifyName(config, tableName))
                                                 .append(" drop column ")
                                                 .append(dialect.openQuote())
                                                 .append(columnName.toUpperCase)
                                                 .append(dialect.openQuote())
    buffer.toString
  }

  /**
   * Generates an ADD COLUMN statement based on the descriptor and dialect
   */
  def generateAddColumnSQL(config:Configuration, tableName:String, col:Column) : String = {
    val dialect = Dialect.getDialect(config.getProperties)
    val buffer = new StringBuffer("alter table ").append(tableName)
                                                 .append(" add column ")

    generateColumnString(dialect, buffer, col, false)
    buffer.toString
  }

  /**
   * Generates an FK constraint based on the descriptor and dialect
   */
  def generateForeignKeySQL(config:Configuration, fk:ForeignKey) : String = {
    val dialect = Dialect.getDialect(config.getProperties)
    val defaultCatalog = config.getProperties.getProperty(Environment.DEFAULT_CATALOG)
    val defaultSchema = config.getProperties.getProperty(Environment.DEFAULT_SCHEMA)
    val buffer = new StringBuffer("alter table ").append(fk.getTable.getQualifiedName(dialect, defaultCatalog, defaultSchema))
    buffer.append(fk.sqlConstraintString(dialect, fk.getName, defaultCatalog, defaultSchema))
    buffer.toString
  }

  def qualifyName(config:Configuration, tableName:String) : String = {
    val dialect = Dialect.getDialect(config.getProperties)
    val defaultCatalog = config.getProperties.getProperty(Environment.DEFAULT_CATALOG)
    val defaultSchema = config.getProperties.getProperty(Environment.DEFAULT_SCHEMA)
    new Table(tableName).getQualifiedName(dialect,defaultCatalog, defaultSchema)
  }

  /**
   * Generates a statement to insert into the domains table
   */
  def domainInsertStatement(domain:Domain) =  "insert into domains(name) values('%s')".format(domain.name)

  /**
   * Generates a statement to insert a fresh schema version
   */
  def schemaVersionInsertStatement(version:Int) =  "insert into schema_version(version) values(%s)".format(version)

  /**
   * Generates a statement to insert a fresh schema version for the correlation store
   */

  def correlationStoreVersionInsertStatement = rootOptionInsertStatement(correlationStoreSchemaKey, correlationStoreVersion)
  /**
   * Generates a statement to insert a key/value pair into the root domain
   */
  def rootOptionInsertStatement(key:String, value:String) =
    "insert into system_config_options (opt_key, opt_val) values ('%s', '%s')".format(key,value)

  /**
   * Generates a statement to update the schema version for the correlation store
   */
  def correlationSchemaVersionUpdateStatement(version:String) =
    "update config_options set opt_val = '%s' where opt_key = '%s' and domain = 'root'".format(version, correlationStoreSchemaKey)

  /**
   * Generates a statement to update the schema_version table
   */
  def schemaVersionUpdateStatement(version:Int) =  "update schema_version set version = %s".format(version)
}

/**
 * Metadata that describes the attributes of a table
 */
case class TableDescriptor(name:String,
                           pk:String*) {

  val columns = new ListBuffer[Column]

  def primaryKey = {
    val key = new PrimaryKey()
    pk.foreach(k => key.addColumn(new Column(k)))
    key
  }

  def addColumn(columnName:String, sqlType:Int, nullable:Boolean) : TableDescriptor
    = addColumn(columnName, sqlType, Column.DEFAULT_LENGTH, nullable)

  def addColumn(columnName:String, sqlType:Int, length:Int, nullable:Boolean) = {
    columns += new Column(columnName){setNullable(nullable);
                                      setSqlTypeCode(sqlType);
                                      setLength(length)}
    this
  }
}

abstract class HibernateMigrationStep {

  /**
   * The version that this step gets the database to.
   */
  def versionId:Int

  /**
   * Requests that the step perform it's necessary migration.
   */
  def migrate(config:Configuration, connection:Connection)
}

object RemoveGroupsMigrationStep extends HibernateMigrationStep {
  def versionId = 1
  def migrate(config: Configuration, connection: Connection) {
    val stmt = connection.createStatement()
    stmt.execute(HibernatePreparationUtils.generateDropColumnSQL(config, "pair", "NAME" ))
    stmt.execute("drop table pair_group")
  }
}

object AddSchemaVersionMigrationStep extends HibernateMigrationStep {

  def schemaVersionCreateStatement(dialect:Dialect) = {
    val schemaVersion = new TableDescriptor("schema_version", "version")
    schemaVersion.addColumn("version", Types.INTEGER, false)
    HibernatePreparationUtils.generateCreateSQL(dialect, schemaVersion)
  }

  def versionId = 2
  def migrate(config: Configuration, connection: Connection) {
    val dialect = Dialect.getDialect(config.getProperties)
    val stmt = connection.createStatement()
    stmt.execute(schemaVersionCreateStatement(dialect))
    stmt.execute(HibernatePreparationUtils.schemaVersionInsertStatement(versionId))
  }
}
object AddDomainsMigrationStep extends HibernateMigrationStep {
  def versionId = 3
  def migrate(config: Configuration, connection: Connection) {
    val dialect = Dialect.getDialect(config.getProperties)
    val stmt = connection.createStatement()

    //create table domains (name varchar(255) not null, primary key (name));
    val domainTable = new TableDescriptor("domains", "name")
    domainTable.addColumn("name", Types.VARCHAR, 255, false)
    stmt.execute(HibernatePreparationUtils.generateCreateSQL(dialect, domainTable))

    //create table system_config_options (opt_key varchar(255) not null, opt_val varchar(255), primary key (opt_key));
    val systemConfigOptionsTable = new TableDescriptor("system_config_options", "opt_key")
    systemConfigOptionsTable.addColumn("opt_key", Types.VARCHAR, 255, false)
    systemConfigOptionsTable.addColumn("opt_val", Types.VARCHAR, 255, false)
    stmt.execute(HibernatePreparationUtils.generateCreateSQL(dialect, systemConfigOptionsTable))

    // Make sure the default is in the DB
    stmt.execute(HibernatePreparationUtils.domainInsertStatement(Domain.DEFAULT_DOMAIN))

    // create table members (domain_name varchar(255) not null, user_name varchar(255) not null, primary key (domain_name, user_name));
    val membersTable = new TableDescriptor("members", "user_name", "domain_name")
    membersTable.addColumn("domain_name", Types.VARCHAR, 255, false)
    membersTable.addColumn("user_name", Types.VARCHAR, 255, false)
    stmt.execute(HibernatePreparationUtils.generateCreateSQL(dialect, membersTable))

    // alter table config_options drop column is_internal
    stmt.execute(HibernatePreparationUtils.generateDropColumnSQL(config, "config_options", "is_internal" ))

    // Add domain column to config_option, endpoint and pair
    val domainColumn = new Column("domain"){
      setSqlTypeCode(Types.VARCHAR)
      setNullable(false)
      setLength(255)
      setDefaultValue(Domain.DEFAULT_DOMAIN.name)
    }

    // alter table config_options add column domain varchar(255) not null
    stmt.execute(HibernatePreparationUtils.generateAddColumnSQL(config, "config_options", domainColumn))
    // alter table endpoint add column domain varchar(255) not null
    stmt.execute(HibernatePreparationUtils.generateAddColumnSQL(config, "endpoint", domainColumn))
    // alter table pair add column domain varchar(255) not null
    stmt.execute(HibernatePreparationUtils.generateAddColumnSQL(config, "pair", domainColumn))

    // Upgrade the schema version for the correlation store
    stmt.execute(HibernatePreparationUtils.correlationSchemaVersionUpdateStatement("1"))

    Seq(
      // alter table config_options add constraint FK80C74EA1C3C204DC foreign key (domain) references domains;
      new ForeignKey() {
        setName("FK80C74EA1C3C204DC")
        addColumn(new Column("domain"))
        setTable(new Table("config_options") )
        setReferencedTable(new Table("domains"){
          setPrimaryKey(new PrimaryKey(){
            addColumn(new Column("name"))
          })
        })
      },
      // alter table members add constraint FK388EC9191902E93E foreign key (domain_name) references domains;
      new ForeignKey() {
        setName("FK388EC9191902E93E")
        addColumn(new Column("domain_name"))
        setTable(new Table("members") )
        setReferencedTable(new Table("domains"){
          setPrimaryKey(new PrimaryKey(){
            addColumn(new Column("name"))
          })
        })
      },
      // alter table members add constraint FK388EC9195A11FA9E foreign key (user_name) references users;
      new ForeignKey() {
        setName("FK388EC9195A11FA9E")
        addColumn(new Column("user_name"))
        setTable(new Table("members") )
        setReferencedTable(new Table("users"){
          setPrimaryKey(new PrimaryKey(){
            addColumn(new Column("name"))
          })
        })
      },
      //alter table endpoint add constraint FK67C71D95C3C204DC foreign key (domain) references domains;
      new ForeignKey() {
        setName("FK67C71D95C3C204DC")
        addColumn(new Column("domain"))
        setTable(new Table("endpoint") )
        setReferencedTable(new Table("domains"){
          setPrimaryKey(new PrimaryKey(){
            addColumn(new Column("name"))
          })
        })
      },
      //alter table escalations add constraint FK2B3C687E7D35B6A8 foreign key (pair_key) references pair;
      new ForeignKey() {
        setName("FK2B3C687E7D35B6A8")
        addColumn(new Column("pair_key"))
        setTable(new Table("escalations") )
        setReferencedTable(new Table("pair"){
          setPrimaryKey(new PrimaryKey(){
            addColumn(new Column("name"))
          })
        })
      },
      //alter table pair add constraint FK3462DAC3C204DC foreign key (domain) references domains;
      new ForeignKey() {
        setName("FK3462DAC3C204DC")
        addColumn(new Column("domain"))
        setTable(new Table("pair") )
        setReferencedTable(new Table("domains"){
          setPrimaryKey(new PrimaryKey(){
            addColumn(new Column("name"))
          })
        })
      },
      //alter table repair_actions add constraint FKF6BE324B7D35B6A8 foreign key (pair_key) references pair;
      new ForeignKey() {
        setName("FKF6BE324B7D35B6A8")
        addColumn(new Column("pair_key"))
        setTable(new Table("repair_actions") )
        setReferencedTable(new Table("pair"){
          setPrimaryKey(new PrimaryKey(){
            addColumn(new Column("name"))
          })
        })
      }
    ).foreach(fk => stmt.execute(HibernatePreparationUtils.generateForeignKeySQL(config,fk)))

  }
}