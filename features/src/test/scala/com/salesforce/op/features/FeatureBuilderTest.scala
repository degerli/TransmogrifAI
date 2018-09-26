/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.features

import java.util

import com.salesforce.op.aggregators._
import com.salesforce.op.features.types._
import com.salesforce.op.stages._
import com.salesforce.op.test.{Passenger, TestSparkContext}
import com.twitter.algebird.MonoidAggregator
import org.apache.spark.sql.{DataFrame, Row}
import org.joda.time.Duration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.reflect.runtime.universe._


@RunWith(classOf[JUnitRunner])
class FeatureBuilderTest extends FlatSpec with TestSparkContext {
  private val name = "feature"
  private val passenger =
    Passenger.newBuilder()
      .setPassengerId(0).setGender("Male").setAge(1).setBoarded(2).setHeight(3)
      .setWeight(4).setDescription("").setSurvived(1).setRecordDate(4)
      .setStringMap(new util.HashMap[String, String]())
      .setNumericMap(new util.HashMap[String, java.lang.Double]())
      .setBooleanMap(new util.HashMap[String, java.lang.Boolean]())
      .build()

  import spark.implicits._
  private val data: DataFrame = Seq(FeatureBuilderContainerTest("blah1", 10, d = 2.0)).toDS.toDF()

  Spec(FeatureBuilder.getClass) should "build a simple feature with a custom name" in {
    val f1 = FeatureBuilder.Real[Passenger]("a").extract(p => Option(p.getAge).map(_.toDouble).toReal).asPredictor
    assertFeature[Passenger, Real](f1)(in = passenger, out = 1.toReal, name = "a")

    val f2 = FeatureBuilder[Passenger, Real]("b").extract(p => Option(p.getAge).map(_.toDouble).toReal).asResponse
    assertFeature[Passenger, Real](f2)(in = passenger, isResponse = true, out = 1.toReal, name = "b")
  }

  it should "build a simple feature using macro" in {
    val feature = FeatureBuilder.Real[Passenger].extract(p => Option(p.getAge).map(_.toDouble).toReal).asResponse
    assertFeature[Passenger, Real](feature)(name = name, in = passenger, out = 1.toReal, isResponse = true)
  }

  it should "build a simple feature from Row with a custom name" in {
    val feature = FeatureBuilder.fromRow[Text](name = "feat", Some(1)).asPredictor
    assertFeature[Row, Text](feature)(name = "feat", in = Row(1.0, "2"), out = "2".toText, isResponse = false)
  }

  it should "build a simple feature from Row using macro" in {
    val feature = FeatureBuilder.fromRow[Real](0).asResponse
    assertFeature[Row, Real](feature)(name = name, in = Row(1.0, "2"), out = 1.toReal, isResponse = true)
  }

  it should "build features from a dataframe" in {
    val row = data.head()
    val (label, Array(fs, fl)) = FeatureBuilder.fromDataFrame[RealNN](data, response = "d")
    assertFeature(label)(name = "d", in = row, out = 2.0.toRealNN, isResponse = true)
    assertFeature(fs.asInstanceOf[Feature[Text]])(name = "s", in = row, out = "blah1".toText, isResponse = false)
    assertFeature(fl.asInstanceOf[Feature[Integral]])(name = "l", in = row, out = 10.toIntegral, isResponse = false)
  }

  it should "error on invalid response" in {
    intercept[RuntimeException](FeatureBuilder.fromDataFrame[RealNN](data, response = "non_existent"))
      .getMessage shouldBe "Response feature 'non_existent' was not found in dataframe schema"
    intercept[RuntimeException](FeatureBuilder.fromDataFrame[RealNN](data, response = "s")).getMessage shouldBe
      "Response feature 's' is of type com.salesforce.op.features.types.Text, " +
        "but expected com.salesforce.op.features.types.RealNN"
    intercept[RuntimeException](FeatureBuilder.fromDataFrame[Text](data, response = "d")).getMessage shouldBe
      "Response feature 'd' is of type com.salesforce.op.features.types.RealNN, " +
        "but expected com.salesforce.op.features.types.Text"
  }

  it should "return a default if extract throws an exception" in {
    val feature =
      FeatureBuilder.Real[Passenger]
        .extract(p => Option(p.getAge / 0).map(_.toDouble).toReal, 123.toReal)
        .asResponse

    assertFeature[Passenger, Real](feature)(name = name, in = passenger, out = 123.toReal, isResponse = true)
  }

  it should "build an aggregated feature" in {
    val feature =
      FeatureBuilder.Real[Passenger]
        .extract(p => Option(p.getAge).map(_.toDouble).toReal).aggregate(MaxReal)
        .asPredictor

    assertFeature[Passenger, Real](feature)(name = name, in = passenger, out = 1.toReal, aggregator = _ => MaxReal)
  }

