package org.clustering4ever.indices
/**
 * @author Beck Gaël
 */
import scala.language.higherKinds
import scala.math.max
import scala.reflect.ClassTag
import scala.collection.{mutable, GenSeq}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.clustering4ever.clustering.ClusteringCommons
import org.clustering4ever.util.ClusterBasicOperations
import org.clustering4ever.math.distances.GenericDistance
/**
 * This object is used to compute internals clustering indices as Davies Bouldin or Silhouette
 */
case class InternalIndicesDistributed[O : ClassTag, D <: GenericDistance[O]](clusterized: RDD[(Int, O)], metric: D, clustersIDsOp: Option[mutable.ArraySeq[Int]] = None) extends InternalIndicesCommons[O, D] {
  /**
   *
   */
  lazy val clustersIDs = if(clustersIDsOp.isDefined) clustersIDsOp.get else mutable.ArraySeq(clusterized.map(_._1).distinct.collect:_*).sorted
  /**
   *
   */
  private[this] def addToBuffer[O](buff: mutable.ArrayBuffer[O], elem: O) = buff += elem
  /**
   *
   */
  private[this] def aggregateBuff[O](buff1: mutable.ArrayBuffer[O], buff2: mutable.ArrayBuffer[O]) = buff1 ++= buff2
  /**
   *
   */
  lazy val daviesBouldin: SparkContext => Double = sc => {
    if(clustersIDs.size == 1) {
      println(" One Cluster found")
      0D
    }
    else {
      val neutralElement = mutable.ArrayBuffer.empty[O]
      val neutralElement2 = mutable.ArrayBuffer.empty[Double]
      def addToBuffer2(buff: mutable.ArrayBuffer[Double], elem: Double) = buff += elem
      def aggregateBuff2(buff1: mutable.ArrayBuffer[Double], buff2: mutable.ArrayBuffer[Double]) = buff1 ++= buff2

      val clusters = clusterized.aggregateByKey(neutralElement)(addToBuffer, aggregateBuff).collect
      val centers = clusters.map{ case (clusterID, cluster) => (clusterID, ClusterBasicOperations.obtainCenter(cluster, metric)) }
      val scatters = clusters.zipWithIndex.map{ case ((k, v), idCLust) => (k, scatter(v, centers(idCLust)._2, metric)) }
      val clustersWithCenterandScatters = (centers.map{ case (id, ar) => (id, (Some(ar), None)) } ++ scatters.map{ case (id, v) => (id, (None, Some(v))) })
        .par
        .groupBy(_._1)
        .map{ case (id, aggregate) =>
          val agg = aggregate.map(_._2)
          val a = agg.head
          val b = agg.last
          if(a._1.isDefined) (id, (b._2.get, a._1.get)) else (id, (a._2.get, b._1.get))
        }
      val cart = for(i <- clustersWithCenterandScatters; j <- clustersWithCenterandScatters if(i._1 != j._1)) yield (i, j)
      val rijList = sc.parallelize(cart.seq.toSeq).map{ case ((idClust1, (centroid1, scatter1)), (idClust2, (centroid2, scatter2))) => (idClust1, good(centroid1, centroid2, scatter1, scatter2, metric)) }
      val di = rijList.aggregateByKey(neutralElement2)(addToBuffer2, aggregateBuff2).map{ case (_, goods)=> goods.reduce(max(_, _)) }
      val numCluster = clustersIDs.size
      val daviesBouldinIndex = di.sum / numCluster
      daviesBouldinIndex
    }
  }

  lazy val ballHall: Double = {
    val neutralElement = mutable.ArrayBuffer.empty[O]
    val clusters = clusterized.aggregateByKey(neutralElement)(addToBuffer, aggregateBuff).cache
    val prototypes = clusters.map{ case (clusterID, cluster) => (clusterID, ClusterBasicOperations.obtainCenter(cluster, metric)) }.collectAsMap
    clusters.map{ case (clusterID, aggregate) => aggregate.map( v => metric.d(v, prototypes(clusterID)) ).sum / aggregate.size }.sum / clusters.count
  }
}
/**
 *
 */
object InternalIndicesDistributed extends ClusteringCommons {
  /**
   * Monothreaded version of davies bouldin index
   * Complexity O(n.c<sup>2</sup>) with n number of individuals and c the number of clusters
   */
  def daviesBouldin[O: ClassTag, D <: GenericDistance[O]](sc: SparkContext, clusterized: RDD[(ClusterID, O)], metric: D): Double = { 
    val internalIndices = new InternalIndicesDistributed(clusterized, metric)
    internalIndices.daviesBouldin(sc)
  }
  /**
   *
   */
  def ballHall[O: ClassTag, D <: GenericDistance[O]](clusterized: RDD[(ClusterID, O)], metric: D): Double = {
    (new InternalIndicesDistributed(clusterized, metric)).ballHall
  }

}