

第 3/5 批
  def loadLocalConfigFile(spark: SparkSession,path:String): (mutable.Map[String, Any], mutable.Map[String, Int], mutable.Map[String, Int], mutable.Map[String, Int], mutable.Map[String, Int], mutable.Map[String, mutable.Map[String, Array[Double]]], String, mutable.Set[String]) ={

    var featureIdxOverflow = mutable.Set.empty[String]
    // load the given date json file to init the map
    val featureMap = Utils.parseJsonToAnyMap(Utils.loadJsonToString(spark, path + "/featureMap.json"))
    val featureMaxIndexMap = Utils.parseJsonToIntMap(Utils.loadJsonToString(spark, path + "/featureMaxIndexMap.json"))
    val realFeatureMaxIdxMap = Utils.parseJsonToIntMap(Utils.loadJsonToString(spark, path + "/realFeatureMaxIndexMap.json"))
    val seqMaxLenMap = Utils.parseJsonToIntMap(Utils.loadJsonToString(spark, path + "/seqMaxLenMap.json"))
    val hashEncodeMap = Utils.parseJsonToIntMap(Utils.loadJsonToString(spark, path + "/hashEncodeMap.json"))
    val meanAndStdMap = Utils.parseJsonToMeanStdMap(Utils.loadJsonToString(spark, path + "/meanAndStdMap.json"))
    val inputFeatIdxOverflow = Utils.loadJsonToString(spark, path + "/featureIdxOverflow.txt")
    if (inputFeatIdxOverflow.nonEmpty) {
      featureIdxOverflow = featureIdxOverflow.++(inputFeatIdxOverflow.split(","))
    }
    LOGGER.info("Load all jsons successfully")
    (featureMap, featureMaxIndexMap, realFeatureMaxIdxMap, seqMaxLenMap, hashEncodeMap, meanAndStdMap, inputFeatIdxOverflow, featureIdxOverflow)
  }

  override def getTaskDesc: String = "特征处理与转换"

  override def getTaskApiType: TaskApiType = TaskApiType.FEATURE_ENGINEERING

  def loadHiveData(spark: SparkSession, sql: String, dataType: String = "train",partitionNum: Int ,isCache: Boolean, storageLevel: String, inputDF: DataFrame): DataFrame = {
    val tableInfoView = "tableInfoView"
    if (sql == null)
      return null
    if (inputDF != null) {
      require(sql.contains(tableInfoView), s"$dataType sql without tableInfoView, this used for input dataframe, please check!!")
      inputDF.createOrReplaceTempView(tableInfoView)
    }
    val hiveData = if(partitionNum > 0) {
      spark.sql(sql).repartition(partitionNum)
    }else{
      spark.sql(sql)
    }
    if (isCache) {
      hiveData.persist(StorageLevel.fromString(storageLevel))
    }
    hiveData
  }

  def splitMaterializedColumns(df: DataFrame, materializedColumns: Map[String, String]): DataFrame = {
    val zippedCol = s"zipper_${UUID.randomUUID().toString.replace("-", "")}"
    val explodedCol = s"exploded_${UUID.randomUUID().toString.replace("-", "")}"
    // 确保只处理存在的列
    val validColumns = materializedColumns.filter { case (colName, _) => df.columns.contains(colName) }
    if (validColumns.isEmpty) return df
    // 处理每个列，使用对应的分隔符
    val processedDF = validColumns.foldLeft(df) {
      case (tempDF, (colName, separator)) =>
        tempDF.withColumn(colName,
          when(col(colName).isNull, array(lit("")))
            .otherwise(split(functions.trim(col(colName)), Pattern.quote(separator)))
        )
    }

    // 将所有处理后的列打包成一个数组列
    val zippedDF = processedDF.withColumn(zippedCol, arrays_zip(validColumns.keys.toSeq.map(col): _*))

    // 添加分组列
    val processedDFWithGroup = zippedDF.withColumn(Const.GROUP_ID, monotonically_increasing_id())

    // 展开数组列
    val explodedDF = processedDFWithGroup.withColumn(explodedCol, explode(col(zippedCol))).drop(zippedCol)
    // 选择需要的列，将展开后的列替换回原列名
    val selectExprs = df.columns.map {
      c =>
        if (validColumns.contains(c)) {
          col(s"$explodedCol.$c").as(c)
        } else {
          col(c)
        }
    } ++ Array(col(Const.GROUP_ID))
    explodedDF.select(selectExprs: _*)
  }


  private def buildMissingValueSqlBuffer(columns: Array[String], labelCol: Array[String], curCnt: Long): String = {
    var sqlTemplate = new StringBuilder()
    for (col <- columns) {
      val curDft = defaultVal4Features.getOrElse(col, null)
      sqlTemplate.append(s"round(sum(case when ${col} is null or ${col} = 'null' " +
        s"or ${col} = ${curDft} then 1 else 0 end)/${curCnt}, 5) as ${col} ,")
    }
    sqlTemplate = sqlTemplate.deleteCharAt(sqlTemplate.length - 1)
    sqlTemplate.toString()
  }

  private def fillDefaultValue(df: DataFrame, featureDefaultValue: Map[String, Feature], label: Array[String], uniqueIds: Array[String], dataType: String): DataFrame = {
    // 根据feature_type来判断 默认的default值：`missing` or `0`
    // 填充null
    val columnDefaults = df.columns.map(col => col -> {
      val curFeature = featureDefaultValue.getOrElse(col, null)
      // col为label列时, curFeature一定是null, 因为modelFeatureSet中没有label
      if(uniqueIds.contains(col)){
        CATEGORICAL_DEFAULT
      }else if(label.contains(col)){
        DENSE_DEFAULT
      }else if(null != curFeature && null != curFeature.dft && Strings.EMPTY != curFeature.dft){
        curFeature.dft
      }else if(null != curFeature && TransformParamModule.DENSE.equals(curFeature.feature_type)){
        DENSE_DEFAULT
      }else{
        CATEGORICAL_DEFAULT
      }
    }).toMap

    if(dataType.equals("train")){
      columnDefaults.foreach(pair => info(s"columnDefaults contains ${pair._1}, ${pair._2}"))
    }

    val outputCols = df.columns.map { colName =>
      val defaultValue = columnDefaults(colName)
      val colExpr = coalesce(
        when(
          df(colName).isNull ||        // 原生null
            trim(df(colName)) === "" ||  // 空字符串（包括全空格）
            lower(trim(df(colName))) === "null", // 处理各种"null"变体
          lit(defaultValue)
        ),
        df(colName)
      ).as(colName)
      colExpr
    }

    df.select(outputCols: _*)
  }

  private def buildOtherStatSqlBuffer(columns: Array[String], labelCol: Array[String], curCnt: Long): String = {
    var sqlTemplate = new StringBuilder()
    // 支持多标签下的正负样本比率等指标的计算
    for (curLabel <- labelCol) {
      sqlTemplate.append(s"round(sum(case when ${curLabel} = 1 then 1 else 0 end) / sum(case when ${curLabel} = 0 then 1 else 0 end),5) as pos_to_neg_of_${curLabel}," +
        s"round(sum(case when ${curLabel} = 1 then 1 else 0 end)/${curCnt},4) as cvr_or_ctr_of_${curLabel},")
    }
    sqlTemplate = sqlTemplate.deleteCharAt(sqlTemplate.length - 1)
    sqlTemplate.toString()
  }

  private def buildMoreInfoStatSqlBuffer(columns: Array[String], labelCol: Array[String], curCnt: Long): String = {
    var sqlTemplate = new StringBuilder()
    // 支持多标签下的正负样本比率等指标的计算
    for (curLabel <- labelCol) {
      sqlTemplate.append(s"round(sum(case when ${curLabel} = 1 then 1 else 0 end) / sum(case when ${curLabel} = 0 then 1 else 0 end),5) as pos_to_neg_of_${curLabel}," +
        s"round(sum(case when ${curLabel} = 1 then 1 else 0 end)/count(*),4) as cvr_or_ctr_of_${curLabel}, count(*) as dataCount, sum(case when ${curLabel} = 1 then 1 else 0 end) as positiveCount, pt_h,")
    }
    sqlTemplate = sqlTemplate.deleteCharAt(sqlTemplate.length - 1)
    sqlTemplate.toString()
  }

  /**
   * meta_training
   *
   * @param spark             spark session
   * @param trainData         trainData
   * @param minBatch          minBatch from parameter
   * @param meta_feature_list meta_feature_list
   * @return
   */

  private def stratifiedMetaFeatureSampling(spark: SparkSession, trainData: DataFrame, minBatch: Integer, meta_feature_list: Array[String], isDebugMode: Boolean): (DataFrame, DataFrame) = {
    // 0.'unique_id' are pseudo-unique.
    val trainDF = trainData.dropDuplicates(Seq("column_id"))
    // 1.store original columns
    val originalColumnsArray = trainDF.columns

    // 2.get groupDF based on minBatch and meta_feature_list
    val sqlBuffProject = meta_feature_list.mkString(",")
    trainDF.createOrReplaceTempView("trainDF")
    val groupDF = spark.sql(s"select ${sqlBuffProject},(2*${minBatch}/cnt) as ratio,row_number() over(order by cnt desc) as groupId, cnt from " +
      s"(select ${sqlBuffProject}, count(1) as cnt from trainDF group by ${sqlBuffProject} having count(1) >= ${2 * minBatch})t")

    // 3.store fraction of each group for stratified sampling
    val fraction = groupDF.select("groupId", "ratio").rdd.map(row => (row.get(0).toString, row.get(1).toString.toDouble)).collectAsMap()

    // 4.apply count-satisfied group to source dataframe joined through meta_feature_list
    val uniqueDFAfterFilterLessGroup = trainDF.join(groupDF, meta_feature_list.toSeq, "inner").select("column_id", "groupId")

    // 5.apply stratified sampling
    val res = uniqueDFAfterFilterLessGroup.rdd.map(row => (row.get(1).toString, row.get(0).toString)).sampleByKeyExact(false, fraction)

    // 6.apply sampled dataframe to source dataframe joined through uniqueId.
    import spark.implicits._
    val resDF = res.toDF("groupId", "column_id")
    val finalSampleData = resDF.join(trainDF, "column_id")

    // 7.split sampled dataframe into two child dataframe for each meta group.
    val windowSpec = Window.partitionBy("groupId").orderBy("column_id")
    finalSampleData.withColumn("inGroupId", row_number() over windowSpec).createOrReplaceTempView("finalSampleData")


    var groupA = spark.sql(s"select * from finalSampleData where inGroupId <= ${minBatch}")
    var groupB = spark.sql(s"select * from finalSampleData where inGroupId > ${minBatch} and inGroupId <= ${2 * minBatch}")

    groupA = groupA.select(originalColumnsArray.map(col): _*).orderBy(meta_feature_list.map(col): _*)
    groupB = groupB.select(originalColumnsArray.map(col): _*) orderBy (meta_feature_list.map(col): _*)

    (groupA, groupB)
  }

  def info(msg: String): Unit = {
    LogUtil.infoLog(LOGGER, msg)
  }
}

