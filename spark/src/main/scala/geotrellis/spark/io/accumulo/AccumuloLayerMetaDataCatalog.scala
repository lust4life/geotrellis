package geotrellis.spark.io.accumulo

import geotrellis.raster.histogram.Histogram
import geotrellis.raster.io.json._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.json._

import org.apache.accumulo.core.client.Connector
import org.apache.accumulo.core.data.{Key, Mutation, Value, Range => ARange}
import org.apache.accumulo.core.security.Authorizations
import org.apache.spark.Logging
import spray.json._
import DefaultJsonProtocol._

case class AccumuloLayerMetaData(layerMetaData: LayerMetaData, tileTable: String)

class AccumuloLayerMetaDataCatalog(connector: Connector, val catalogTable: String) extends Store[LayerId, AccumuloLayerMetaData] with Logging {
  //create the metadata table if it does not exist
  {
    val ops = connector.tableOperations()
    if (!ops.exists(catalogTable))
      ops.create(catalogTable)
  }

  var catalog: Map[LayerId, AccumuloLayerMetaData] = fetchAll

  def zoomLevelsFor(layerName: String): Seq[Int] = {
    catalog.keys.filter(_.name == layerName).map(_.zoom).toSeq
  }

  def write(id: LayerId, metaData: AccumuloLayerMetaData): Unit = {
    catalog = catalog updated (id, metaData)

    val mutation = new Mutation(s"${metaData.tileTable}__${id.name}")
    mutation.put( //RasterMetaData
      id.zoom.toString, "metadata", System.currentTimeMillis(),
      new Value(metaData.layerMetaData.rasterMetaData.toJson.compactPrint.getBytes)
    )
    mutation.put( //Histogram
      id.zoom.toString, "histogram", System.currentTimeMillis(),
      new Value(metaData.layerMetaData.histogram.toJson.compactPrint.getBytes)
    )
    mutation.put( //Key ClassTag
      id.zoom.toString, "keyClass", System.currentTimeMillis(),
      new Value(metaData.layerMetaData.keyClass.getBytes)
    )

    connector.write(catalogTable, mutation)
  }


  def read(layerId: LayerId): AccumuloLayerMetaData = {
    val candidates = catalog
      .filterKeys( _ == layerId)

    candidates.size match {
      case 0 =>
        throw new LayerNotFoundError(layerId)
      case 1 =>
        val (key, value) = candidates.toList.head
        value
      case _ =>
        throw new MultipleMatchError(layerId)
    }
  }

  def fetchAll: Map[LayerId, AccumuloLayerMetaData] = {
    var data: Map[LayerId, Map[String, Value]] =
      Map.empty.withDefaultValue(Map.empty)

    var tables: Map[LayerId, String] =
      Map.empty

    connector.createScanner(catalogTable, new Authorizations()).foreach { case (key, value) =>
      val Array(table, name) = key.getRow.toString.split("__")
      val zoom: Int = key.getColumnFamily.toString.toInt
      val layerId = LayerId(name, zoom)
      val field = key.getColumnQualifier.toString

      val k = layerId
      data = data updated (k, data(k) updated (field, value))
      tables = tables updated (k, table)
    }

    def readLayerMetaData(map: Map[String, Value], table: String): AccumuloLayerMetaData =
      AccumuloLayerMetaData(
        LayerMetaData(
          keyClass =  map("keyClass").toString,
          rasterMetaData = map("metadata").toString.parseJson.convertTo[RasterMetaData],
          histogram = map.get("histogram").map(_.toString.parseJson.convertTo[Histogram])
        ),
        table
      )

    data map { case (key, fieldMap) => key -> readLayerMetaData(fieldMap, tables(key))}
  }
}

