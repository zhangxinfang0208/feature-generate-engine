
第 4/5 批
  def transformHashFeature(input: DataFrame, suffixName: String, hashNumMap: mutable.Map[String, Int], seqMaxLength: mutable.Map[String, Int],
                           minCnt4Features: mutable.Map[String, Int], isPaddingFlag: Boolean, minCnt: Int = 100, isCrossDoubleHash: Boolean = false, hashType:String): DataFrame = {
    if (hashNumMap.isEmpty || input == null)
      return input
    val hashEncode = new HashEncode(null, null, seqMaxLength)
    hashEncode.setMinCount(minCnt)
    hashEncode.setMinCnt4Features(minCnt4Features)
    if (TransformParamModule.useColumnExprOpt) {
        hashEncode.transform_new_final(input, suffixName, hashNumMap, isCrossDoubleHash, isPaddingFlag, hashType)
    } else {
      hashEncode.transform(input, suffixName, hashNumMap, isCrossDoubleHash, isPaddingFlag, hashType)
    }
  }

  /**
   * 长序列 autoHash 特征 transform：使用 Array 输入的 hash UDF
   * 与普通 transformHashFeature 独立，不交叉特征，不 split
   *
   * @param input        输入 DataFrame
   * @param suffixName   后缀名
   * @param hashNumMap   feature_name -> hash_bucket
   * @param seqMaxLength 特征最大长度
   * @param isPadding    是否 padding
   * @return DataFrame
   */
  def transformHashLongSeqFeature(input: DataFrame, suffixName: String, hashNumMap: mutable.Map[String, Int],
                                  seqMaxLength: mutable.Map[String, Int], isPadding: Boolean): DataFrame = {
    if (hashNumMap.isEmpty || input == null)
      return input
    val hashEncode = new HashEncode(null, null, seqMaxLength)
    hashEncode.transformLongSeqArray(input, suffixName, hashNumMap, isPadding)
  }

  /**
   * 长序列 pureDense 特征 transform：使用 Array 输入的 norm UDF
   *
   * @param input         输入 DataFrame
   * @param denseFeature  特征名数组
   * @param suffixName    后缀名
   * @param meanAndStdMap 均值和标准差
   * @return DataFrame
   */
  def transformDenseLongSeqFeature(input: DataFrame, denseFeature: Array[String], suffixName: String,
                                   meanAndStdMap: mutable.Map[String, mutable.Map[String, Array[Double]]]): DataFrame = {
    if (denseFeature == null || denseFeature.length == 0 || input == null)
      return input
    val denseNormModel = new KeepNorm(meanAndStdMap)
    denseNormModel.transformArray(input, suffixName, denseFeature: _*)
  }

  def transformRawFeature(input: DataFrame, suffixName: String, seqMaxLength: mutable.Map[String, Int], isRawNumTransform: Boolean, isPaddingFlag: Boolean,
                          rawEncodeFeatures: Array[String]): DataFrame = {
    if (rawEncodeFeatures.isEmpty || input == null)
      return input
    val rawEncode = new RawEncode(null, null, seqMaxLength)
    if (TransformParamModule.useColumnExprOpt) {
      rawEncode.transformOpt(input, suffixName, isRawNumTransform, isPaddingFlag, rawEncodeFeatures)
    } else {
      rawEncode.transform(input, suffixName, isRawNumTransform, isPaddingFlag, rawEncodeFeatures)
    }
  }

  //删除目录和文件
  def dirDel(path: File) {
    if (!path.exists())
      return
    else if (path.isFile()) {
      if (!path.delete()) {
        throw new Exception(s"delete ${path} failed")
      }
      return
    }
    val file: Array[File] = path.listFiles()
    for (d <- file) {
      dirDel(d)
    }
    if (path.delete()) {
      info(path + ":  目录被删除")
    } else {
      throw new Exception(s"delete ${path} failed")
    }
  }

  def saveHdfsFile(spark: SparkSession, input: DataFrame, labelCol: Array[String], fileFormat: String, dataSavePath: String, outputPartition: Int = -1, resultFileSizeInMB: Int = -1, dataType: String = "train", compress: Boolean = true, sampleZipNum: Int, groupParams: Map[String, String], materialFeatures: Map[String, String], materialSwitch: Boolean, multipartUploadThreshold: Int = 20 * 1024 * 1024): Unit = {
    if (input == null)
      return
    info(s"save ${dataType} data as ${fileFormat} at " + s"${dataSavePath + s"/${dataType}"}")

    // 分区数计算：outputPartition 直接用，其余需要 count + 采样估算
    val numPartitions = if (dataType == "train" && outputPartition > 0) {
      // 用户明确配置了输出分区数，直接用，不估算
      outputPartition
    } else {
      val cnt = input.count()
      info(s"data count is ${cnt}")

      if (cnt > 10000) {
        // 定义不同输出格式和压缩方式的修正系数，模拟写入后实际数据大小的变化
        val formatFactors: Map[String, Double] = Map(
          "csv" -> (if (compress) 0.07 else 0.7),
          "parquet" -> (if (compress) 0.06 else 0.06),
          "tfrecord" -> (if (compress) 0.03 else 0.90)
        )
        val factor = formatFactors.getOrElse(fileFormat.toLowerCase, 1.0)

        val sampleFraction = 0.001
        val sampledData = input.sample(withReplacement = false, sampleFraction)
        val sampleSizeInBytes = sampledData.rdd.map(_.toString.getBytes("UTF-8").length.toLong).reduce(_ + _)

        val estimatedTotalSizeInBytes = sampleSizeInBytes * (1 / sampleFraction) * factor
        val estimatedTotalSizeInMB = estimatedTotalSizeInBytes / (1024 * 1024)

        LOGGER.info(s"estimated $dataType dataset size is ${estimatedTotalSizeInMB / 1024}GB")

        // 计算分区数
        if (resultFileSizeInMB > 0) {
          Math.ceil(estimatedTotalSizeInMB / resultFileSizeInMB).toInt
        } else {
          // 获取当前分区数
          val currentPartitions = input.rdd.getNumPartitions
          val currentPartitionSize = estimatedTotalSizeInMB / currentPartitions

          val minResultFileSizeInMB = 128
          val maxResultFileSizeInMB = 512

          if (currentPartitionSize > maxResultFileSizeInMB) {
            Math.ceil(estimatedTotalSizeInMB / maxResultFileSizeInMB).toInt
          } else if (currentPartitionSize < minResultFileSizeInMB) {
            Math.max(1, Math.floor(estimatedTotalSizeInMB / minResultFileSizeInMB).toInt)
          } else {
            currentPartitions
          }
        }
      } else {
        1
      }
    }

    LOGGER.info(s"current numPartitions is  $numPartitions")
    var saveDF = input
    // 处理label特征，保存为对应导出文件格式依赖形式
    if (!(labelCol.length == 1 && labelCol(0).isEmpty)) {
    saveDF = if (TransformParamModule.useColumnExprOpt) {
        DataPersistenceInterface.convertLabelOpt(input, labelCol, fileFormat)
      } else {
        DataPersistenceInterface.convertLabel(input, labelCol, fileFormat)
      }
    }

    // 处理分组
    val groupKeyOpt = groupParams.getOrElse("groupKey", "")
    val groupPhase = groupParams.getOrElse("groupPhase", "")

    if (groupKeyOpt.nonEmpty && (groupPhase.isEmpty | groupPhase.split(COMMA_SEP).contains(dataType))) {
      // 如果 groupKey 不是空字符串
      saveDF = if (TransformParamModule.useColumnExprOpt) {
        DataPersistenceInterface.groupSamplesOpt(spark, saveDF, groupParams)
      } else {
        DataPersistenceInterface.groupSamples(spark, saveDF, groupParams)
      }
      LogUtil.infoLog(LOGGER, "group data finished.")
    }

    if (materialSwitch) {
      LogUtil.infoLog(LOGGER, "group split data started.")
      saveDF = if (TransformParamModule.useColumnExprOpt) {
        DataPersistenceInterface.groupSplitsSamplesOpt(saveDF, materialFeatures)
      } else {
        DataPersistenceInterface.groupSplitsSamples(saveDF, materialFeatures)
      }
      LogUtil.infoLog(LOGGER, "group split data finished.")
    }

    // 处理数据压缩
    if (sampleZipNum > 1) {
      saveDF = if (TransformParamModule.useColumnExprOpt) {
        DataPersistenceInterface.combineSamplesOpt(spark, saveDF, sampleZipNum)
      } else {
        DataPersistenceInterface.combineSamples(spark, saveDF, sampleZipNum)
      }
      LogUtil.infoLog(LOGGER, "combine data finished.")
    }

    // 判断透传字段中是否存在pt_h,以此来决定是否按照天级时序目录存放tf
    var isOrderByDay: Boolean = false
    if (requiredPassFeatures.contains(PT_H)) {
      info("******************RequiredPassFeatures contains pt_h, the final output path would order by day***************************")
      isOrderByDay = true
    }

    if (storageMode != null && storageMode.equalsIgnoreCase("nsp")) {
      info(s"save data by nsp")
      val basePath: String = handleNspSavePath(dataSavePath) + s"${dataType}"

      val envConfig = NspUtils.init_for_executor()
      val cloudMapConfig = envConfig.cloudMapConfig
      val nspConfig = envConfig.nspConfig
      fileFormat match {
        case "tfrecord" =>
          SaveAsTFRecordFormat.saveAsNspFile(spark, saveDF, basePath,
            numPartitions, compress=compress, isOrderByDay = isOrderByDay, cloudMapConfig, nspConfig, datasetInfo, multipartUploadThreshold = multipartUploadThreshold, isWriteSuccessFile = TransformParamModule.isWriteSuccessFile)
        case "csv" =>
          SaveAsCsvFormat.saveAsNspFile(spark, saveDF, basePath,
            numPartitions, compress=compress, isOrderByDay = isOrderByDay, cloudMapConfig, nspConfig, datasetInfo, multipartUploadThreshold = multipartUploadThreshold, isWriteSuccessFile = TransformParamModule.isWriteSuccessFile)
        case _ => throw new IllegalArgumentException("only support csv,parquet or tfrecord type")
      }
    } else {
      info(s"save data by hdfs")
      // 保存训练集数据
      fileFormat match {
        case "csv" => {
          // csv 数据保存前需要将 Array 转为 String
          saveDF = if (TransformParamModule.useColumnExprOpt) {
            DataPersistenceInterface.convertArray2StringOpt(saveDF)
          } else {
            DataPersistenceInterface.convertArray2String(saveDF)
          }
          SaveAsCsvFormat.saveAsHdfsFile(spark, saveDF, dataSavePath + s"/${dataType}", numPartitions, compress=compress)
        }
        case "tfrecord" => {
          SaveAsTFRecordFormat.saveGZHdfsFile(spark, saveDF, dataSavePath + s"/${dataType}", numPartitions, compress=compress, isOrderByDay = isOrderByDay)
        }
        case "parquet" => {
          SaveAsParquetFormat.saveAsHdfsFile(spark, saveDF, dataSavePath + s"/${dataType}", numPartitions, compress=compress)
        }
        case _ => throw new IllegalArgumentException("only support csv,parquet or tfrecord type")
      }

    }
    info(s"*************ending*************")
  }

  def handleNspSavePath(dataSavePath: String, isNeedSuffix: Boolean = true) = {
    info(s"origin savePath is ${dataSavePath}")
    var savePath = dataSavePath.stripPrefix(obsRoot)
    if (savePath.startsWith("/")) savePath = savePath.stripPrefix("/")
    if (isNeedSuffix) {
      savePath = if (savePath.endsWith("/")) savePath else savePath + "/"
    } else {
      savePath = if (savePath.endsWith("/")) savePath.stripSuffix("/") else savePath
    }
    val datasyncRegex = f"${datasetInfo.getBusinessId}/dataset/${datasetInfo.getDataSetId}/base/"
    var basePath = savePath
    if (!savePath.startsWith(datasyncRegex)) {
      basePath = f"${datasetInfo.getBusinessId}/dataset/${datasetInfo.getDataSetId}/base/${savePath}"
    }
    basePath
  }

  def saveTransformMap(spark: SparkSession, param: TaskParameter, featureMap: mutable.Map[String, Any],
                       featureMaxIndexMap: mutable.Map[String, Int], seqMaxLenMap: mutable.Map[String, Int],
                       meanAndStdMap: mutable.Map[String, mutable.Map[String, Array[Double]]],
                       hashEncodeMap: mutable.Map[String, Int], jointFeatureMap: Map[String, Array[String]],
                       longSeqHashEncodeMap: mutable.Map[String, Int] = mutable.Map.empty): Unit = {
    info("*************save transform map*************")
    val transformMap: mutable.Map[String, Any] = mutable.Map[String, Any]().empty
    transformMap += "version" -> date
    val onlineFeatureMap = mutable.Map[String, Any]().empty

    // 增加mean&&std
    val dense = mutable.Map[String, mutable.Map[String, Array[Double]]]().empty
    val meanName = "mean"
    val stdName = "std"
    if (!denseFeatures.isEmpty || !pureDenseFeatures.isEmpty) {
      val res = denseFeatures ++ pureDenseFeatures
      info(s"meanAndStdMap size = ${meanAndStdMap.size} keys = ${meanAndStdMap.keySet.mkString(",")}")
      info(s"features size = ${res.length} keys = ${res.mkString(",")}")
      if (meanAndStdMap.keySet.size != res.length) {
        throw new IllegalArgumentException("the size of feature's mean is not equal to the length of input dense feature,please check")
      }
      for (featName <- res.map(_.trim)) {
        // 将当前特征对应的mean和std加入
        dense.put(Utils.encodeFeatureName(featName), mutable.Map(stdName -> meanAndStdMap(featName)(stdName)))
        dense(Utils.encodeFeatureName(featName)) += meanName -> meanAndStdMap(featName)(meanName)
        // 如果是多维连续特征，则加入seqMaxLenMap
        if (meanAndStdMap(featName)(meanName).length >= 1)
          seqMaxLenMap += Utils.encodeFeatureName(featName) -> meanAndStdMap(featName)(meanName).length
      }
      info(s"*************ending*************")
    }

    if (dense.nonEmpty && isSaveOnlineFeatureMap) {
      info(s"dense is $dense")
      onlineFeatureMap += "dense" -> dense
    }

    transformMap += "dense" -> dense

    val sparseFeatureMap = mutable.Map[String, mutable.Map[String, Any]]().empty
    var featurePrefix: String = null
    var featureSuffix: String = null

    if (featureMap != null) {
      for (feat <- featureMap.keys) {
        if (feat.split(",").length < 2) {
          throw new Exception(s"feature map format error,current feature is ${feat}, the possible reason is that there is empty string in the feat ${feat.split(",")(0)}, please check the input table")
        }
        featurePrefix = Utils.encodeFeatureName(feat.split(",")(0))
        featureSuffix = feat.split(",").drop(1).mkString(",")
        if (sparseFeatureMap.contains(featurePrefix)) {
          sparseFeatureMap(featurePrefix) += featureSuffix -> featureMap(feat)
        } else {
          sparseFeatureMap += featurePrefix -> mutable.Map(featureSuffix -> featureMap(feat))
        }
      }
      if (sparseFeatureMap.nonEmpty && isSaveOnlineFeatureMap) {
        onlineFeatureMap += "sparse" -> sparseFeatureMap
      }
    }

    // 保存hash32类型的编码数据
    val hash32FeatureMap = mutable.Map[String, mutable.Map[String, Any]]()
    if (!hashEncodeMap.isEmpty) {
      //val hashMap = mutable.Map[String, Int]().empty
      for ((key, value) <- hashEncodeMap.iterator) {
        if (sparseFeatureMap.contains(Utils.encodeFeatureName(key))) {
          sparseFeatureMap(Utils.encodeFeatureName(key)) += "hashBucket" -> value
          hash32FeatureMap(Utils.encodeFeatureName(key)) += "hashBucket" -> value
        } else {
          sparseFeatureMap += Utils.encodeFeatureName(key) -> mutable.Map[String, Any]("hashBucket" -> value)
          hash32FeatureMap += Utils.encodeFeatureName(key) -> mutable.Map[String, Any]("hashBucket" -> value)
        }
      }
      if (sparseFeatureMap.nonEmpty && isSaveOnlineFeatureMap) {
        if (isModuleInHash) {
          // 当前autoHash类型和separate、joint类型共用一个sparseFeatureMap存储
          // 修改属性的键为hash32，意义为：autoHash类型中使用hash32编码的数据
          onlineFeatureMap += "hash32" -> hash32FeatureMap
        }
      }
    }

    if (featureMaxIndexMap != null) {
      for (feat <- featureMaxIndexMap.keys) {
        sparseFeatureMap(Utils.encodeFeatureName(feat)) += "maxIndex" -> 1
      }
      transformMap += "maxIndexMap" -> featureMaxIndexMap.map(x => (Utils.encodeFeatureName(x._1), x._2))
    }
    transformMap += "sparse" -> sparseFeatureMap

    if (seqMaxLenMap != null) {
      transformMap += "seqMaxLength" -> seqMaxLenMap.map(x => (Utils.encodeFeatureName(x._1), x._2))
    }

    // 长序 hash bucket 独立保存，不混入 sparseFeatureMap
    if (longSeqHashEncodeMap.nonEmpty) {
      transformMap += "longSeqHashBucket" -> longSeqHashEncodeMap.map(x => (Utils.encodeFeatureName(x._1), x._2))
    }

    transformMap += "featureAlias" -> jointFeatureMap.flatMap(feature => {
      feature._2.map(name => (name, feature._1))
    }).map(x => (Utils.encodeFeatureName(x._1), Utils.encodeFeatureName(x._2)))

    if (jointFeatureMap.nonEmpty && isSaveOnlineFeatureMap) {
      onlineFeatureMap += "featureAlias" -> transformMap("featureAlias")
    }

    Utils.saveMutableMapV2(spark, transformMap, onlineTransformJsonSavePath)
    if (storageMode != null && storageMode.equalsIgnoreCase("nsp")) {
      info("current storageMode is nsp, start to writes data success file, and preload data")
      val basePath = handleNspSavePath(onlineTransformJsonSavePath, isNeedSuffix = false)
      val envConfig = NspUtils.init()
      val nspService = new NspService(envConfig.cloudMapConfig, envConfig.nspConfig, datasetInfo.getRegion)
      saveMutableMapByNsp(nspService, basePath, transformMap, datasetInfo.isCrossRegion)
      nspService.close()
      val DataMangerService = new DataMangerService()
      val preloadPaths = new util.ArrayList[String]()
      preloadPaths.add(basePath)
      info(s"preload path  ${basePath}")
      DataMangerService.preloadDataSet(datasetInfo, preloadPaths)
    }

    // 开启保存任务
    if (onlineFeatureMap.nonEmpty) {
      saveOnlineFeatureMap(spark, onlineFeatureMap)
    }

  }

  /**
   * 对于标记为autoHash编码类型的特征进行处理，分为两类：
   * 1.小模型下的增量哈希方案 - isHashTransform: false
   * 2.大模型下的哈希结合ps方案 - isHashTransform：true
   *
   * @param data            训练集
   * @param hashEncodeMap   feature_name -> feature_hash_bucket
   * @param hashBufPercent  增量场景下，hash_bucket需要预留空间
   * @param autoHashFeature 标记为autoHash属性的特征名
   * @param minCnt4Features 低频过滤阈值
   * @param minCount        全局低频过滤阈值
   * @param isModuleInHash  hash是否取模
   */
  def processAutoHash(data: DataFrame, hashEncodeMap: mutable.Map[String, Int], hashBufPercent: Double, autoHashFeature: Array[String], minCnt4Features: mutable.Map[String, Int], minCount: Int = 0, featuresNeedMod:Set[String]): Unit = {
    if (autoHashFeature == null || autoHashFeature.length == 0) return

    for (feat <- autoHashFeature) {
      LOGGER.info(s"current hash feature is ${feat}")
      // 小模型下hash才需要统计hash bucket,大模型下无需生成,因此此处置入默认值
      if (featuresNeedMod.contains(feat)) {
        generateHashBuckets(data, feat)
      } else {
        hashEncodeMap += feat -> DEFAULT_HASH_BUCKET
      }
    }
  }

  /**
   * 长序列 autoHash 特征 fit：统计 Array 元素去重数量，计算 hash bucket
   * 与普通 processAutoHash 不同：不 split，直接 explode Array
   *
   * @param data           训练集
   * @param hashEncodeMap  feature_name -> feature_hash_bucket
   * @param hashBufPercent 增量场景下预留空间
   * @param autoHashFeature 长序列 autoHash 特征名
   * @param minCnt4Features 低频过滤阈值
   * @param minCount        全局低频过滤阈值
   */
  def processAutoHashLongSeq(data: DataFrame, hashEncodeMap: mutable.Map[String, Int], hashBufPercent: Double,
                             autoHashFeature: Array[String], minCnt4Features: mutable.Map[String, Int], minCount: Int = 0,
                             featuresNeedMod: Set[String]): Unit = {
    if (autoHashFeature == null || autoHashFeature.length == 0) return

    for (feat <- autoHashFeature) {
      LOGGER.info(s"current long seq hash feature is ${feat}")
      if (featuresNeedMod.contains(feat)) {
        val minCnt = minCnt4Features.getOrElse(feat, minCount)
        info(s"minCnt for long seq ${feat} of hash is ${minCnt}")
        // Array 输入：直接 explode，不 split
        val hashBucket = (1 + hashBufPercent) * (data.select(feat)
          .withColumn(feat, explode(col(feat)))
          .filter(s"$feat <> ''")
          .groupBy(feat)
          .count()
          .filter(col("count") > minCnt)
          .count())
        hashEncodeMap += feat -> hashBucket.toInt
      } else {
        hashEncodeMap += feat -> DEFAULT_HASH_BUCKET
      }
    }
  }

  private def generateHashBuckets(data: DataFrame, feat: String): Unit = {
    // deal with no hashBufPercent, save autoHash feat as hash(${feat}) and update hashEncodeMap with its #distinct values
    // remember to set minCount for each feature
    if (hashEncodeMap.getOrElse(feat, 1) > 1) {
      return
    }
    if (feat.indexOf(Const.CONFIG_CROSS_SEP_CHAR) == (-1)) {
      val minCnt = minCnt4Features.getOrElse(feat, minCount)
      info(s"minCnt for ${feat} of hash is ${minCnt}")
      val hashBucket = (1 + hashBufPercent) * (data.select(feat)
        .withColumn(feat, explode(split(col(feat), FEATURE_SEP_CHAR)))
        .filter(s"$feat <> ''")
        .groupBy(feat)
        .count()
        .filter(col("count") > minCnt)
        .count())
      hashEncodeMap += feat -> hashBucket.toInt
    } else if (feat.split(Const.CONFIG_CROSS_SEP_CHAR).size == 2) {
      val featNameArr = feat.split(Const.CONFIG_CROSS_SEP_CHAR).map(_.trim)
      val splitMap = TransformConfigModule.getSplitCharMap()
      val sep1 = splitMap.getOrElse(featNameArr(0), FEATURE_SEP_CHAR)
      val sep2 = splitMap.getOrElse(featNameArr(1), FEATURE_SEP_CHAR)
      val crossUDF = Utils.crossUDF(sep1, sep2)
      val featName = feat.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR)
      val minCnt = minCnt4Features.getOrElse(featName, minCount)
      info(s"minCnt for ${feat} of hash is ${minCnt}")
      val hashBucket = data.withColumn(featName, crossUDF(feat.split(Const.CONFIG_CROSS_SEP_CHAR).map(col(_)): _*))
        .withColumn(featName, explode(split(col(featName), Pattern.quote("^"))))
        .filter(s"$featName <> ''")
        .groupBy(featName).count().filter(col("count") > minCnt).count() * (1 + hashBufPercent)
      hashEncodeMap += feat -> hashBucket.toInt
    } else throw new IllegalArgumentException(s"don't support more then 2 features' crossing but got ${feat}")
  }

  private def saveOnlineFeatureMap(spark: SparkSession, onlineFeatureMap: mutable.Map[String, Any]): Unit = {
    val fileName = "/online_feature_service_feature_map.json"
    val path = onlineTransformJsonSavePath.split(SLASH_SEP).dropRight(1).mkString(SLASH_SEP) + fileName
    var jointKey = ""

    info(s"the OnlineFeatureMap output path is $path")

    if (onlineFeatureMap.contains("sparse")) {
      val separateFeatureMap = onlineFeatureMap("sparse").asInstanceOf[mutable.Map[String, mutable.Map[String, Any]]]
      // 将joint特征中 featureAlias { a -> c, b -> c} ; feature_map{ c -> {...}}
      // 替换为 feature_map{ a -> {...},b -> {...}}
      if (onlineFeatureMap.contains("featureAlias")) {
        val featureAliasFeatureMap = onlineFeatureMap("featureAlias").asInstanceOf[immutable.Map[String, String]]

        for (key <- featureAliasFeatureMap.keys) {
          jointKey = featureAliasFeatureMap(key)
          separateFeatureMap += key -> separateFeatureMap(jointKey)

        }
        // 删除featureMap中存放的jointPrefix->{}记录
        for (key <- featureAliasFeatureMap.keys) {
          jointKey = featureAliasFeatureMap(key)
          separateFeatureMap -= jointKey
        }
        onlineFeatureMap -= "featureAlias"
      }
      // 将sparse、joint特征中的maxIndex替换为unknown
      for (value <- separateFeatureMap.values) {
        if (value.contains("maxIndex")) {
          value += "unknown" -> value("maxIndex")
          value.remove("maxIndex")
        }
      }

      // 将separate里面重复的hash32特征删除
      if (onlineFeatureMap.contains("hash32")) {
        val hash32FeatureMap = onlineFeatureMap("hash32").asInstanceOf[mutable.Map[String, mutable.Map[String, Any]]]
        for (key <- hash32FeatureMap.keys) {
          if (separateFeatureMap.contains(key)) {
            separateFeatureMap.remove(key)
          }
        }
      }

      onlineFeatureMap += "feature_map" -> separateFeatureMap
      onlineFeatureMap -= "sparse"
    }
    Utils.saveMutableMapV2(spark, onlineFeatureMap, path)
    if (storageMode != null && storageMode.equalsIgnoreCase("nsp")) {
      info("current storageMode is nsp, start to writes data success file, and preload data")
      val basePath = handleNspSavePath(path, isNeedSuffix = false)
      val envConfig = NspUtils.init()
      val nspService = new NspService(envConfig.cloudMapConfig, envConfig.nspConfig, datasetInfo.getRegion)
      saveMutableMapByNsp(nspService, basePath, onlineFeatureMap, datasetInfo.isCrossRegion)
      nspService.close()
      val DataMangerService = new DataMangerService()
      val preloadPaths = new util.ArrayList[String]()
      preloadPaths.add(basePath)
      info(s"preload path  ${basePath}")
      DataMangerService.preloadDataSet(datasetInfo, preloadPaths)
    }


  }