object FeatureFitAndTransformNew {
  private val LOGGER = LoggerFactory.getLogger(this.getClass)

  /**
   * fit accumulate_encoded features [全量任务]
   *
   * @param spark                  spark session
   * @param input                  input dataframe
   * @param features               features fed into accumulate encode
   * @param featureMap             featureMap for full transform task
   * @param featureMapExtra        featureMap for full transform task
   * @param featureMaxIndexMap     maxIdx after perform accumulateBufPercent
   * @param realFeatureMaxIndexMap real maxIdx
   * @param seqMaxLenMap           seqMaxLenMap
   * @param minCount               minCount
   * @return
   */
  def fitAccumulatedFeaturesByFull(spark: SparkSession,
                                   input: DataFrame,
                                   features: Array[String],
                                   featureMap: mutable.Map[String, Any],
                                   featureMapExtra: mutable.Map[String, Any],
                                   featureMaxIndexMap: mutable.Map[String, Int],
                                   realFeatureMaxIndexMap: mutable.Map[String, Int],
                                   seqMaxLenMap: mutable.Map[String, Int], minCount: Int = -1, accuBufPercent: Int = 3): Tuple5[mutable.Map[String, Any], mutable.Map[String, Any], mutable.Map[String, Int], mutable.Map[String, Int], mutable.Map[String, Int]] = {
    if (features == null || features.length == 0) {
      return Tuple5(featureMap, featureMapExtra, featureMaxIndexMap, realFeatureMaxIndexMap, seqMaxLenMap)
    }

    val accumulateEncode = new AccumulateEncode(null, null, null, seqMaxLenMap, null)
    accumulateEncode.setMinCount(minCount)
    accumulateEncode.setMinCnt4Features(minCnt4Features)
    accumulateEncode.setAccumulateBufPercent(accuBufPercent)
    accumulateEncode.fit(spark, input, features: _*)
    val result = accumulateEncode.getFeatureMapFromAccumulate()
    Tuple5(featureMap.++(result._1), featureMapExtra.++(result._2), featureMaxIndexMap.++(result._3), realFeatureMaxIndexMap.++(result._4), seqMaxLenMap.++(result._5))
  }

