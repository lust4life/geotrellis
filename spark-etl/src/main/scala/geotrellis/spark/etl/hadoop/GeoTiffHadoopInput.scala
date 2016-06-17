package geotrellis.spark.etl.hadoop

import geotrellis.raster._
import geotrellis.spark._
import geotrellis.spark.etl.EtlJob
import geotrellis.vector.ProjectedExtent
import geotrellis.spark.ingest._
import geotrellis.spark.merge._
import geotrellis.spark.io.hadoop._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

class GeoTiffHadoopInput extends HadoopInput[ProjectedExtent, Tile]() {
  val format = "geotiff"
  def apply(job: EtlJob)(implicit sc: SparkContext): RDD[(ProjectedExtent, Tile)] = sc.hadoopGeoTiffRDD(job.inputProps("path"))
}

