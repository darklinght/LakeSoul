// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.spark.sql.lakesoul.utils

import com.dmetasoul.lakesoul.meta.{DataFileInfo, DataOperation, MetaUtils}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.connector.catalog.Identifier
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.internal.StaticSQLConf
import org.apache.spark.sql.lakesoul.catalog.{LakeSoulCatalog, LakeSoulTableV2}
import org.apache.spark.sql.lakesoul.sources.LakeSoulBaseRelation
import org.apache.spark.sql.lakesoul.{BatchDataSoulFileIndexV2, PartitionFilter, Snapshot, SnapshotManagement}
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.util.Utils.logWarning
import org.jetbrains.annotations.TestOnly

import scala.collection.JavaConverters._


object SparkUtil extends Logging {

  def allPartitionFilterInfoDF(snapshot: Snapshot): DataFrame = {
    val allPartition = snapshot.getPartitionInfoArray.map(part =>
      PartitionFilterInfo(
        part.range_value,
        MetaUtils.getPartitionMapFromKey(part.range_value),
        part.version,
        part.table_id,
        part.read_files.map(_.toString),
        part.expression,
        part.commit_op))

    val spark = SparkSession.active
    import spark.implicits._
    spark.sparkContext.parallelize(allPartition).toDF.persist()
  }

  @TestOnly
  def allDataInfo(snapshot: Snapshot): Array[DataFileInfo] = {
    val spark = SparkSession.active
    import spark.implicits._
    spark.sparkContext.parallelize(DataOperation.getTableDataInfo(snapshot.getPartitionInfoArray)).toDS().persist().as[DataFileInfo].collect()
  }

  def isPartitionVersionRead(snapshotManagement: SnapshotManagement): Boolean = {
    val (partitionDesc, startPartitionVersion, endPartitionVersion, incremental) = snapshotManagement.snapshot.getPartitionDescAndVersion
    if (endPartitionVersion == -1L && partitionDesc.equals("")) {
      false
    } else {
      true
    }
  }

  def makeQualifiedTablePath(tablePath: Path): Path = {
    val spark = SparkSession.active
    val uri = tablePath.toUri.toString
    if (uri.startsWith("file:///")) {
      tablePath
    } else if (uri.startsWith("file:/")) {
      // make local file path always starts with file:///
      tablePath.getFileSystem(spark.sessionState.newHadoopConf()).makeQualified(
        new Path(uri.substring(5))
      )
    } else {
      tablePath.getFileSystem(spark.sessionState.newHadoopConf()).makeQualified(tablePath)
    }
  }

  def makeQualifiedPath(tablePath: String): Path = {
    makeQualifiedTablePath(new Path(tablePath))
  }

  def getDefaultTablePath(table: Identifier): Path = {
    val namespace = table.namespace() match {
      case Array(ns) => ns
      case _ => LakeSoulCatalog.showCurrentNamespace()(0)
    }
    val spark = SparkSession.active
    val warehousePath = spark.sessionState.conf.getConf(StaticSQLConf.WAREHOUSE_PATH)
    makeQualifiedTablePath(new Path(new Path(warehousePath, namespace), table.name()))
  }

  def getDefaultTablePath(table: TableIdentifier): Path = {
    val namespace = table.database.getOrElse(LakeSoulCatalog.showCurrentNamespace()(0))
    val spark = SparkSession.active
    val warehousePath = spark.sessionState.conf.getConf(StaticSQLConf.WAREHOUSE_PATH)
    makeQualifiedTablePath(new Path(new Path(warehousePath, namespace), table.table))
  }

  def tablePathExisted(fs: FileSystem, tableAbsolutePath: Path): Boolean = {
    if (fs.exists(tableAbsolutePath) && fs.listStatus(tableAbsolutePath).nonEmpty) {
      true
    } else {
      false
    }
  }

  // ------------------snapshotmanagement--------------
  def createRelation(partitionFilters: Seq[Expression] = Nil, snapmnt: SnapshotManagement, sparksess: SparkSession): BaseRelation = {
    LakeSoulBaseRelation(None, snapmnt)(sparksess)
  }


  def createDataFrame(files: Seq[DataFileInfo],
                      requiredColumns: Seq[String],
                      snapmnt: SnapshotManagement,
                      predicts: Option[Expression] = None): DataFrame = {
    val skipFiles = if (predicts.isDefined) {
      val predictFiles = PartitionFilter.filesForScan(snapmnt.snapshot, Seq(predicts.get))
      files.intersect(predictFiles)
    } else {
      files
    }
    val spark = SparkSession.active
    val table_name = snapmnt.table_path
    val fileIndex = BatchDataSoulFileIndexV2(spark, snapmnt, skipFiles)
    val table = LakeSoulTableV2(
      spark,
      new Path(table_name),
      None,
      None,
      Option(fileIndex)
    )
    val option = new CaseInsensitiveStringMap(Map("basePath" -> table_name).asJava)
    Dataset.ofRows(
      spark,
      DataSourceV2Relation(
        table,
        table.schema().toAttributes,
        None,
        None,
        option
      )
    ).select(requiredColumns.map(col): _*)
  }


  def tryWithSafeFinally[T](block: => T)(finallyBlock: => Unit): T = {
    var originalThrowable: Throwable = null
    try {
      block
    } catch {
      case t: Throwable =>
        // Purposefully not using NonFatal, because even fatal exceptions
        // we don't want to have our finallyBlock suppress
        originalThrowable = t
        throw originalThrowable
    } finally {
      try {
        finallyBlock
      } catch {
        case t: Throwable if (originalThrowable != null && originalThrowable != t) =>
          originalThrowable.addSuppressed(t)
          logWarning(s"Suppressing exception in finally: ${t.getMessage}", t)
          throw originalThrowable
      }
    }
  }

  def tryWithResource[R <: AutoCloseable, T](createResource: => R)(f: R => T): T = {
    val resource = createResource
    tryWithSafeFinally({
      f.apply(resource)
    }) {
      resource.close()
    }
  }

}
