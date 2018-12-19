/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2017 Astraea, Inc.
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
 */

package astraea.spark.rasterframes.functions

import java.nio.ByteBuffer

import astraea.spark.rasterframes.util._
import geotrellis.raster.{DataType ⇒ _, _}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.aggregate.{ImperativeAggregate, TypedImperativeAggregate}
import org.apache.spark.sql.catalyst.expressions.{Expression, ImplicitCastInputTypes}
import org.apache.spark.sql.rf.TileUDT
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Column, TypedColumn}
import spire.syntax.cfor._

/**
 * Aggregator for reassembling tiles from from exploded form
 *
 * @since 9/24/17
 */
case class TileAssembler(
  colIndex: Expression,
  rowIndex: Expression,
  cellValue: Expression,
  tileCols: Int, tileRows: Int,
  cellType: CellType,
  mutableAggBufferOffset: Int = 0,
  inputAggBufferOffset: Int = 0
) extends TypedImperativeAggregate[ByteBuffer] with ImplicitCastInputTypes {

  override def children: Seq[Expression] = Seq(colIndex, rowIndex, cellValue)

  override def inputTypes = Seq(IntegerType, IntegerType, DoubleType)

  private val TileType = new TileUDT()

  override def prettyName: String = "assemble_tiles"

  override def withNewMutableAggBufferOffset(newMutableAggBufferOffset: Int): ImperativeAggregate =
    copy(mutableAggBufferOffset = newMutableAggBufferOffset)

  override def withNewInputAggBufferOffset(newInputAggBufferOffset: Int): ImperativeAggregate =
    copy(inputAggBufferOffset = newInputAggBufferOffset)

  override def nullable: Boolean = true

  override def dataType: DataType = TileType

  override def createAggregationBuffer(): ByteBuffer = {
    val buff = ByteBuffer.allocateDirect(tileCols * tileRows * java.lang.Double.BYTES)
    val dubs = buff.asDoubleBuffer()
    val length = dubs.capacity()
    cfor(0)(_ < length, _ + 1) { idx ⇒
      dubs.put(idx, doubleNODATA)
    }
    buff
  }

  override def update(buffer: ByteBuffer, input: InternalRow): ByteBuffer = {
    val col = colIndex.eval(input).asInstanceOf[Int]
    val row = rowIndex.eval(input).asInstanceOf[Int]
    val cell = cellValue.eval(input).asInstanceOf[Double]
    val cells = buffer.asDoubleBuffer()
    cells.put(row * tileCols + col, cell)
    buffer
  }

  override def merge(buffer: ByteBuffer, input: ByteBuffer): ByteBuffer = {
    val left = buffer.asDoubleBuffer()
    val right = input.asDoubleBuffer()
    val length = left.capacity()
    cfor(0)(_ < length, _ + 1) { idx ⇒
      val cell = right.get(idx)
      if (isData(cell)) left.put(idx, cell)
    }
    buffer
  }

  override def eval(buffer: ByteBuffer): InternalRow = {
    // TODO: figure out how to eliminate copies here.
    val result = buffer.asDoubleBuffer()
    val length = result.capacity()
    val cells =  Array.ofDim[Double](length)
    result.get(cells)
    val tile = ArrayTile.apply(cells, tileCols, tileRows).convert(cellType)
    TileType.serialize(tile)
  }

  override def serialize(buffer: ByteBuffer): Array[Byte] = {
    val length = buffer.capacity()
    val data = Array.ofDim[Byte](length)
    buffer.get(data)
    data
  }

  override def deserialize(storageFormat: Array[Byte]): ByteBuffer = ByteBuffer.wrap(storageFormat)

}

object TileAssembler {
  import astraea.spark.rasterframes.encoders.StandardEncoders._
  def apply(columnIndex: Column, rowIndex: Column, cellData: Column, cols: Int, rows: Int, ct: CellType): TypedColumn[Any, Tile] =
    new Column(new TileAssembler(columnIndex.expr, rowIndex.expr, cellData.expr, cols, rows, ct).toAggregateExpression())
      .as(cellData.columnName).as[Tile]
}
