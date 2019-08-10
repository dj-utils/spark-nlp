package com.johnsnowlabs.nlp.annotators.ocr

import com.johnsnowlabs.nlp.{Annotation, ParamsAndFeaturesReadable, RawAnnotator}
import com.johnsnowlabs.nlp.AnnotatorType.CHUNK
import org.apache.spark.ml.param.Param
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.ArrayType
import com.johnsnowlabs.nlp.annotators.ocr.schema._

class PositionFinder(override val uid: String) extends RawAnnotator[PositionFinder] {

  override val outputAnnotatorType: AnnotatorType = CHUNK

  /** A Tokenizer could require only for now a SentenceDetector annotator */
  override val inputAnnotatorTypes: Array[AnnotatorType] = Array[AnnotatorType](CHUNK)

  def this() = this(Identifiable.randomUID("POSITION_FINDER"))

  val pageMatrixCol: Param[String] = new Param(this, "pageMatrixCol", "Column name for Page Matrix schema")

  def setPageMatrixCol(value: String): this.type = set(pageMatrixCol, value)

  def getPageMatrixCol: String = $(pageMatrixCol)

  private val parseCoordinates = udf {
    (chunkRaw: Seq[Row], pageRaw: Seq[Row]) => {
      val chunkAnnotations = chunkRaw.map(Annotation(_))

      val bounds = Array.ofDim[Int](pageRaw.length)
      var last = 0
      val matrix = pageRaw.zipWithIndex.flatMap{case (p, i) =>
        val pm = PageMatrix.fromRow(p)
        last += pm.mapping.length
        bounds(i) = last
        pm.mapping
      }

      chunkAnnotations.map(target => {
        val line = matrix.slice(target.begin, target.end+1)
        if(target.result == line.map(_.toString).mkString) {

          var minx = -1.0f
          var maxx = -1.0f

          for (pos <- line) {
            if (pos != null) {

              if (minx == -1 || pos.x < minx) minx = pos.x
              if (maxx == -1 || pos.x > maxx) maxx = pos.x
            }
          }

          val firstPosition = line.head
          val lastPosition = line.last

          val x = minx
          val y = firstPosition.y

          val w = (maxx - minx) + lastPosition.width
          val h = lastPosition.height

          Coordinate(bounds.count(target.begin > _), x, y, w, h)
        } else {
          Coordinate(-1, -1, -1, -1, -1)
        }

      })
    }
  }

  override def transform(dataset: Dataset[_]): DataFrame = {
    require(
      dataset.schema.fields.exists(field => field.name == $(pageMatrixCol) && field.dataType == PageMatrix.dataType),
      s"because there is no column with name ${$(pageMatrixCol)} and an appropriate PageMatrix schema"
    )
    require(
      getInputCols.length == 1,
      "because inputCols is not 1"
    )
    require(
      dataset.schema.fields.exists(field => field.name == getInputCols.head && field.dataType == ArrayType(Annotation.dataType)),
      s"because there is no column with name ${getInputCols.head} and an appropriate Annotation schema"
    )

    dataset.withColumn(getOutputCol, parseCoordinates(col(getInputCols.head), col(getPageMatrixCol)))

  }

}

object PositionFinder extends ParamsAndFeaturesReadable[PositionFinder]