  /**
   * fit accumulate_encoded features [增量任务]
   *
   * @param spark                  spark session
   * @param input                  input dataframe
   * @param features               features fed into accumulate encode
   * @param featureMap             featureMap for full transform task
   * @param featureMaxIndexMap     maxIdx after perform accumulateBufPercent
   * @param realFeatureMaxIndexMap real maxIdx
   * @param seqMaxLenMap           seqMaxLenMap
   * @param minCount               minCount
   * @return
   */
  def fitAccumulatedFeaturesByIncrement(spark: SparkSession,
                                        input: DataFrame,
                                        features: Array[String],
                                        featureMap: mutable.Map[String, Any],
                                        featureMaxIndexMap: mutable.Map[String, Int],
                                        realFeatureMaxIndexMap: mutable.Map[String, Int],
                                        featureIdxOverflow: mutable.Set[String],
                                        seqMaxLenMap: mutable.Map[String, Int], minCount: Int = -1): Tuple5[mutable.Map[String, Any], mutable.Map[String, Int], mutable.Map[String, Int], mutable.Map[String, Int], mutable.Set[String]] = {
    if (features == null || features.length == 0) {
      return Tuple5(featureMap, featureMaxIndexMap, realFeatureMaxIndexMap, seqMaxLenMap, mutable.Set.empty[String])
    }

    val accumulateEncode = new AccumulateEncode(featureMap, featureMaxIndexMap, realFeatureMaxIndexMap, seqMaxLenMap, featureIdxOverflow)
    accumulateEncode.setMinCount(minCount)
    accumulateEncode.setMinCnt4Features(minCnt4Features)
    accumulateEncode.fitAccumulatedFeatures(spark, input, features: _*)
    accumulateEncode.getFeatureMapFromAccumulateWithoutExtra()
  }


