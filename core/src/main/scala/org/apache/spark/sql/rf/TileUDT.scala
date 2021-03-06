/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2018 Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     [http://www.apache.org/licenses/LICENSE-2.0]
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.apache.spark.sql.rf

import astraea.spark.rasterframes.encoders.CatalystSerializer
import astraea.spark.rasterframes.encoders.CatalystSerializer._
import astraea.spark.rasterframes.ref.RasterRef
import astraea.spark.rasterframes.ref.RasterRef.RasterRefTile
import astraea.spark.rasterframes.tiles.{InternalRowTile, ProjectedRasterTile}
import geotrellis.raster._
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types.{DataType, _}


/**
 * UDT for singleband tiles.
 *
 * @since 5/11/17
 */
@SQLUserDefinedType(udt = classOf[TileUDT])
class TileUDT extends UserDefinedType[Tile] {
  import TileUDT._
  override def typeName = TileUDT.typeName

  override def pyUDT: String = "pyrasterframes.TileUDT"

  def userClass: Class[Tile] = classOf[Tile]

  def sqlType: StructType = CatalystSerializer[Tile].schema

  override def serialize(obj: Tile): InternalRow =
    Option(obj)
      .map(_.toInternalRow)
      .orNull

  override def deserialize(datum: Any): Tile =
    Option(datum)
      .collect {
        case ir: InternalRow ⇒ ir.to[Tile]
      }
      .map {
        case realIRT: InternalRowTile ⇒ realIRT.toArrayTile()
        case other ⇒ other
      }
      .orNull

  override def acceptsType(dataType: DataType): Boolean = dataType match {
    case _: TileUDT ⇒ true
    case _ ⇒ super.acceptsType(dataType)
  }
}

case object TileUDT  {
  UDTRegistration.register(classOf[Tile].getName, classOf[TileUDT].getName)
  UDTRegistration.register(classOf[ProjectedRasterTile].getName, classOf[TileUDT].getName)

  final val typeName: String = "tile"

  // Column mapping which must match layout below
  object C {
    val CELL_TYPE = 0
    val COLS = 1
    val ROWS = 2
    val CELLS = 3
    val REF = 4
  }

  implicit def tileSerializer: CatalystSerializer[Tile] = new CatalystSerializer[Tile] {
    import scala.language.reflectiveCalls

    override def schema: StructType = StructType(Seq(
      StructField("cell_type", StringType, false),
      StructField("cols", ShortType, false),
      StructField("rows", ShortType, false),
      StructField("cells", BinaryType, true),
      StructField("ref", CatalystSerializer[RasterRef].schema, true)
    ))

    def isRef[R](row: R, io: CatalystIO[R]): Boolean = io.isNullAt(row, C.CELLS)

    override def to[R](t: Tile, io: CatalystIO[R]): R = {
      t match {
        case ref: RasterRefTile ⇒
          io.create(
            io.encode(ref.cellType.name),
            ref.cols.toShort,
            ref.rows.toShort,
            null,
            io.to(ref.rr)
          )
        case _ ⇒
          io.create(
            io.encode(t.cellType.name),
            t.cols.toShort,
            t.rows.toShort,
            t.toBytes,
            null
          )
      }
    }
    override def from[R](row: R, io: CatalystIO[R]): Tile = {
      row match {
        case ir: InternalRow if !isRef(row, io) ⇒ new InternalRowTile(ir)
        case _ ⇒
          if(isRef(row, io)) {
            val ref = io.get[RasterRef](row, C.REF)
            RasterRefTile(ref)
          }
          else {
            val ct = CellType.fromName(io.getString(row, C.CELL_TYPE))
            val cols = io.getShort(row, C.COLS)
            val rows = io.getShort(row, C.ROWS)

            val data = io.getByteArray(row, 3)
            ArrayTile.fromBytes(data, ct, cols, rows)
          }
      }
    }
  }
}
