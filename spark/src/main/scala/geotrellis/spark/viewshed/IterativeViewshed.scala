/*
 * Copyright 2017 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.viewshed

import geotrellis.proj4.LatLng
import geotrellis.raster._
import geotrellis.raster.rasterize.Rasterizer
import geotrellis.raster.viewshed.R2Viewshed
import geotrellis.raster.viewshed.R2Viewshed._
import geotrellis.spark._
import geotrellis.spark.tiling._
import geotrellis.util._
import geotrellis.vector._

import com.vividsolutions.jts.{ geom => jts }
import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.AccumulatorV2

import scala.collection.mutable
import scala.reflect.ClassTag


object IterativeViewshed {

  type Point6D = Array[Double]

  implicit def coordinatesToPoints(points: Seq[jts.Coordinate]): Seq[Point6D] =
    points.map({ p => Array(p.x, p.y, p.z, 0, -1.0, Double.NegativeInfinity) })

  private val logger = Logger.getLogger(IterativeViewshed.getClass)

  private type Message = (SpatialKey, Int, From, mutable.ArrayBuffer[Ray]) // key, point index, direction, ray
  private type Messages = mutable.ArrayBuffer[Message]

  private class RayCatcher extends AccumulatorV2[Message, Messages] {
    private val messages: Messages = mutable.ArrayBuffer.empty
    def copy: RayCatcher = {
      val other = new RayCatcher
      other.merge(this)
      other
    }
    def add(message: Message): Unit = this.synchronized { messages.append(message) }
    def isZero: Boolean = messages.isEmpty
    def merge(other: AccumulatorV2[Message, Messages]): Unit = this.synchronized { messages ++= other.value }
    def reset: Unit = this.synchronized { messages.clear }
    def value: Messages = messages
  }

  private def computeResolution[K: (? => SpatialKey): ClassTag, V: (? => Tile)](
    elevation: RDD[(K, V)] with Metadata[TileLayerMetadata[K]]
  ) = {
    val md = elevation.metadata
    val mt = md.mapTransform
    val key = implicitly[SpatialKey](elevation.map(_._1).first)
    val extent = mt(key).reproject(md.crs, LatLng)
    val degrees = extent.xmax - extent.xmin
    val meters = degrees * (6378137 * 2.0 * math.Pi) / 360.0
    val pixels = md.layout.tileCols
    math.abs(meters / pixels)
  }

  private case class PointInfo(
    index: Int,
    key: SpatialKey,
    col: Int,
    row: Int,
    viewHeight: Double,
    angle: Double,
    fov: Double,
    alt: Double
  )

  private def pointInfo[K: (? => SpatialKey), V: (? => Tile)](
    rdd: RDD[(K, V)] with Metadata[TileLayerMetadata[K]])(
    pi: (Point6D, Int)
  )= {
    val (p, index) = pi
    val md = rdd.metadata

    val p2 = new jts.Coordinate(p(0),p(1))
    val bounds = md.layout.mapTransform(p2.envelope)
    require(bounds.colMin == bounds.colMax)
    require(bounds.rowMin == bounds.rowMax)

    val cols = md.layout.tileCols
    val rows = md.layout.tileRows
    val key = SpatialKey(bounds.colMin, bounds.rowMin)
    val extent = md.mapTransform(key)
    val re = RasterExtent(extent, cols, rows)
    val col = re.mapXToGrid(p2.x)
    val row = re.mapYToGrid(p2.y)
    val viewHeight = p(2)

    PointInfo(
      index = index,
      key = key,
      col = col,
      row = row,
      viewHeight = viewHeight,
      angle = p(3),
      fov = p(4),
      alt = p(5)
    )
  }

  /**
    *
    */
  def apply[K: (? => SpatialKey): ClassTag, V: (? => Tile)](
    elevation: RDD[(K, V)] with Metadata[TileLayerMetadata[K]],
    ps: Seq[Point6D],
    maxDistance: Double,
    curvature: Boolean = true,
    operator: AggregationOperator = Or(),
    epsilon: Double = (1/math.Pi),
    touchedKeys: mutable.Set[SpatialKey] = null
  )(implicit sc: SparkContext): RDD[(K, Tile)] with Metadata[TileLayerMetadata[K]] = {

    ps.foreach({ p => require(p.length == 6) })

    val md = elevation.metadata
    val mt = md.mapTransform

    val resolution = computeResolution(elevation)
    logger.debug(s"Computed resolution: $resolution meters/pixel")

    val bounds = md.bounds.asInstanceOf[KeyBounds[K]]
    val minKey = implicitly[SpatialKey](bounds.minKey)
    val minKeyCol = minKey._1
    val minKeyRow = minKey._2
    val maxKey = implicitly[SpatialKey](bounds.maxKey)
    val maxKeyCol = maxKey._1
    val maxKeyRow = maxKey._2

    val rays = new RayCatcher; sc.register(rays)

    def validKey(key: SpatialKey): Boolean = {
      ((minKeyCol <= key.col && key.col <= maxKeyCol) &&
       (minKeyRow <= key.row && key.row <= maxKeyRow))
    }

    def rayCatcherFn(key: SpatialKey, index: Int)(bundle: Bundle): Unit = {
      val southKey = SpatialKey(key.col + 0, key.row + 1)
      val westKey = SpatialKey(key.col + 1, key.row + 0)
      val northKey = SpatialKey(key.col + 0, key.row - 1)
      val eastKey = SpatialKey(key.col - 1, key.row + 0)

      Map(
        FromSouth() -> southKey,
        FromWest() -> westKey,
        FromNorth() -> northKey,
        FromEast() -> eastKey
      ).foreach({ case (dir, key) =>
        if (validKey(key)) {
          val rs = bundle.getOrElse(dir, throw new Exception)
          if (rs.length > 0) {
            val message = (key, index, dir, rs)
            rays.add(message)
          }
        }
      })
    }

    val info: Seq[PointInfo] = {
      val fn = pointInfo(elevation)_
      ps.zipWithIndex.map(fn)
    }

    val _pointsByKey: Map[SpatialKey, Seq[PointInfo]] =
      info
        .groupBy(_.key)
        .toMap
    val pointsByKey = sc.broadcast(_pointsByKey)
    if (touchedKeys != null) touchedKeys ++= _pointsByKey.keys

    val _pointsByIndex: Map[Int, PointInfo] =
      info
        .groupBy(_.index)
        .mapValues({ list => list.head })
        .toMap
    val pointsByIndex = sc.broadcast(_pointsByIndex)

    val _heightsByIndex: Map[Int, Double] = // index -> height
      elevation
        .flatMap({ case (k, v) =>
          val key = implicitly[SpatialKey](k)
          val tile = implicitly[Tile](v)

          pointsByKey.value.get(key) match {
            case Some(list) =>
              list.map({ case PointInfo(index, _, col, row, viewHeight0, _, _, _) =>
                val viewHeight =
                  if (viewHeight0 >= 0.0) tile.getDouble(col, row) + viewHeight0 ; else -viewHeight0
                (index, viewHeight)
              })
            case None => Seq.empty[(Int, Double)]
          }
        })
        .collect
        .toMap
    val heightsByIndex = sc.broadcast(_heightsByIndex)

    // Create RDD  of viewsheds; after this,  the accumulator contains
    // the rays emanating from the starting points.
    var sheds: RDD[(K, V, MutableArrayTile)] = elevation.map({ case (k, v) =>
      val key = implicitly[SpatialKey](k)
      val tile = implicitly[Tile](v)
      val shed = R2Viewshed.generateEmptyViewshedTile(tile.cols, tile.rows)

      pointsByKey.value.get(key) match {
        case Some(list) =>
          list.foreach({ case PointInfo(index, _, col, row, _, ang, fov, alt) =>
            val viewHeight = heightsByIndex.value.getOrElse(index, throw new Exception)

            R2Viewshed.compute(
              tile, shed,
              col, row, viewHeight,
              FromInside(),
              null,
              rayCatcherFn(key, index),
              resolution = resolution,
              maxDistance = maxDistance,
              curvature = curvature,
              altitude = alt,
              operator = operator,
              cameraDirection = ang,
              cameraFOV = fov
            )
          })
        case None =>
      }

      (k, v, shed)
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)
    sheds.count // make sheds materialize

    // Repeatedly  map over the RDD  of viewshed tiles until  all rays
    // have reached the periphery of the layer.
    do {
      val _changes: Map[SpatialKey, Seq[(Int, From, mutable.ArrayBuffer[Ray])]] =
        rays.value
          .groupBy(_._1)
          .map({ case (k, list) =>
            (k, list.map({ case (_, index, from, rs) => (index, from, rs) }))
          })
          .toMap
      val changes = sc.broadcast(_changes)

      if (touchedKeys != null) touchedKeys ++= _changes.keys
      rays.reset
      logger.debug(s"≥ ${changes.value.size} tiles in motion")

      val oldSheds = sheds
      sheds = oldSheds.map({ case (k, v, shed) =>
        val key = implicitly[SpatialKey](k)
        val elevationTile = implicitly[Tile](v)
        val cols = elevationTile.cols
        val rows = elevationTile.rows

        changes.value.get(key) match {
          case Some(localChanges: Seq[(Int, From, mutable.ArrayBuffer[Ray])]) => { // sequence of <index, from, rays> triples for this key
            val indexed: Map[Int, Seq[(From, mutable.ArrayBuffer[Ray])]] = // a map from an index to a sequence of <from, rays> pairs
              localChanges
                .groupBy(_._1)
                .map({ case (index, list) =>
                  (index, list.map({ case (_, from, rs) => (from, rs) }))
                })

            indexed.foreach({ case (index, list) => // for all <from, rays> pairs generated by this point (this index)
              val PointInfo(_, pointKey, col, row, _, angle, fov, alt) = pointsByIndex.value.getOrElse(index, throw new Exception)
              val startCol = (pointKey.col - key.col) * cols + col
              val startRow = (pointKey.row - key.row) * rows + row
              val viewHeight = heightsByIndex.value.getOrElse(index, throw new Exception)
              val packets: Map[From, Array[Ray]] = list
                .groupBy(_._1)
                .mapValues({ case rss =>
                  rss
                    .map({ case (_, rs) => rs })
                    .foldLeft(mutable.ArrayBuffer.empty[Ray])(_ ++ _) })
                .mapValues({ rs => rs.sortBy(_.theta).toArray })

              packets.foreach({ case (from, rays) => // for each <direction, packet> pair, evolve the tile
                val sortedRays = rays.toArray
                if (rays.length > 0) {
                  R2Viewshed.compute(
                    elevationTile, shed,
                    startCol, startRow, viewHeight,
                    from,
                    sortedRays,
                    rayCatcherFn(key, index),
                    resolution = resolution,
                    maxDistance = maxDistance,
                    curvature = curvature,
                    operator = operator,
                    altitude = alt,
                    cameraDirection = angle,
                    cameraFOV = fov,
                    epsilon = epsilon
                  )
                }
              })
            })

          }
          case None =>
        }
        (k, v, shed)
      }).persist(StorageLevel.MEMORY_AND_DISK_SER)
      sheds.count
      oldSheds.unpersist()

    } while (rays.value.size > 0)

    // Return the computed viewshed layer
    val metadata = TileLayerMetadata(IntConstantNoDataCellType, md.layout, md.extent, md.crs, md.bounds)
    val rdd = sheds.map({ case (k, _, v) => (k, v.asInstanceOf[Tile]) })
    ContextRDD(rdd, metadata)
  }

}