  /**
   * fit weighted features
   *
   * @param spark              SparkSession
   * @param input              train dataframe
   * @param features           wighted features
   * @param featureMap         featureMap
   * @param featureMaxIndexMap featureMaxIndexMap
   * @param seqMaxLenMap       seqMaxLenMap
   * @param minCount           minCount
   * @return
   */
  def fitWeightedSeparateFeature(spark: SparkSession,
                                 input: DataFrame,
                                 features: Array[String],
                                 featureMap: mutable.Map[String, Any],
                                 featureMapExtra: mutable.Map[String, Any],
                                 featureMaxIndexMap: mutable.Map[String, Int],
                                 seqMaxLenMap: mutable.Map[String, Int], minCount: Int = -1): Tuple4[mutable.Map[String, Any], mutable.Map[String, Any], mutable.Map[String, Int], mutable.Map[String, Int]] = {
    if (features == null || features.length == 0)
      return Tuple4(featureMap, featureMapExtra, featureMaxIndexMap, seqMaxLenMap)

    val weightedSeparateEncode = new WeightedSeparateEncode(null, null, seqMaxLenMap)
    weightedSeparateEncode.setMinCount(minCount)
    weightedSeparateEncode.fit(spark, input, features: _*)
    val tempTuple = weightedSeparateEncode.getFeatureMap
    Tuple4(featureMap.++(tempTuple._1), featureMapExtra.++(tempTuple._2), featureMaxIndexMap.++(tempTuple._3), seqMaxLenMap.++(tempTuple._4))
  }


