package clusterwise

import scala.util.Random
import breeze.linalg.DenseMatrix
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.collection.immutable.IndexedSeq
import util.control.Breaks._

class Regression(var dsXYTrain:Array[(Int, (Array[Double],Array[Double]))], var h:Int, var g:Int)(var allGroupedData:HashMap[Int,Int], var nbBloc:Int) extends Serializable
{
	val nbMaxAttemps = 30

	type dsPerClassType = Array[(Int, (Array[Double], Array[Double], Int))]
	type classedDStype = Array[(Int, dsPerClassType)]
	type id_x_DS = Array[ArrayBuffer[(Int, Array[Double])]]
	type y_DS = Array[ArrayBuffer[Array[Double]]]
	type regPerClassType = (Double, DenseMatrix[Double], Array[Double], Array[(Int, Array[Double])])
	type classedDSperGrpType = Array[(Int, Array[(Int, Int, Array[(Int, Int, Array[Double], Array[Double])])])]

	val rangeOverClasses = (0 until g).toArray

	val removeLastXY = (_class:Int, inputX:id_x_DS, inputY:y_DS) =>
	{
	  	inputX(_class).remove( inputX(_class).size - 1 )	
	  	inputY(_class).remove( inputY(_class).size - 1 )
	}

	val posInClassForMovingPoints = (currClass:Int, elemNb:Int, limitsClass:Array[Int]) =>
		if( currClass == 0 ) elemNb else elemNb - limitsClass( currClass - 1 ) - 1

	val removeFirstElemXY = (_class:Int, xDS:id_x_DS, yDS:y_DS) =>
	{
		xDS(_class).remove(0)
		yDS(_class).remove(0)
	}

	val prepareMovingPoint = (classedDS:classedDStype, xDS:id_x_DS, yDS:y_DS, g:Int, elemNb:Int, currClass:Int, limitsClass:Array[Int]) =>
	{
		val posInClass = posInClassForMovingPoints(currClass, elemNb, limitsClass)
		val (elemToReplace_ID, (elemToReplace_X, elemToReplace_Y, _)) = classedDS(currClass)._2(posInClass)
		for( j <- 0 until g )
		{
		  if( j == currClass ) removeFirstElemXY(j, xDS, yDS)
		  else
		  {
		    xDS(j) += ( (elemToReplace_ID, elemToReplace_X) )
		    yDS(j) += elemToReplace_Y
		  }
		}
	}
	                          //classedDS:Array[(class, Array[(class, bucketID, Array[(bucketID, ID, X, Y)])])])]
	val prepareMovingPointByGroup = (classedDS:classedDSperGrpType, xDS:id_x_DS,  yDS:y_DS, g:Int, elemNb:Int, currClass:Int, limitsClass:Array[Int], orderedBucketSize:Array[Int]) =>
	{
		val posInClass = posInClassForMovingPoints(currClass, elemNb, limitsClass)
		val elemToReplace = classedDS(currClass)._2(posInClass)._3
		for( j <- 0 until g )
		{
		  if( j == currClass )
		    for( i <- 0 until orderedBucketSize(elemNb) )
		    	removeFirstElemXY(j, xDS, yDS)
		  else
		  {
		    xDS(j) ++= elemToReplace.map{ case(grpId, id, x, y) => (id, x) }
		    yDS(j) ++= elemToReplace.map{ case(grpId, id, x, y) => y }
		  }
		}
	}



