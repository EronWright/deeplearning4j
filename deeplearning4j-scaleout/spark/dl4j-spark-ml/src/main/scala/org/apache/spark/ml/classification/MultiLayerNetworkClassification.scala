package org.apache.spark.ml.classification

import scala.collection.JavaConversions._

import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.ml.param._
import org.apache.spark.ml.nn._
import org.apache.spark.mllib.linalg.{VectorUDT, BLAS, Vector, Vectors}
import org.apache.spark.sql.types.{DataType, DoubleType, FloatType, IntegerType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.storage.StorageLevel

import org.deeplearning4j.nn.conf.MultiLayerConfiguration
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.spark.util.MLLibUtil

import org.nd4j.linalg.util.FeatureUtil
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.api.ndarray.INDArray

private[classification] trait NeuralNetworkClassificationParams extends ProbabilisticClassifierParams 
  with HasMultiLayerConfiguration 
  with HasBatchSize
  with HasWindowSize {
}

@AlphaComponent
class NeuralNetworkClassification
  extends ProbabilisticClassifier[Vector, NeuralNetworkClassification, NeuralNetworkClassificationModel]
  with NeuralNetworkClassificationParams {
  
  override protected def train(dataset: DataFrame, paramMap: ParamMap): NeuralNetworkClassificationModel = {
    val sqlContext = dataset.sqlContext
    var sc = sqlContext.sparkContext
    
    // compute the effective parameter values
    val map = this.paramMap ++ paramMap
    val conf = map(confParam)
    val batchSize = map(batchSizeParam)
    
    // prepare the dataset for classification
    val prepared = dataset.select(map(labelCol), map(featuresCol))
    val numClasses = conf.getConf(conf.getConfs().size() - 1).getnOut() // TODO - use ML column metadata 'numValues'
    
    // devise a training strategy for the distributed neural network
    val trainingStrategy = new ParameterAveragingTrainingStrategy[Row](
        conf, 
        map(windowSizeParam), 
        conf.getConf(0).getNumIterations())

    // train
    val networkParams = trainingStrategy.train(
        prepared.rdd, (network:MultiLayerNetwork, rows:Iterator[Row]) => {
          
          // features
          val featureArrays = rows.map(row => MLLibUtil.toVector(row.getAs[Vector](1)))
          val featureMatrix = Nd4j.vstack(featureArrays.toArray: _*)
          
          // labels
          val labelArrays = rows.map(row => FeatureUtil.toOutcomeVector(row.getDouble(0).toInt, numClasses))
          val labelMatrix = Nd4j.vstack(labelArrays.toArray: _*)
          
          network.fit(featureMatrix, labelMatrix)
    })
    
    new NeuralNetworkClassificationModel(this, map, numClasses, networkParams)

  }
}

/**
 * Note: the model is not thread-safe (MultiLayerNetwork is mutated at each prediction).
 */
@AlphaComponent
class NeuralNetworkClassificationModel private[ml] (
    override val parent: NeuralNetworkClassification,
    override val fittingParamMap: ParamMap,
    override val numClasses: Int,
    val networkParams: INDArray)
  extends ProbabilisticClassificationModel[Vector, NeuralNetworkClassificationModel]
  with NeuralNetworkClassificationParams {
  
  val network = new MultiLayerNetwork(paramMap(confParam))
  network.init()
  network.setParameters(networkParams)
  
  override protected def predict(features: Vector): Double = {
    val examples: INDArray = MLLibUtil.toVector(features)
    
    val predictions = network.predict(examples)
    predictions(0)
  }
  
  override protected def predictRaw(features: Vector): Vector = {
    val examples: INDArray = MLLibUtil.toVector(features)
    
    val raw = network.feedForward(examples)
    MLLibUtil.toVector(raw.get(0))
  }
  
  override protected def predictProbabilities(features: Vector): Vector = {
    
    val examples: INDArray = MLLibUtil.toVector(features)
    
    val probabilities: INDArray = network.labelProbabilities(examples)
    MLLibUtil.toVector(probabilities)
  }
  
  override protected def copy(): NeuralNetworkClassificationModel = {
    val m = new NeuralNetworkClassificationModel(parent, fittingParamMap, numClasses, networkParams)
    Params.inheritValues(this.paramMap, this, m)
    m
  }
}