  def fitDenseFeature(spark: SparkSession, input: DataFrame, denseFeature: Array[String],
                      normType: String = "standard",
                      seqMaxLenMap: mutable.Map[String, Int] = null): mutable.Map[String, mutable.Map[String, Array[Double]]] = {
    if (denseFeature == null || denseFeature.size == 0)
      return mutable.Map[String, mutable.Map[String, Array[Double]]]().empty
    val denseNormModel: DenseNormTransformInterface = normType match {
      case "minMax" => new MinMaxNorm(null)
      case "standard" => new StandardNorm(null)
      case "keep" => new KeepNorm(null)
      case _ => throw new IllegalArgumentException("norm type only support minMax、standard and keep norm")
    }
    denseNormModel.initSeqMaxLengthMap(seqMaxLenMap)
    denseNormModel.fit(input, denseFeature: _*)
    if (seqMaxLenMap != null) seqMaxLenMap ++= denseNormModel.getSeqMaxLengthMap
    denseNormModel.getFeatureMeanAndStd()
  }

  /**
   * 长序列 pureDense 特征 fit：使用 Array 输入的 fit 方法
   * 确定 Array 元素最大长度，设置 seq_max_length
   *
   * @param spark          SparkSession
   * @param input          输入 DataFrame
   * @param denseFeature   特征名数组
   * @param seqMaxLenMap   序列最大长度 map
   * @return 均值和标准差 map
   */
  def fitDenseLongSeqFeature(spark: SparkSession, input: DataFrame, denseFeature: Array[String],
                             seqMaxLenMap: mutable.Map[String, Int]): mutable.Map[String, mutable.Map[String, Array[Double]]] = {
    if (denseFeature == null || denseFeature.size == 0)
      return mutable.Map[String, mutable.Map[String, Array[Double]]]().empty
    val denseNormModel = new KeepNorm(null)
    denseNormModel.initSeqMaxLengthMap(seqMaxLenMap)
    denseNormModel.fitArray(input, denseFeature: _*)
    if (seqMaxLenMap != null) seqMaxLenMap ++= denseNormModel.getSeqMaxLengthMap
    denseNormModel.getFeatureMeanAndStd()
  }

  def fitSeperateFeature(spark: SparkSession, input: DataFrame, features: Array[String], featureMap: mutable.Map[String, Any],
                         featureMapExtra: mutable.Map[String, Any],
                         featureMaxIndexMap: mutable.Map[String, Int], seqMaxLenMap: mutable.Map[String, Int], minCnt4Features: mutable.Map[String, Int],
                         minCount: Int = (-1)): Tuple4[mutable.Map[String, Any], mutable.Map[String, Any], mutable.Map[String, Int], mutable.Map[String, Int]] = {
    if (features == null || features.length == 0) {
      return Tuple4(featureMap, featureMapExtra, featureMaxIndexMap, seqMaxLenMap)
    }
    val separateEncodeModel = new SeperateEncode(null, null, seqMaxLenMap)
    separateEncodeModel.setMinCount(minCount)
    separateEncodeModel.setMinCnt4Features(minCnt4Features)
    separateEncodeModel.fit(spark, input, features: _*)
    val Tuple4(tmpFeatureMap, tmpFeatureMapExtra, tmpFeautreMaxIndexMap, tmpSeqMaxLengthMap) = separateEncodeModel.getFeatureMap
    Tuple4(featureMap.++(tmpFeatureMap), featureMapExtra.++(tmpFeatureMapExtra), featureMaxIndexMap.++(tmpFeautreMaxIndexMap), seqMaxLenMap.++(tmpSeqMaxLengthMap))
  }