	def mbplsPerDot() =
	{
		var continue = true
		var cptAttemps = 0
		var classOfEachData = Array.empty[(Int, Int)]
		var dsPerClassF = Array.empty[dsPerClassType]
		var regPerClassFinal = Array.empty[regPerClassType]
	  	val mapRegCrit = HashMap.empty[Int,Double]
	  	

		do
		{
			try
			{
				cptAttemps += 1
			  	// Set randomly a class to each data point
			  	val classedDS = dsXYTrain.map{ case (id, (x, y)) => (id, (x, y, Random.nextInt(g)))}.sortBy{ case (id, (x, y, _class)) => _class}
			  	val valuesToBrowse = classedDS.map{  case (id, (x, y, _class)) => (id, _class) }
			  	//val valuesToBrowseMap = HashMap(valuesToBrowse.map(_._1).zipWithIndex:_*)
			  	//val tmpError = ArrayBuffer.empty[IndexedSeq[(Int, Int, Double, Double)]]	
				val dsPerClass = classedDS.groupBy{ case (id, (x, y, _class)) => _class }.toArray.sortBy{ case (_class, id__x_y_class) => _class }
				//val classPosByID = HashMap.empty[Int, (Int, Int)]
			  	val inputX = dsPerClass.map{ case (_class, id__x_y_class) => ArrayBuffer(id__x_y_class.map{ case (id, (x, y, _class))  => (id, x) }:_*) }
			  	val inputY = dsPerClass.map{ case (_class, id__x_y_class) => ArrayBuffer(id__x_y_class.map{ case (id, (x, y, _class)) => y }:_*) }
			  	val preLimitsClass = (for( i <- 0 until inputY.size ) yield( inputY(i).size )).toArray
			  	val limitsClass = (for( i <- 0 until preLimitsClass.size ) yield( (for( j <- 0 to i ) yield( preLimitsClass(j) )).reduce(_ + _) )).map(_ - 1).toArray
			  	var currentDotIdx = 0
			  	var currentClass = 0
			  	var nbIte = 0
			  	val stop = valuesToBrowse.size - 1

		  		breakable
		  		{
			  		if( inputX.size != g ) break
				  	
				  	while( continue && nbIte != stop )
				  	{
					  	val (current_dot_id, current_dot_class) = valuesToBrowse(nbIte)
					  	val regPerClass = for( i <- rangeOverClasses ) yield( Mbpls.prepareAndLaunchMbpls(inputX, inputY, i, h) )
					  	// Temporary WorkAround when reduce data
					  	if( ! regPerClass.map(_._1).filter(_.isNaN).isEmpty ) break
					  	
					  	val error1 = regPerClass.map(_._1)
					  	prepareMovingPoint(dsPerClass, inputX, inputY, g, currentDotIdx, currentClass, limitsClass)
					  	val regPerClass2 =
					  		try for( i <- rangeOverClasses ) yield( Mbpls.prepareAndLaunchMbpls(inputX, inputY, i, h) )
					  		catch { case emptyClass : java.lang.IndexOutOfBoundsException => Array.empty[regPerClassType] }
					  	if( regPerClass2.isEmpty ) break
					  	val error2 = regPerClass2.map(_._1)
					  	val boolTab = Array.fill(g)(true)
					  	val errorsIdx = error1.zip(error2).zipWithIndex
					  	//tmpError += errorsIdx.map{ case ((err1, err2), idx) => (current_dot_id, idx, err1, err2) }
					  	boolTab(current_dot_class) = false
					  	val errors = for( i <- rangeOverClasses ) yield(
					  	{
					  		if( i == current_dot_class ) errorsIdx.map{ case ((err1, err2), idx) => err1 }
					  		else
					  		{
						  		var b = true
						  		errorsIdx.map{ case ((err1, err2), idx) =>
						  			{
						  				if( idx == current_dot_class ) err2
							  			else
							  			{
								  			if( boolTab(idx) && b )
								  			{
								  				boolTab(idx) = false
								  				b = false
								  				err2
								  			}
								  			else err1
						  				}
					  				}
								}
					  		}
					  	}).fold(0D)(_ + _)
					  	val minError = errors.min
					  	val classToMovePointInto = errors.indexOf(minError)
					  	val (point_ID, (point_X, point_Y, _)) = classedDS(currentDotIdx)
					  	if( classToMovePointInto != current_dot_class )
					  	{
						  	classedDS(currentDotIdx) = (point_ID, (point_X, point_Y, classToMovePointInto))
						  	val classWithoutDot = rangeOverClasses.filter( _class => _class != classToMovePointInto && _class != current_dot_class)
						  	for( j <- classWithoutDot ) removeLastXY(j, inputX, inputY)
					  	}
					  	else
					  	{
						  	val classWithoutDot = rangeOverClasses.filter(_ != current_dot_class)
						  	for( j <- classWithoutDot ) removeLastXY(j, inputX, inputY)
							inputX(current_dot_class) += ( (point_ID, point_X) )
							inputY(current_dot_class) += point_Y
					  	}
					  	continue = inputX.filter(_.isEmpty).isEmpty
					  	mapRegCrit += ( current_dot_id -> minError )
					  	nbIte += 1
				  		currentDotIdx += 1
				  		if( currentDotIdx > limitsClass(currentClass) ) currentClass += 1
				  	}
			  	}
			  	continue = nbIte != stop
			  	if( continue ) mapRegCrit.clear
			  	else
			  	{
					dsPerClassF = for( i <- rangeOverClasses ) yield( classedDS.filter{ case (_, (_, _, _class)) => _class == i } )
					regPerClassFinal = for( i <- rangeOverClasses ) yield( Mbpls.prepareAndLaunchMbpls(inputX, inputY, i, h) )
					classOfEachData = classedDS.map{ case (id, (_, _, _class)) => (id, _class) }	
			  	}
			}
			catch { case svdExcept : breeze.linalg.NotConvergedException => {
				mapRegCrit.clear
				println("\nThere was an Singular Value Decomposition Issue, retry with new initialisation")
				}
			}
		  	// On refait la regression sur le dataset relabeliser une fois
		}
		while( continue && cptAttemps < nbMaxAttemps )

		if( continue && cptAttemps == nbMaxAttemps ) throw new Exception("There was too many unsuccesufull attemps due to empty classes, try to diminish number of class ")
		

	  	val resReg = regPerClassFinal.map(_._1)
	  	val coXYcoef = regPerClassFinal.map(_._2.toArray)
	  	val coIntercept = regPerClassFinal.map(_._3.toArray)
	  	val pred = regPerClassFinal.map(_._4)	

	  	(dsPerClassF, pred, coIntercept, coXYcoef, resReg, mapRegCrit, classOfEachData)

	}

