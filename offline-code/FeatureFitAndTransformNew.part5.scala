
第 5/5 批
  def saveModelTrainConfig(spark: SparkSession, modelTrainConfigSavePath: String, dataLocalSavePath: String,
                           featureMaxIndexMap: mutable.Map[String, Int], seqMaxLenMap: mutable.Map[String, Int],
                           trainCount: Long, validCount: Long = 0, testCount: Long = 0, onlyTransform: Boolean = false, statInfoMap: mutable.Map[String, Any] = mutable.Map.empty[String, Any]
                           , sampleZipNum: Int, groupParams: Map[String, String]): Unit = {
    info(s"save model train map at ${modelTrainConfigSavePath}")
    var modelTrainMap = mutable.Map[String, Any]().empty
    var dataCountMap = mutable.Map[String, Long]().empty
    var dataPathMap = mutable.Map[String, String]().empty

    //modelTrain.json若已存在，则加载modelTrain.json中的dataCount和dataPath信息
    if (onlyTransform && new File(modelTrainConfigSavePath).exists()) {
      val oldMap = JSON.parseFull((Utils.loadJsonToString(spark, modelTrainConfigSavePath))).get
        .asInstanceOf[immutable.Map[String, Any]]
      dataCountMap = mutable.Map[String, Long](oldMap("dataCount").asInstanceOf[immutable.Map[String, Long]].toSeq: _*)
      dataPathMap = mutable.Map[String, String](oldMap("dataPath").asInstanceOf[immutable.Map[String, String]].toSeq: _*)
    }
    modelTrainMap += "maxIndex" -> featureMaxIndexMap.map(x => (Utils.encodeFeatureName(x._1), x._2))
    modelTrainMap += "seqMaxLength" -> seqMaxLenMap.map(x => (Utils.encodeFeatureName(x._1), x._2))

    def addDataCount(dataType: String, dataCount: Long): Unit = {
      if ((dataCount - 0.0) > 1e-6) {
        val dataPath = dataLocalSavePath + "/" + dataType
        dataPathMap += dataType -> dataPath
        dataCountMap += dataType -> dataCount
      } else {
        info(s"dataType:${dataType}, dataCount:${dataCount} will not be saved")
      }
    }

    Array(Tuple2("train", trainCount), Tuple2("valid", validCount), Tuple2("test", testCount)).map(x => addDataCount(x._1, x._2))

    modelTrainMap += "dataPath" -> dataPathMap
    modelTrainMap += "dataCount" -> dataCountMap

    //debug模式下会将stat指标输出值modelTrain.json
    statInfoMap.foreach(pair => modelTrainMap += pair._1 -> pair._2)

    //在开启样本压缩情况下,将sampleZipNum输出至modelTrain.json
    if (sampleZipNum > 1) {
      modelTrainMap += "sampleZipNum" -> sampleZipNum
    }

    //在分组操作时，将分组参数输出至modelTrain.json
    groupParams.get("groupKey") match {
      case Some(value: String) if value.nonEmpty =>
        modelTrainMap += "groupKey" -> groupParams.getOrElse("groupKey", "")
        modelTrainMap += "groupQueueLen" -> groupParams.getOrElse("groupQueueLen", 1)
        modelTrainMap += "groupRankIndicator" -> groupParams.getOrElse("groupRankIndicator", "")
        modelTrainMap += "groupPhase" -> groupParams.getOrElse("groupPhase", "")
        modelTrainMap += "groupRankTopK" -> groupParams.getOrElse("groupRankTopK", "")
      case _ =>
        println("not specified groupKey.")
    }

    //启动多特征域拼接，需要记录离散特征与连续特征拼接的顺序
    if (isFeatureConcat) {
      modelTrainMap += "sparse_feature_order" -> sparseFeatureCols.sortWith(_ < _).zipWithIndex.toMap
      modelTrainMap += "dense_feature_order" -> denseFeatureCols.sortWith(_ < _).zipWithIndex.toMap
    }

    Utils.saveMutableMapV2(spark, modelTrainMap, modelTrainConfigSavePath)
    if (storageMode != null && storageMode.equalsIgnoreCase("nsp")) {
      info("current storageMode is nsp, start to writes data success file, and preload data")
      val envConfig = NspUtils.init()
      val nspService = new NspService(envConfig.cloudMapConfig, envConfig.nspConfig, datasetInfo.getRegion)
      val basePath = handleNspSavePath(modelTrainConfigSavePath, isNeedSuffix = false)
      saveMutableMapByNsp(nspService, basePath, modelTrainMap, datasetInfo.isCrossRegion)
      nspService.close()
      val DataMangerService = new DataMangerService()
      val preloadPaths = new util.ArrayList[String]()
      preloadPaths.add(basePath)
      info(s"preload path  ${basePath}")
      DataMangerService.preloadDataSet(datasetInfo, preloadPaths)
    }

    info(s"*************ending*************")
  }

  // 计算时间样本权重
  def computeSampleWeight(input: DataFrame, inputCol: String, method: String): DataFrame = {
    val dateInfo = input.withColumn(inputCol, substring(col(inputCol), 1, 8)).select(max(inputCol), min(inputCol))
    var inputs = input.withColumn("max_date", to_date(lit(dateInfo.first().get(0)), "yyyyMMdd"))
      .withColumn("min_date", to_date(lit(dateInfo.first().get(1)), "yyyyMMdd"))
      .withColumn("max_date_gap", datediff(col("max_date"), col("min_date")))
      .withColumn("date_gap", datediff(col("max_date"), to_date(col(inputCol), "yyyyMMdd")))

    // 判断是使用指数或者幂函数或者线性函数计算样本权重
    if (method == "exp") {
      inputs = inputs.withColumn("prefix_sample_weight", exp(-col("date_gap")))
    } else if (method == "pow") {
      inputs = inputs.withColumn("prefix_sample_weight", lit(1) / (lit(1) + col("date_gap")))
    } else {
      inputs = inputs.withColumn("prefix_rank_number", col("max_date_gap") - col("date_gap") + lit(1))

      val total_rank = inputs.select("prefix_rank_number").distinct().agg(sum("prefix_rank_number")).first().get(0)
      info(s"=========total rank is :  ${total_rank} ===========")

      inputs = inputs.withColumn("prefix_total_rank", lit(total_rank))
        .withColumn("prefix_sample_weight", col("prefix_rank_number") / col("prefix_total_rank"))
    }

    inputs = inputs.drop(col(inputCol)).withColumnRenamed("prefix_sample_weight", inputCol)
    inputs
  }

  def info(msg: String): Unit = {
    LogUtil.infoLog(LOGGER, msg)
  }

  def removeGroupId(map: mutable.Map[String, Any]): mutable.Map[String, Any] = {
    map.map { case (key, value) =>
      key -> {
        value match {
          case nestedMap: mutable.Map[String, Any] =>
            // 递归处理嵌套的 Map
            removeGroupId(nestedMap)
          case _ =>
            // 如果不是 Map，直接返回
            value
        }
      }
    }.filterNot { case (key, value) =>
      key == Const.GROUP_ID
    }
  }

  private def startConsistencyCheck(spark: SparkSession, suffixName: String, sampledTrainData: DataFrame): Unit = {
    if (sampledTrainData != null) {
      info("Building consistency check JSON...")

      // 所有参与编码的特征名（原始名）
      val allTransformedFeatures = (
        denseFeatures ++
          pureDenseFeatures ++
          separateEncodeFeatures ++
          rawEncodeFeatures ++
          jointEncodeFeatures.flatMap(_._2) ++
          weightedSeqFeatures ++
          accumulateFeatures ++
          autoHashWeightedFeatures ++
          hashEncodeMap.keys
        ).distinct.map(_.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR))

      // 合并所有加权特征：weightedSeq + autoHashWeighted
      weightedSeqFeatures.map(_.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR)).toSet
      autoHashWeightedFeatures.map(_.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR)).toSet

      // 在构建 ConsistCheckUtils 实例前，用 seqMaxLenMap 中的值更新 featureDefaultValue 中的 seq_max_length，此外要处理交叉特征名#替换成__
      val updatedFeatureDefaultValue = featureDefaultValue.map { case (name, feature) =>
        val realName = name.replace("#", "__")
        val updatedSeqMaxLength = seqMaxLenMap.getOrElse(realName, feature.seq_max_length)
        realName -> feature.copy(seq_max_length = updatedSeqMaxLength).copy(name = realName)
      }
      // 构建 ConsistCheckUtils 实例
      val checker = ConsistCheckUtils(
        labelCols = labelCol,
        featureConfigs = updatedFeatureDefaultValue,
        featureNames = allTransformedFeatures,
        weightedFeatures = weightedSeqFeatures,
        autoHashWeightedFeatures = autoHashWeightedFeatures,
        df = sampledTrainData
      )
      val jsonContent = checker.buildConsistencyJson()
      LOGGER.info("begin to print json Content {}", jsonContent)
      val filePath = dataHdfsSavePath + "/consistencyCheck/consistencyCheck.json"
      info(s"Consistency check JSON saved to: $filePath")
      Utils.saveTextFile(spark, jsonContent, filePath)
    }
  }

  def saveMutableMapByNsp(nspService: NspService, save_path: String, mapFile: Any, isCrossRegion: Boolean):Unit = {
    try {
      val map = mapFile.asInstanceOf[mutable.Map[String, Any]]

      // 使用 ByteArrayOutputStream 模拟输出流，与 saveMutableMapV2 保持一致
      implicit val formats: org.json4s.DefaultFormats = org.json4s.DefaultFormats
      val baos = new java.io.ByteArrayOutputStream()
      Serialization.write(map, baos)
      val bytes = baos.toByteArray

      nspService.putObjectWithResponse(save_path, bytes, isCrossRegion)
    } catch {
      case e: Exception =>
        info(s"saveMutableMapByNsp error: ${e.getMessage}")
        throw e
    }
  }


  def processVarLenDenseFeature(input: DataFrame, features: Array[String]): DataFrame = {
    try {
      if (features == null || features.isEmpty || input == null) {
        return input
      }

      var data = input
      val splitMap = TransformConfigModule.getSplitCharMap()
      val joinSepMap = TransformConfigModule.getJoinSepMap()

      for (featureName <- features) {
        if (seqMaxLenMap.contains(featureName)) {
          val maxLength = seqMaxLenMap(featureName)
          val separator = splitMap.getOrElse(featureName, FEATURE_SEP_CHAR)
          val joinSep = joinSepMap.getOrElse(featureName, LIST_SEP)
          // UDF 内部已包含 try-catch，防止运行时异常
          val padTruncateUDF = udf((value: String) => {
            try {
              if (value == null || value.isEmpty) {
                val dft = TransformConfigModule.featureDftMap.getOrElse(featureName, "0")
                val result = Array.fill(maxLength)(dft).mkString(joinSep)
                result
              }
              val values = value.split(separator)
              if (values.exists(_.isEmpty)) {
                info(s"processVarLenDenseFeature UDF: WARNING - feature '$featureName' value '$value' contains empty elements after split")
              }
              if (values.length == maxLength) {
                value
              } else {
                val result = if (values.length > maxLength) {
                  values.take(maxLength)
                } else {
                  val dft = TransformConfigModule.featureDftMap.getOrElse(featureName, "0")
                  values ++ Array.fill(maxLength - values.length)(dft)
                }
                result.mkString(joinSep)
              }
            } catch {
              case e: Exception =>
                info(s"processVarLenDenseFeature UDF: Exception in UDF for $featureName, value: '$value'. Error: ${e.getMessage}")
                value
            }
          })
          data = data.withColumn(featureName, padTruncateUDF(col(featureName)))
        }
      }
      info(s"processVarLenDenseFeature: Completed processing ${features.length} var-len dense features")
      data

    } catch {
      case e: Exception =>
        info(s"processVarLenDenseFeature: Unexpected top-level exception occurred. Error: ${e.getMessage}")
        if (input != null) {
          input
        } else {
          info(s"processVarLenDenseFeature: Cannot return original data, input was null.")
          throw new IllegalStateException("Input DataFrame is null and cannot process.", e)
        }
    }
  }

  /**
   * 长序 pureDense 特征截断/填充：对 array 输入做长度对齐，输出还是 array
   * 与 processVarLenDenseFeature 对称，区别是不做 split/join，直接操作 array
   *
   * @param input   输入 DataFrame
   * @param features 长序 pureDense 特征名
   * @return DataFrame
   */
  def processVarLenDenseLongSeqFeature(input: DataFrame, features: Array[String]): DataFrame = {
    if (features == null || features.isEmpty || input == null) return input
    var data = input
    for (featureName <- features) {
      val maxLength = seqMaxLenMap.getOrElse(featureName, 0)
      if (maxLength > 0) {
        val padTruncateUDF = udf((value: Any) => {
          value match {
            case wa: mutable.WrappedArray[_] if wa.nonEmpty =>
              val n = wa.length
              val outLen = math.min(n, maxLength)
              val result = new Array[Double](maxLength)
              var i = 0
              while (i < outLen) {
                val elem = wa(i)
                val s = if (elem == null) "" else elem.toString.trim
                result(i) = if (s.isEmpty) LIST_EMPTY_DEFAULT_VALUE
                else {
                  try { s.toDouble }
                  catch { case _: NumberFormatException => LIST_EMPTY_DEFAULT_VALUE }
                }
                i += 1
              }
              while (i < maxLength) { result(i) = LIST_EMPTY_DEFAULT_VALUE; i += 1 }
              result.toSeq
            case _ =>
              Array.fill(maxLength)(LIST_EMPTY_DEFAULT_VALUE).toSeq
          }
        })
        data = data.withColumn(featureName, padTruncateUDF(col(featureName)))
      }
    }
    data
  }



  def processVarLenDenseFeatureOpt(input: DataFrame, features: Array[String]): DataFrame = {
    try {
      // 基础防御性校验
      if (features == null || features.isEmpty || input == null) {
        return input
      }

      // 1. 在 Driver 端提前提取好 Local 配置，规避 Executor 端的分布式序列化风险
      val splitMapLocal = TransformConfigModule.getSplitCharMap().toMap
      val joinSepMapLocal = TransformConfigModule.getJoinSepMap().toMap
      val featureDftMapLocal = TransformConfigModule.featureDftMap.toMap
      val seqMaxLenMapLocal = seqMaxLenMap.toMap

      val defaultSep = FEATURE_SEP_CHAR
      val defaultJoin = LIST_SEP

      // 2. 核心优化：利用扁平化的一维映射结构，代替原先多次 withColumn 的性能黑洞
      val selectExprs = input.schema.fieldNames.map { fieldName =>
        if (features.contains(fieldName) && seqMaxLenMapLocal.contains(fieldName)) {

          // 提取当前特征的局部配置常量
          val maxLength = seqMaxLenMapLocal(fieldName)
          val separator = splitMapLocal.getOrElse(fieldName, defaultSep)
          val joinSep = joinSepMapLocal.getOrElse(fieldName, defaultJoin)
          val dftString = featureDftMapLocal.getOrElse(fieldName, "0")

          // 3. 直接为当前列构造独立的 UDF 算子对象（修复了匿名函数中不能使用 return 的编译报错）
          val padTruncateUDF = udf((value: String) => {
            try {
              if (value == null || value.isEmpty) {
                // 完美替代原 return 逻辑：最后一行表达式的值即为匿名函数的返回值
                Array.fill(maxLength)(dftString).mkString(joinSep)
              } else {
                // 原汁原味保留您原本的纯 Scala 数组切分、防御、截断与补齐逻辑
                val values = value.split(separator)
                if (values.exists(_.isEmpty)) {
                  info(s"processVarLenDenseFeature UDF: WARNING - feature '$fieldName' value '$value' contains empty elements after split")
                }

                if (values.length == maxLength) {
                  value
                } else {
                  val result = if (values.length > maxLength) {
                    values.take(maxLength)
                  } else {
                    values ++ Array.fill(maxLength - values.length)(dftString)
                  }
                  result.mkString(joinSep)
                }
              }
            } catch {
              case e: Exception =>
                info(s"processVarLenDenseFeature UDF: Exception in UDF for $fieldName, value: '$value'. Error: ${e.getMessage}")
                value
            }
          })

          // 调用 UDF 转换该列，并保持原列名
          padTruncateUDF(col(fieldName)).as(fieldName)

        } else {
          // 4. 非目标变长特征列，直接原样保留指针，不发生任何实际转换与开销
          col(fieldName)
        }
      }

      // 5. 将原先的多层线性嵌套执行计划，无损压缩为单层矩阵投影平铺展开
      val result = input.select(selectExprs: _*)
      info(s"processVarLenDenseFeature: Completed processing ${features.length} var-len dense features via single select projection")
      result

    } catch {
      case e: Exception =>
        info(s"processVarLenDenseFeature: Unexpected top-level exception occurred. Error: ${e.getMessage}")
        if (input != null) {
          input
        } else {
          info(s"processVarLenDenseFeature: Cannot return original data, input was null.")
          throw new IllegalStateException("Input DataFrame is null and cannot process.", e)
        }
    }
  }
}