  def fitJointFeature(spark: SparkSession, input: DataFrame, features: Map[String, Array[String]], featureMap: mutable.Map[String, Any],
                      featureMapExtra: mutable.Map[String, Any],
                      featureMaxIndexMap: mutable.Map[String, Int], seqMaxLenMap: mutable.Map[String, Int],
                      minCount: Int = (-1)): Tuple4[mutable.Map[String, Any], mutable.Map[String, Any], mutable.Map[String, Int], mutable.Map[String, Int]] = {
    if (features == null || features.size == 0) {
      return Tuple4(featureMap, featureMapExtra, featureMaxIndexMap, seqMaxLenMap)
    }
    val jointEncodeModel = new JointEncode(null, null, seqMaxLenMap)
    jointEncodeModel.setMinCount(minCount)
    for (jointFeature <- features) {
      val prefixName = jointFeature._1
      jointEncodeModel.fit(spark, input, (Array(prefixName) ++ jointFeature._2.map(_.trim)): _*)
    }
    val Tuple4(tmpFeatureMap, tmpFeatureMapExtra, tmpFeatureMaxIndexMap, tmpSeqMaxLengthMap) = jointEncodeModel.getFeatureMap
    Tuple4(featureMap.++(tmpFeatureMap), featureMapExtra.++(tmpFeatureMapExtra), featureMaxIndexMap.++(tmpFeatureMaxIndexMap), seqMaxLenMap.++(tmpSeqMaxLengthMap))
  }

  def fitHashFeature(spark: SparkSession, input: DataFrame, features: Array[String], seqMaxLenMap: mutable.Map[String, Int]): mutable.Map[String, Int] = {
    if (features == null || features.length == 0)
      return mutable.Map[String, Int]().empty
    val hashEncodeModel = new HashEncode(null, null, seqMaxLenMap)
    hashEncodeModel.fit(spark, input, features: _*)
    hashEncodeModel.getFeatureMap._4
  }

  def fitRawFeature(spark: SparkSession, input: DataFrame, features: Array[String], seqMaxLenMap: mutable.Map[String, Int]): mutable.Map[String, Int] = {
    if (features == null || features.length == 0)
      return mutable.Map[String, Int]().empty
    val rawEncodeModel = new RawEncode(null, null, seqMaxLenMap)
    rawEncodeModel.fit(spark, input, features: _*)
    rawEncodeModel.getFeatureMap._4
  }

  def transformDenseFeature(input: DataFrame, denseFeature: Array[String], suffixName: String,
                            meanAndStdMap: mutable.Map[String, mutable.Map[String, Array[Double]]],
                            normType: String = "standard"): DataFrame = {
    if (denseFeature == null || denseFeature.length == 0 || input == null)
      return input
    val denseNormModel: DenseNormTransformInterface = normType match {
      case "minMax" => new MinMaxNorm(meanAndStdMap)
      case "standard" => new StandardNorm(meanAndStdMap)
      case "keep" => new KeepNorm(meanAndStdMap)
      case _ => throw new IllegalArgumentException("norm type only support minMax，standard and keep")
    }
    // transform the data
    val resDf = if (TransformParamModule.useColumnExprOpt) {
      denseNormModel.transformOpt(input, suffixName, denseFeature: _*)
    } else {
      denseNormModel.transform(input, suffixName, denseFeature: _*)
    }
    resDf
  }