	def mbplsPerGroup() =
	{
		var continue = true
		var cptAttemps = 0
		var classOfEachData = Array.empty[(Int, Int)]
		var dsPerClassF = Array.empty[classedDSperGrpType]
		var regPerClassFinal = Array.empty[regPerClassType]
	  	val mapRegCrit = HashMap.empty[Int,Double]	

	  	do
	  	{
			try
			{
		  		cptAttemps += 1
			  	// Initialisation par groupe
			  	val perGroupClassInit = for( i <- 0 until nbBloc ) yield(Random.nextInt(g))

			  	// Array[(Int, Array[(Int, Int, Array[(Int, Int, Array[Double], Array[Double])])])]
			  	// Array[(class,Array[(class, bucketID, Array[(bucketID, ID, X, Y)])])])]
			  	val dsPerClassPerBucket = dsXYTrain.map{ case (id, (x, y)) => (allGroupedData(id), id, x, y) }
							  						.groupBy(_._1)
							  						.toArray
							  						.map{ case (grpId, grpId_id_x_y) => (perGroupClassInit(grpId), grpId, grpId_id_x_y) }
							  						.groupBy{ case (_class, grpId, grpId_id_x_y) => _class }
							  						.toArray
							  						.sortBy{ case (_class, buckets) => _class }
				// Array[class,Array[BucketID]]
				val bucketOrderPerClass = (for( (_class, buckets) <- dsPerClassPerBucket ) yield( (_class, buckets.map{ case (_class, grpId, grpId_id_x_y) => grpId }) )).toArray
				// Array[(BucketID,class),loopIdx]
				val indexedFlatBucketOrder = bucketOrderPerClass.flatMap{ case (_class, grpIds) => grpIds.map( grpId => (grpId, _class) ) }.zipWithIndex

				val preSize = dsPerClassPerBucket.map{ case (_class, dsPerBucket) => dsPerBucket.map{ case (_class, grpId, ds) => ds.size } }
				val orderedBucketSize = preSize.flatMap(x => x)
				val classSize = preSize.map(_.size )
				val classSize2 = preSize.map(_.reduce(_ + _))
				val limitsClass = (for( i <- 0 until classSize.size ) yield( (for( j <- 0 to i ) yield( classSize(j) )).reduce(_ + _) )).map(_ - 1).toArray

				// Array[ArrayBuffer[(Int,Array[Double])]]

			  	val inputX = dsPerClassPerBucket.map{ case (_class, dsPerBucket) => ArrayBuffer(dsPerBucket.flatMap{ case (_class, grpId, ds) => ds.map{ case (grpId, id, x, y) => (id, x) }}:_*)}
			  	val inputY = dsPerClassPerBucket.map{ case (_class, dsPerBucket) => ArrayBuffer(dsPerBucket.flatMap{ case (_class, grpId, ds) => ds.map{ case (grpId, id, x, y) => y }}:_*)}

			  	var currentDotsGrpIdx = 0
			  	var currentClass = 0
			  	var nbIte = 0
				val stop = indexedFlatBucketOrder.size - 1
			  	//val tmpError = ArrayBuffer.empty[Array[(Int,Int,Double,Double)]]	
		  		breakable
		  		{
			  		// if init starts with empty classes we retry
			  		if( inputX.size != g ) break

				  	while( continue && nbIte != stop )
				  	{
				  		val ((grpId, current_class), current_idx) = indexedFlatBucketOrder(nbIte)
				  	
					  	// Regression with Point inside one Class and not the Rest
					  	val regPerClass = for( i <- rangeOverClasses ) yield( Mbpls.prepareAndLaunchMbpls(inputX, inputY, i, h) )
					  	val error1 = regPerClass.map(_._1)
					  	prepareMovingPointByGroup(dsPerClassPerBucket, inputX, inputY, g, currentDotsGrpIdx, currentClass, limitsClass, orderedBucketSize)
					  	// Regression with Point inside all other Class and not the former
					  	val regPerClass2 = 
					  		try for( i <- rangeOverClasses ) yield( Mbpls.prepareAndLaunchMbpls(inputX, inputY, i, h) )
					  		catch { case emptyClass : java.lang.IndexOutOfBoundsException => Array.empty[regPerClassType] }
					  	if( regPerClass2.isEmpty ) break
			  	  		val error2 = regPerClass2.map(_._1)
					  	val boolTab = Array.fill(g)(true)
					  	val errorsIdx = error1.zip(error2).zipWithIndex
					  	//tmpError += errorsIdx.map{ case ((err1, err2), idx) => (current_idx, idx, err1, err2) }
					  	boolTab(current_class) = false
					  	val errors = for( i <- rangeOverClasses ) yield(
					  	{
					  		if( i == current_class ) errorsIdx.map{ case ((err1, err2), idx) => err1 }
					  		else
					  		{
						  		var b = true
						  		errorsIdx.map{ case ((err1, err2), idx) =>
						  			{
							  			if( idx == current_class ) err2
							  			else
							  			{
								  			if(boolTab(idx) && b)
								  			{
								  				boolTab(idx) = false
								  				b = false
								  				err2
								  			}
								  			else err1
							  			}
					  				}
					  			}
					  		}
					  	}).fold(0D)(_ + _)
					  	val minError = errors.min
					  	val classToMoveGroupInto = errors.indexOf(minError)
					  	//val posInClass = if( currentClass == 0 ) currentDotsGrpIdx else currentDotsGrpIdx - limitsClass(currentClass - 1) -1
					  	val posInClass = posInClassForMovingPoints(currentClass, currentDotsGrpIdx, limitsClass)
					  	// Array[(class,Array[(class, bucketID, Array[(bucketID, ID, X, Y)])])])]
					  	val (_, bucketIDtoUpdate, grpId_ID_X_Y) = dsPerClassPerBucket(currentClass)._2(posInClass)
					  	if( classToMoveGroupInto != current_class )
					  	{
						  	dsPerClassPerBucket(currentClass)._2(posInClass) = (classToMoveGroupInto, bucketIDtoUpdate, grpId_ID_X_Y)
						  	val classWithoutDots = rangeOverClasses.filter( _class => _class != classToMoveGroupInto && _class != current_class)
						  	for( j <- classWithoutDots )
						  		for( i <- 0 until orderedBucketSize(currentDotsGrpIdx) ) removeLastXY(j, inputX, inputY)
					  	}
					  	else
					  	{
						  	val classWithoutDots = rangeOverClasses.filter(_ != current_class)
						  	for( j <- classWithoutDots )
						  		for( i <- 0 until orderedBucketSize(currentDotsGrpIdx) ) removeLastXY(j, inputX, inputY)
						  	inputX(current_class) ++= grpId_ID_X_Y.map{ case (grpId, id, x, y) => (id, x) }
						  	inputY(current_class) ++= grpId_ID_X_Y.map{ case (grpId, id, x, y) => y }
					  	}
					  	mapRegCrit += ( current_idx -> minError )
					  	continue = inputX.filter(_.isEmpty).isEmpty
						nbIte += 1
				  		currentDotsGrpIdx += 1
				  		if( currentDotsGrpIdx > limitsClass(currentClass) ) currentClass += 1
			  		}
			  	}
			  	continue = nbIte != stop
			  	if( continue ) mapRegCrit.clear
			  	else
			  	{
			  		dsPerClassF = for( i <- rangeOverClasses ) yield( dsPerClassPerBucket.filter{ case (_class, _) => _class == i } )
					regPerClassFinal = for( i <- rangeOverClasses ) yield( Mbpls.prepareAndLaunchMbpls(inputX, inputY, i, h) )
			  		classOfEachData = dsPerClassPerBucket.flatMap{ case (_class, dsPerBucket) => dsPerBucket.flatMap{ case (_, grpId, ds) => ds.map{ case (_, id, x, y) => (id,_class) } } }
			  	}
			}
			catch { case svdExcept : breeze.linalg.NotConvergedException => {
				mapRegCrit.clear
				println("\nThere was an Singular Value Decomposition Issue, retry with new initialisation")
				}
			}
		}
		while( continue && cptAttemps < nbMaxAttemps )

		if( continue && cptAttemps == nbMaxAttemps ) throw new Exception("There was too many unsuccesufull attemps due to empty classes, try to diminish number of class or size of blocs")


	  	val resReg = regPerClassFinal.map(_._1)
	  	val coXYcoef = regPerClassFinal.map(_._2.toArray)
	  	val coIntercept = regPerClassFinal.map(_._3.toArray)
	  	val pred = regPerClassFinal.map(_._4)

	  	(dsPerClassF, pred, coIntercept, coXYcoef, resReg, mapRegCrit, classOfEachData)
	}
/*
	def mbplsPerDotLSH(dsXYTrain:Array[((Array[Double],Array[Double]),Int)],
					tabHash:Array[Array[Double]],
					w:Double,
					b:Double,
					sizeBloc:Int,
					n:Int,
					blo:Int,
					h:Int,
					g:Int,
					p:Int,
					lw:Double) = {

  		val tin1 = System.nanoTime
	  	// Set randomly a 50class to each data
	  	var classedDS = dsXYTrain.map(x=>(x._2,(x._1._1,x._1._2,Random.nextInt(g))))
	  	val valuesToBrowse = classedDS.map(_._1)
	  	val valuesToBrowseMap = valuesToBrowse.zipWithIndex.toMap		  	
	  	for(k<-valuesToBrowse) {
		  	val dsPerClass = (for(i<-0 to g-1) yield(classedDS.filter(_._2._3==i))).toArray
		  	val inputX = dsPerClass.map(_.map(x => (x._1,x._2._1)))
		  	val inputY = dsPerClass.map(_.map(_._2._2))

		  	val regPerClass = for(i<-0 to g-1) yield(MyFct.prepareAndLaunchMbpls(inputX, inputY, i, blo, lw, h))
		  	val error1 = regPerClass.toArray.map(_._1)
		  	val dsPerClassLSH = (for(i<-0 to g-1) yield(MyFct.prepareLSHbloc(dsPerClass, tabHash, i, sizeBloc, w, b))).toArray		  	
		  	val errorsLSH = (for(i<-0 to g-1) yield(MyFct.prepareAndLaunchMbplsPerLSHgroupPerClass(dsPerClassLSH, i, blo, lw, h))).toArray
		  	val comparaison = error1.zip(errorsLSH).map(x=>(x._1,x._2,x._1-x._2))
		  	//comparaison.foreach(println)
		  	/*
			*/


		  	val current_dot_class = classedDS.find(_._1==k).get._2._3
		  	val dsPerClass2 = MyFct.prepareMovingPoint(dsPerClass, current_dot_class, k, g)
		  	val inputX2 = dsPerClass2.map(_.map(_._2._1))
		  	val inputY2 = dsPerClass2.map(_.map(_._2._2))
		  	val regPerClass2 = for(i<-0 to g-1) yield(MyFct.prepareAndLaunchMbpls(inputX2, inputY2, i, blo, lw, h))
		  	val error2 = regPerClass2.toArray
		  	val errors = error1 ++ error2
		  	val minError = errors.min
		  	val idxError = errors.indexOf(minError)
		  	val classToMovePointInto = idxError % g
		  	val arrayPositionPoint = valuesToBrowseMap(k)
		  	val pointValues = classedDS(arrayPositionPoint)
		  	classedDS(arrayPositionPoint) = (point_ID,(point_Xoint_Y, classToMovePointInto))
	  	}
//		val tin2 = System.nanoTime
//		val res01 = (tin2-tin1)/1000000
		//ac1 += res01
	}
*/
}