  it should "build an aggregated feature with an aggregate window" in {
    val feature =
      FeatureBuilder.Real[Passenger]
        .extract(p => Option(p.getAge).map(_.toDouble).toReal)
        .window(new Duration(123))
        .asPredictor

    assertFeature[Passenger, Real](feature)(name = name,
      in = passenger, out = 1.toReal, aggregateWindow = Some(new Duration(123)))
  }

  it should "build an aggregated feature with a custom aggregator" in {
    val feature =
      FeatureBuilder.Real[Passenger]
        .extract(p => Option(p.getAge).map(_.toDouble).toReal)
        .aggregate(MaxReal)
        .asPredictor

    assertFeature[Passenger, Real](feature)(name = name, in = passenger, out = 1.toReal, aggregator = _ => MaxReal)
  }

  it should "build an aggregated feature with a custom aggregate function" in {
    val feature =
      FeatureBuilder.Real[Passenger]
        .extract(p => Option(p.getAge).map(_.toDouble).toReal)
        .aggregate((v1, _) => v1)
        .asPredictor

    assertFeature[Passenger, Real](feature)(name = name, in = passenger, out = 1.toReal,
      aggregator = _ => feature.originStage.asInstanceOf[FeatureGeneratorStage[Passenger, Real]].aggregator
    )
  }

  it should "build an aggregated feature with a custom aggregate function with zero" in {
    val feature = FeatureBuilder.Real[Passenger]
      .extract(p => Option(p.getAge).map(_.toDouble).toReal)
      .aggregate(Real.empty.v, (v1, _) => v1)
      .asPredictor

    assertFeature[Passenger, Real](feature)(name = name, in = passenger, out = 1.toReal,
      aggregator = _ => feature.originStage.asInstanceOf[FeatureGeneratorStage[Passenger, Real]].aggregator
    )
  }

}

/**
 * Assert feature instance on a given input/output
 */
object assertFeature extends Matchers {

  /**
   * Assert feature instance on a given input/output
   *
   * @param f               feature to assert
   * @param in              input value
   * @param out             expected output value
   * @param name            expected name
   * @param isResponse      is expected to be a response
   * @param parents         expected parents
   * @param aggregator      expected aggregator
   * @param aggregateWindow expected aggregate window
   * @param tti             expected input type tag
   * @param tto             expected output type tag
   * @param ttov            expected output value type tag
   * @tparam I input type
   * @tparam O output feature type
   */
  // scalastyle:off parameter.number
  def apply[I, O <: FeatureType : FeatureTypeSparkConverter](f: FeatureLike[O])(
    in: I, out: O, name: String, isResponse: Boolean = false,
    parents: Seq[OPFeature] = Nil,
    aggregator: WeakTypeTag[O] => MonoidAggregator[Event[O], _, O] = (wtt: WeakTypeTag[O]) =>
      MonoidAggregatorDefaults.aggregatorOf[O](wtt),
    aggregateWindow: Option[Duration] = None
  )(implicit tti: WeakTypeTag[I], tto: WeakTypeTag[O], ttov: WeakTypeTag[O#Value]): Unit = {
    f.name shouldBe name
    f.isResponse shouldBe isResponse
    f.parents shouldBe parents
    f.uid should startWith(tto.tpe.dealias.toString.split("\\.").last)
    f.wtt.tpe =:= tto.tpe shouldBe true
    f.isRaw shouldBe parents.isEmpty
    f.typeName shouldBe tto.tpe.typeSymbol.fullName
    f.originStage shouldBe a[OpPipelineStage[_]]
    val s = f.originStage.asInstanceOf[OpPipelineStage[_]]
    s.getOutputFeatureName shouldBe name
    s.outputIsResponse shouldBe isResponse

    if (f.isRaw) {
      f.originStage shouldBe a[FeatureGeneratorStage[_, _ <: FeatureType]]
      val fg = f.originStage.asInstanceOf[FeatureGeneratorStage[I, O]]
      fg.tti shouldBe tti
      val aggr = aggregator(tto)
      fg.aggregator shouldBe aggr
      fg.operationName shouldBe s"$aggr($name)"
      fg.extractFn(in) shouldBe out
      // TODO: should eval the 'extractSource' code here. perhaps using scala.tools.reflect.ToolBox?
      fg.extractSource should not be empty
      fg.aggregateWindow shouldBe aggregateWindow
      fg.uid should startWith(classOf[FeatureGeneratorStage[_, _]].getSimpleName)
    } else {
      withClue("Output stage type tags are not as expected: ") {
        f.originStage match {
          case o: HasOutput[_] =>
            o.tto.tpe =:= tto.tpe shouldBe true
            o.ttov.tpe =:= ttov.tpe shouldBe true
          case _ =>
        }
      }
      f.originStage match {
        case t: OpTransformer =>
          val conv = implicitly[FeatureTypeSparkConverter[O]]
          val res = t.transformKeyValue(_ => in)
          conv.fromSpark(res) shouldBe out
        case _ =>
      }
    }
  }

}