  // modelTrainConfigSavePath
  def transformCategoricalFeature(modelTrainConfigSavePath: String, input: DataFrame, categoricalType: String, suffixName: String,
                                  featureMap: mutable.Map[String, Any],
                                  initMapArr: Array[mutable.Map[String, Int]], mode: String, broadcastChunkInfo: Broadcast[ChunkInfo], isPadding: Boolean): DataFrame = {
    if (!encodeMap.contains(categoricalType) || input == null)
      return input
    var res = input
    var cateEncodeModel: CategoricalEncodeInterface = null
    info(s"transformCategoricalFeature type = ${categoricalType}  size= ${encodeMap(categoricalType).size} start!!!")
    categoricalType match {
      case SEPARATE => {
        // 因为separate encode fit时,优先更新this.featureMap, 因此只要传入的wholeMap的大小小于upper limit,那么依旧可以采用老的方式传递featureMap
        cateEncodeModel = new SeperateEncode(featureMap, initMapArr(0), initMapArr(1))
        if (TransformParamModule.useColumnExprOpt) {
          val formattedSeparateInput: Array[(String, String)] = separateEncodeFeatures.map { feature =>
            val replacedKey = feature.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR)
            (feature, replacedKey)
          }.toArray
          res = cateEncodeModel.transformOpt(broadcastChunkInfo, res, suffixName, mode, isPadding, formattedSeparateInput)
        } else {
          for (feature <- separateEncodeFeatures) {
            res = cateEncodeModel.transform(broadcastChunkInfo, res, feature.replace(Const.CONFIG_CROSS_SEP_CHAR,
              Const.ACTUAL_SEP_CHAR), suffixName, mode, isPadding, feature)
          }
        }
      }
      case JOINT => {
        cateEncodeModel = new JointEncode(featureMap, initMapArr(0), initMapArr(1))
        if (TransformParamModule.useColumnExprOpt) {

          val formattedJointInput: Array[(String, String)] = jointEncodeFeatures.flatMap { feature =>
            val featureMapKey = feature._1 // 比如 "A_B"
            val colsArray = feature._2     // 比如 Array("A", "B")

            // 平铺成: Array(("A", "A_B"), ("B", "A_B"))
            colsArray.map(colName => (colName, featureMapKey))
          }.toArray

          // 一次性扔进修正后的真实批量函数中
          res = cateEncodeModel.transformOpt(broadcastChunkInfo, res, suffixName, mode, isPadding, formattedJointInput)
        } else {
          for (feature <- jointEncodeFeatures) {
            res = cateEncodeModel.transform(broadcastChunkInfo, res, feature._1, suffixName, mode, isPadding, feature._2: _*)
          }
        }
      }
      case WEIGHTED_SEQ => {
        cateEncodeModel = new WeightedSeparateEncode(featureMap, initMapArr(0), initMapArr(1))
        if (TransformParamModule.useColumnExprOpt) {
          // 批量 Opt：每个 feature 自带专属 featureName，一次性 select 投影
          val weightedSeqInput: Array[(String, String)] = weightedSeqFeatures.map(feature => (feature, feature))
          res = cateEncodeModel.transformWeightedOpt(broadcastChunkInfo, res, mode, isPadding, weightedSeqInput)
        } else {
          for (feature <- weightedSeqFeatures) {
            res = cateEncodeModel.transformWeighted(broadcastChunkInfo, res, feature, mode, isPadding, feature)
          }
        }
      }
      case ACU => {
        cateEncodeModel = new AccumulateEncode(featureMap, initMapArr(0), null, initMapArr(1), null)
        for (feature <- accumulateFeatures) {
          res = cateEncodeModel.transform(broadcastChunkInfo, res, feature.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR), suffixName, mode, isPadding, feature)
        }
      }
      case _ => throw new IllegalArgumentException("only support separateEncode,jointEncode,weightedSeparateEncode and accumulateEncode as categoricalType")
    }
    info(s"transformCategoricalFeature type = ${categoricalType} start!!!")
    res
  }