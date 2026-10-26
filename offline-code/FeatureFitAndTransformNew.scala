class FeatureFitAndTransformNew(taskMeta: TaskMetaData) extends RcmTaskBatch(taskMeta) {
  private val LOGGER = LoggerFactory.getLogger(this.getClass)
  override def execute(spark: SparkSession, input: mutable.Map[String, DataFrame]): mutable.Map[String, DataFrame] = {
    val param = taskMeta.parameter
    // check the input parameter
    checkInputParam(param)
    setCompressMode(spark, param)
    init(param)
    val featuresNeedMod = if(isModuleInHash) features.filter(_.isModuleInHash).map(_.name).toSet else Set[String]()
    // param init
    var featureMap = mutable.Map[String, Any]().empty
    var featureMapExtra = mutable.Map[String, Any]().empty
    var featureMaxIndexMap = mutable.Map[String, Int]().empty
    var realFeatureMaxIdxMap = mutable.Map[String, Int]().empty
    var featureIdxOverflow = mutable.Set.empty[String]
    var meanAndStdMap = mutable.Map[String, mutable.Map[String, Array[Double]]]().empty
    val preProcess = new Preprocess(taskMeta)
    val dagPreProcess = new DagPreprocess(taskMeta)
    // 仅管理本次执行新建的 Hive 缓存，不清理调用者已有的缓存。
    val ownedHiveCaches = mutable.LinkedHashMap.empty[String, DataFrame]
    def releaseHiveCache(key: String): Unit = {
      ownedHiveCaches.get(key).foreach { df =>
        df.unpersist(blocking = true)
        ownedHiveCaches.remove(key)
      }
    }
    def loadOwnedHiveData(sql: String, key: String, source: DataFrame): DataFrame = {
      val df = loadHiveData(spark, sql, key, partitionNum, false, storageLevel, source)
      if (df != null && isCache && df.storageLevel == StorageLevel.NONE) {
        val level = StorageLevel.fromString(storageLevel)
        if (level != StorageLevel.NONE) {
          df.persist(level)
          ownedHiveCaches.put(key, df)
        }
      }
      df
    }
    def outputPartitionsFor(key: String): Int = key match {
      case "train" => param.getInt("trainOutputRepartitionNum", Some(outputRepartitionNum))
      case "valid" => param.getInt("validOutputRepartitionNum", Some(-1))
      case "test" => param.getInt("testOutputRepartitionNum", Some(-1))
      case _ => -1
    }
    // DAG 广播必须保留到所有计算、保存完成；异常时也释放。
    try {
    val syncBasePath = configSaveRootPath + "/sync/feature_map"
    // 特征转换时，随机掩埋部分比例特征增加泛化能力
    Const.setMaskRate(maskRate)
    // 增量任务需要加载的config路径
    var preConfigLoadPath = ""
    // 增量任务increaseDataBaseVer.txt需要更新的周期
    var increaseDataBaseVer = ""
    // 是否需要更新increaseTransformBaseVer.txt
    var updateTransformBaseDateFlag = false
    if (!sampleWeightMethod.equals("") && sampleWeightCol != null && sampleWeightCol(0).length == 0)
      throw new IllegalArgumentException("if use computeSampleWeightMethod, need to set sampleWeightCol")
    val nameNode = dataHdfsSavePath.split("(\\\\/)+")(0) + "//" + dataHdfsSavePath.split("(\\\\/)+")(1)
    spark.sparkContext.hadoopConfiguration.set("fs.defaultFS", s"${nameNode}")
    spark.conf.set("fs.defaultFS", s"${nameNode}")
    val suffixName = "_Index"
    val dataSrcCol = "data_src"
    val inputDF = if (input.contains(INPUT_NAME_DATA)) input(INPUT_NAME_DATA) else null
    // 构建训练集、验证集与测试集
    var Array(trainData, validData, testData) = Array(Tuple2(trainSql, "train"), Tuple2(validSql, "valid"), Tuple2(testSql, "test")).map(
      x => loadOwnedHiveData(x._1, x._2, inputDF)
    )
    if (param.getBoolean("consistencyCheck", Some(false))){
      if (!uniqueId.contains("column_id")) {
        uniqueId :+ "column_id"
      }
      val consistencyChecker = new ConsistencyCheckTransform(taskMeta, tranFeatures)
      testData = consistencyChecker.execute(spark, testData)
    }
    if (param.getBoolean("fillDefaultValue", Some(false))){
      trainData = fillDefaultValue(trainData,featureDefaultValue, labelCol, uniqueId, "train")
      validData = fillDefaultValue(validData,featureDefaultValue, labelCol, uniqueId, "valid")
      testData = fillDefaultValue(testData,featureDefaultValue, labelCol, uniqueId, "test")
    }
    val originTrainDF: DataFrame = trainData.select(trainData.columns.map(col): _*)
    if (trainData == null && validData == null && testData == null) {
      throw new IllegalArgumentException("trainSql, validSql, testSql are all empty, please check...")
    }
    // 获取训练集、验证集、测试集的样本数量
    var Array(trainDataCnt, validDataCnt, testDataCnt) = Array(trainData, validData, testData).map(x => if (x != null) x.count() else 0)
    info(s"Datacount train:$trainDataCnt  valid:$validDataCnt  test:$testDataCnt")
    // 如果进行分组, 需要分组后计算样本数量
    if (groupParams.getOrElse("groupKey", "").nonEmpty){
      val groupPhase = groupParams.getOrElse("groupPhase", "")
      val groupKeyOpt = groupParams.getOrElse("groupKey", "")
      // 只在配置了对应 groupPhase 时统计分组数；否则保留前面的样本数。
      // 不能把原始 DataFrame 放入 grouped\* 后再 collect().length，否则会把全量样本拉到 Driver。
      val groupedTrainData = if (groupPhase.split(COMMA_SEP).contains("train"))
        trainData.groupBy(groupKeyOpt).count() else null
      val groupedValidData = if (groupPhase.split(COMMA_SEP).contains("valid"))
        validData.groupBy(groupKeyOpt).count() else null
      val groupedTestData = if (groupPhase.split(COMMA_SEP).contains("test"))
        testData.groupBy(groupKeyOpt).count() else null
      // 保留原分组条件和完整行求值，以 RDD count 避免 SQL 列裁剪及全量结果进入 Driver。
      trainDataCnt = if (groupedTrainData != null) groupedTrainData.rdd.count() else trainDataCnt
      validDataCnt = if (groupedValidData != null) groupedValidData.rdd.count() else validDataCnt
      testDataCnt = if (groupedTestData != null) groupedTestData.rdd.count() else testDataCnt
    }
    if (trainDataCnt == 0 || validDataCnt == 0 || testDataCnt == 0) {
      throw new IllegalArgumentException("train set count, valid set count or test set count may be 0, please check...")
    }
    // 获取外部数据
    var externalData = loadOwnedHiveData(externalSql, "external", null)
    var unionData: DataFrame = null
    var externalOnly: Boolean = false
    if (externalData != null) {
      if (labelCol.length == 1 && labelCol(0).isEmpty) throw new IllegalArgumentException(s"to use externalsql need input label")
      val defaultCxr = SamplingUtils.getPositiveProportion(trainData, labelCol(0))
      info(s"=========== default cxr: $defaultCxr")
      // cxr 参数
      val cxr = Utils.getParamDouble(param, CXR, defaultCxr)
      // 判断是否有三方数据并进行采样
      if (sampleScope.equals("external")) {
        externalData = SamplingUtils.externalSampling(spark, trainData, externalData, labelCol(0), cxr)
        // sampleScope 配置为 external，开关决定是否只保存采样数据
        externalOnly = TransformParamModule.externalOnly
      } else if (sampleScope.equals("third_dsp")) {
        externalData = SamplingUtils.thirdDspSampling(spark, externalData, labelCol(0), cxr)
      }
      // 添加 data_src 列
      externalData = externalData.withColumn(dataSrcCol, lit(0))
      trainData = trainData.withColumn(dataSrcCol, lit(1))
      validData = validData.withColumn(dataSrcCol, lit(1))
      testData = testData.withColumn(dataSrcCol, lit(1))
      unionData = SamplingUtils.unionDataFrame(trainData, externalData)
      // shuffle
      unionData = SamplingUtils.shuffleDataSet(unionData)
      separateEncodeFeatures = separateEncodeFeatures :+ dataSrcCol
    }
    // DAG 接入：在素材拆分和 DAG 前确定训练数据源，后续不再覆盖。
    if (unionData != null) {
      trainData = unionData
    }
    val materialFeatures = features.filter(feature => feature.isMaterial == true)
      .map(feature => (feature.name -> feature.creative_sep)).toMap
    info(s"materialFeatures is:{${materialFeatures}}")
    val materialSwitch = TransformParamModule.materialSwitch
    info(s"materialSwitch is:{${materialSwitch}}")
    if (materialSwitch) {
      require(materialFeatures.nonEmpty, s"there is no material features,please close materialSwitch!")
      info(s"split material started")
      trainData = splitMaterializedColumns(trainData, materialFeatures)
      validData = splitMaterializedColumns(validData, materialFeatures)
      testData = splitMaterializedColumns(testData, materialFeatures)
      info(s"split material ended")
    }
    val varLenDenseFeatures = (denseFeatures ++ pureDenseFeatures).filter(seqMaxLenMap.contains)
    // 三个互斥分支各调用一次；不改变原有 Preprocess 的执行范围。
    def runDagPreprocess(): Unit = {
      val arrayOutputNames = (
        Option(pureDenseLongSeqFeatures).toSeq.flatMap(_.toSeq) ++
          Option(autoHashLongSeqFeatures).toSeq.flatMap(_.toSeq) ++
          TransformParamModule.longSeqHashEncodeMap.keys
        ).toSet
      val dagResult = dagPreProcess.execute(
        spark,
        mutable.LinkedHashMap(
          "train" -> trainData,
          "valid" -> validData,
          "test" -> testData
        ),
        arrayOutputNames
      )
      // 只预热 train；valid/test 保持惰性。计数不覆盖后面的业务样本数。
      if (param.getBoolean("materializeTrainDagBeforeFit", Some(true)) &&
          (!TransformParamModule.transform || StringUtils.isBlank(previousDate)) &&
          ownedHiveCaches.contains("train") && dagPreProcess.hasCachedOutput("train")) {
        val dagRows = dagResult("train").count()
        info(s"Train DAG materialized before fit: rows=$dagRows")
        releaseHiveCache("train")
      }
      var newTrainData = dagResult.getOrElse("train", null)
      val newValidData = dagResult.getOrElse("valid", null)
      val newTestData = dagResult.getOrElse("test", null)
      // DERIVED 列必须先生成，训练集的变长 dense 只处理一次。
      if (varLenDenseFeatures.nonEmpty && newTrainData != null) {
        newTrainData = if (TransformParamModule.useColumnExprOpt) {
          FeatureFitAndTransformNew.processVarLenDenseFeatureOpt(
            newTrainData, varLenDenseFeatures)
        } else {
          FeatureFitAndTransformNew.processVarLenDenseFeature(
            newTrainData, varLenDenseFeatures)
        }
      }
      // DAG 输出缓存由 DagPreprocess 通过 dagOutputStorageLevel 参数管理（默认 NONE 不缓存）。
      trainData = newTrainData
      validData = newValidData
      testData = newTestData
    }
    // step2: fit the feature, include dense、pureDense、sparse feature、jointEncode、hashEncode
    if (!TransformParamModule.transform) {
      // preProcess
      trainData = preProcess.execute(spark, mutable.Map("train" -> trainData)).getOrElse("train", null)
      validData = preProcess.execute(spark, mutable.Map("valid" -> validData)).getOrElse("valid", null)
      testData = preProcess.execute(spark, mutable.Map("test" -> testData)).getOrElse("test", null)
      runDagPreprocess()
      // fit the dense feature and add the value to meanAndStdMap
      // when transform is false, adctr will fit the train data to get the needed parameter file
      meanAndStdMap = meanAndStdMap.++(FeatureFitAndTransformNew.fitDenseFeature(spark, trainData, denseFeatures, normType, seqMaxLenMap))
      // fit the pure dense feature => not use norm action
      meanAndStdMap = meanAndStdMap.++(FeatureFitAndTransformNew.fitDenseFeature(spark, trainData, pureDenseFeatures, "keep", seqMaxLenMap))
      // fit the long sequence pureDense feature => Array input, no split
      meanAndStdMap = meanAndStdMap.++(FeatureFitAndTransformNew.fitDenseLongSeqFeature(spark, trainData, pureDenseLongSeqFeatures, seqMaxLenMap))
      // fit the raw feature
      val rawSeqMaxLengthMap = FeatureFitAndTransformNew.fitRawFeature(spark, trainData, rawEncodeFeatures, seqMaxLenMap)
      seqMaxLenMap = seqMaxLenMap.++(rawSeqMaxLengthMap)
      // fit the separate encode feature
      val tempTuple = FeatureFitAndTransformNew.fitSeperateFeature(spark, trainData, separateEncodeFeatures, featureMap, featureMapExtra, featureMaxIndexMap, seqMaxLenMap, minCnt4Features, minCount = minCount)
      featureMap = tempTuple._1
      featureMapExtra = tempTuple._2
      featureMaxIndexMap = tempTuple._3
      seqMaxLenMap = tempTuple._4
      // fit the joint encode feature
      val tempTuple2 = FeatureFitAndTransformNew.fitJointFeature(spark, trainData, jointEncodeFeatures, featureMap, featureMapExtra, featureMaxIndexMap, seqMaxLenMap, minCount = minCount)
      featureMap = tempTuple2._1
      featureMapExtra = tempTuple2._2
      featureMaxIndexMap = tempTuple2._3
      seqMaxLenMap = tempTuple2._4
      // fit the joint encode feature, update the hashEncodeMap
      FeatureFitAndTransformNew.processAutoHash(trainData, hashEncodeMap, hashBufPercent, autoHashFeatures++(autoHashWeightedFeatures), minCnt4Features, minCount, featuresNeedMod)
      // fit the long sequence autoHash feature => Array input, no split
      // 写入独立的 longSeqHashEncodeMap，不混入 hashEncodeMap
      if (autoHashLongSeqFeatures.nonEmpty) {
        FeatureFitAndTransformNew.processAutoHashLongSeq(trainData, TransformParamModule.longSeqHashEncodeMap, hashBufPercent, autoHashLongSeqFeatures, minCnt4Features, minCount, featuresNeedMod)
      }
      hashEncodeMap = mutable.Map[String, Int](hashEncodeMap.toSeq.map(x => (x._1.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR), x._2)): _*)
      if (!hashEncodeMap.isEmpty) {
        for (key <- hashEncodeMap.keys) {
          featureMaxIndexMap += key -> hashEncodeMap(key)
        }
        val tmpSeqMaxLengthMap = FeatureFitAndTransformNew.fitHashFeature(spark, trainData,
          hashEncodeMap.keys.toArray, seqMaxLenMap)
        seqMaxLenMap = seqMaxLenMap.++(tmpSeqMaxLengthMap)
      }
      // fit the weighted feature
      val tempTuple3 = FeatureFitAndTransformNew.fitWeightedSeparateFeature(spark, trainData, weightedSeqFeatures, featureMap, featureMapExtra, featureMaxIndexMap, seqMaxLenMap, minCount = minCount)
      info("================= weightedSeqFeature fit finished =================")
      featureMap = tempTuple3._1
      featureMapExtra = tempTuple3._2
      featureMaxIndexMap = tempTuple3._3
      seqMaxLenMap = tempTuple3._4
      // fit the accumulated_encoded features
      val result = FeatureFitAndTransformNew.fitAccumulatedFeaturesByFull(spark, trainData, accumulateFeatures, featureMap, featureMapExtra, featureMaxIndexMap, realFeatureMaxIdxMap, seqMaxLenMap, minCount = minCount, accumulateBufPercent)
      info("================= accumulate features fit finished =================")
      featureMap = result._1
      featureMapExtra = result._2
      featureMaxIndexMap = result._3
      realFeatureMaxIdxMap = result._4
      seqMaxLenMap = result._5
      for (k <- featureMaxIndexMap.keys) {
        info(s"the max index of feature:${k} is ${featureMaxIndexMap(k)}")
      }
    } else if(StringUtils.isBlank(previousDate)){
      // 基于共享文件的增量transform逻辑
      var latestFullDate = ""
      var latestIncreaseData = ""
      try {
        latestFullDate = Utils.loadWholeLocalFile(LATEST_FULL_DATA_TXT)
        latestIncreaseData = Utils.loadWholeLocalFile(LATEST_INCREASE_DATA_TXT)
        increaseDataBaseVer = Utils.loadWholeLocalFile(INCREASE_DATA_BASE_VER_TXT)
      } catch {
        case ex: Exception => LOGGER.error(s"load file cause error with message : ${ex.getMessage}")
      }
      info(s"latestFullDate is ${latestFullDate}, increaseDataBaseVer is ${increaseDataBaseVer}")
      import org.apache.commons.lang3.StringUtils;
      val configRootPath = modelTrainConfigSavePath.split("\\\\/").reverse.dropWhile(_ != "config").reverse.mkString("/")
      if (StringUtils.isEmpty(latestFullDate)) {
        // first full transform has not finished, skip current incremental transform
        throw new Exception(s"full transform has not finished, skip current incremental transform")
      }
      if (latestFullDate.equals(increaseDataBaseVer)) {
        // 找最新的且正确的增量transform的周期
        preConfigLoadPath = modelTrainConfigSavePath.split("\\\\/").dropRight(2).mkString("/") + '/' + latestIncreaseData
      } else {
        // 注意增量tfrecord生成好了之后(在saveHdfsFile方法之后),需要去更新transformBaseVer.txt
        preConfigLoadPath = configRootPath + "/full/" + latestFullDate
        updateTransformBaseDateFlag = true
        increaseDataBaseVer = latestFullDate
      }
      info(s"preConfigLoadPath is ${preConfigLoadPath}")
      //4月增量方案v3版本中对于增量的tfrecord存在路径从 /data/incremental/date -> /data/incremental/latestFullDate/date
      val tmpArray = dataLocalSavePath.split("\\\\/")
      val curIncrementPeriod = tmpArray(tmpArray.length - 1)
      dataLocalSavePath = tmpArray.dropRight(1).mkString("/") + "/" + latestFullDate + "/" + curIncrementPeriod
      info(s"dataLocalSavePath is ${dataLocalSavePath}")
      // load the given date json file to init the map
      val res = loadLocalConfigFile(spark,preConfigLoadPath)
      featureMap = res._1
      featureMaxIndexMap = res._2
      realFeatureMaxIdxMap = res._3
      seqMaxLenMap = res._4
      hashEncodeMap = res._5
      meanAndStdMap = res._6
      featureIdxOverflow = res._8
      LOGGER.info("Load all jsons successfully")
      // 增量 fit 也需要先生成衍生特征。
      runDagPreprocess()
      val result = FeatureFitAndTransformNew.fitAccumulatedFeaturesByIncrement(spark, trainData, accumulateFeatures, featureMap, featureMaxIndexMap, realFeatureMaxIdxMap, featureIdxOverflow, seqMaxLenMap, minCount)
      featureMap = result._1
      featureMaxIndexMap = result._2
      realFeatureMaxIdxMap = result._3
      seqMaxLenMap = result._4
      featureIdxOverflow = result._5
    } else {
      val previousDateConfigLoadPath = modelTrainConfigSavePath.split("\\\\/").dropRight(2).mkString("/") + '/' + previousDate
      info(s"previousDateConfigLoadPath is ${previousDateConfigLoadPath}")
      val res = loadLocalConfigFile(spark,previousDateConfigLoadPath)
      featureMap = res._1
      featureMaxIndexMap = res._2
      realFeatureMaxIdxMap = res._3
      seqMaxLenMap = res._4
      hashEncodeMap = res._5
      meanAndStdMap = res._6
      featureIdxOverflow = res._8
      // 指定历史版本仅 transform 时，也生成衍生特征。
      runDagPreprocess()
    }
    // save the map file
    featureMap ++= featureMapExtra
    Utils.saveMutableMapV2(spark, featureMap, configSaveRootPath + "/featureMap.json")
    Utils.saveMutableMapV2(spark, featureMaxIndexMap, configSaveRootPath + "/featureMaxIndexMap.json")
    Utils.saveMutableMapV2(spark, seqMaxLenMap, configSaveRootPath + "/seqMaxLenMap.json")
    Utils.saveMutableMapV2(spark, meanAndStdMap, configSaveRootPath + "/meanAndStdMap.json")
    Utils.saveMutableMapV2(spark, hashEncodeMap, configSaveRootPath + "/hashEncodeMap.json")
    Utils.saveMutableMapV2(spark, realFeatureMaxIdxMap, configSaveRootPath + "/realFeatureMaxIndexMap.json")
    Utils.saveTextFile(spark, featureIdxOverflow.mkString(","), configSaveRootPath + "/featureIdxOverflow.txt")

    if (storageMode != null && storageMode.equalsIgnoreCase("nsp")) {
      info("current storageMode is nsp, start to writes data success file, and preload data")
      val envConfig = NspUtils.init()
      val nspService = new NspService(envConfig.cloudMapConfig, envConfig.nspConfig, datasetInfo.getRegion)
      val basePath = handleNspSavePath(configSaveRootPath, isNeedSuffix = false)
      saveMutableMapByNsp(nspService, basePath+"/featureMap.json", featureMap,  datasetInfo.isCrossRegion)
      saveMutableMapByNsp(nspService, basePath+"/featureMaxIndexMap.json", featureMaxIndexMap,  datasetInfo.isCrossRegion)
      saveMutableMapByNsp(nspService, basePath+"/seqMaxLenMap.json", seqMaxLenMap,  datasetInfo.isCrossRegion)
      saveMutableMapByNsp(nspService, basePath+"/meanAndStdMap.json", meanAndStdMap,  datasetInfo.isCrossRegion)
      saveMutableMapByNsp(nspService, basePath+"/hashEncodeMap.json", hashEncodeMap,  datasetInfo.isCrossRegion)
      saveMutableMapByNsp(nspService, basePath+"/realFeatureMaxIndexMap.json", realFeatureMaxIdxMap,  datasetInfo.isCrossRegion)
      nspService.putObjectWithResponse(basePath+"/featureIdxOverflow.txt", featureIdxOverflow.mkString(",").getBytes())
      nspService.close()
      val DataMangerService = new DataMangerService()
      val preloadPaths = new util.ArrayList[String]()
      preloadPaths.add(basePath)
      info(s"preload path  ${basePath}")
      DataMangerService.preloadDataSet(datasetInfo, preloadPaths)
    }
    val chunkSize = 1024 * 1024
    Utils.writeMapToHDFSInChunks(featureMap, syncBasePath, chunkSize)
    val broadcastChunkInfo = spark.sparkContext.broadcast(ChunkInfo(syncBasePath, (featureMap.size + chunkSize - 1) / chunkSize))

    // step3: transform the data
    if (unionData != null) {
      // 保留 DAG 结果；原有样本计数逻辑不变。
      if (externalOnly) {
        trainDataCnt = externalData.count()
      } else {
        trainDataCnt = trainData.count()
      }
      info(s"=========== unionData count = ${trainDataCnt}")
    }

    if (materialSwitch) {
      trainDataCnt = trainData.count()
      testDataCnt = testData.count()
      validDataCnt = validData.count()
    }

    // debug模式下, 输出数据集的相关统计指标,  map("MissingRate" -> Map("train" -> Map(fea1 -> value1), "valid" -> Map(fea1 -> value1)))
    var statInfoMap: mutable.Map[String, Any] = mutable.Map.empty[String, Any]
    if (isDebugMode) {
      SqlTemplateExecutor.sqlTemplate(spark, Array(Tuple3("train", trainData, trainDataCnt),
        Tuple3("valid", validData, validDataCnt), Tuple3("test", testData, testDataCnt)), labelCol, statInfoMap, "MissingRate", buildMissingValueSqlBuffer)
      if (!(labelCol.length == 1 && labelCol(0).isEmpty)) {
        SqlTemplateExecutor.sqlTemplate(spark, Array(Tuple3("train", trainData, trainDataCnt),
          Tuple3("valid", validData, validDataCnt), Tuple3("test", testData, testDataCnt)), labelCol, statInfoMap, "OtherStatInfo", buildOtherStatSqlBuffer)
        if (trainSql.contains(",pt_h") | trainSql.contains(", pt_h")) {
          SqlTemplateExecutor.sqlTemplate(spark, Array(Tuple3("train", trainData, trainDataCnt),
            Tuple3("valid", validData, validDataCnt), Tuple3("test", testData, testDataCnt)), labelCol, statInfoMap, "PartitionStatInfo", buildMoreInfoStatSqlBuffer, true)
        }
      }
      if (materialSwitch) statInfoMap = FeatureFitAndTransformNew.removeGroupId(statInfoMap)
      info(s"statInfoMap is ${statInfoMap}")
    }

    var featureCols = Array[String]()

    for (data <- Array(Tuple2(trainData, "train"), Tuple2(validData, "valid"), Tuple2(testData, "test"))) {
      var tempData = data._1
      val dataType = data._2
      if (varLenDenseFeatures.nonEmpty && dataType != "train") {
        tempData =
        if (TransformParamModule.useColumnExprOpt) {
          FeatureFitAndTransformNew.processVarLenDenseFeatureOpt(tempData, varLenDenseFeatures)
        } else {
          FeatureFitAndTransformNew.processVarLenDenseFeature(tempData, varLenDenseFeatures)
        }
      }


      info("================= dense feature transform started =================")
      // dense norm
      tempData = FeatureFitAndTransformNew.transformDenseFeature(tempData, denseFeatures, suffixName, meanAndStdMap, normType)
      info("================= dense feature transform finished =================")

      // pure dense`
      tempData = FeatureFitAndTransformNew.transformDenseFeature(tempData, pureDenseFeatures, suffixName, meanAndStdMap, "keep")
      info("================= pure dense feature transform finished =================")

      // long sequence pureDense transform => Array input, no split
      if (pureDenseLongSeqFeatures.nonEmpty) {
        tempData = FeatureFitAndTransformNew.processVarLenDenseLongSeqFeature(tempData, pureDenseLongSeqFeatures)
        info("================= long seq varLen dense feature pad/truncate finished =================")
        tempData = FeatureFitAndTransformNew.transformDenseLongSeqFeature(tempData, pureDenseLongSeqFeatures, suffixName, meanAndStdMap)
        info("================= long seq pureDense feature transform finished =================")
      }

      // transform the raw encode feature
      tempData = FeatureFitAndTransformNew.transformRawFeature(tempData, suffixName, seqMaxLenMap, RawNumTransform, isPadding, rawEncodeFeatures)
      info("================= raw encode feature transform finished =================")
      // transform the separate encode feature
      tempData = FeatureFitAndTransformNew.transformCategoricalFeature(modelTrainConfigSavePath, tempData, SEPARATE, suffixName, featureMap, Array(featureMaxIndexMap, seqMaxLenMap), dataType, broadcastChunkInfo, isPadding)
      info("================= SEPARATE encode feature transform finished =================")

      // transform the joint encode feature



      tempData = FeatureFitAndTransformNew.transformCategoricalFeature(modelTrainConfigSavePath, tempData, JOINT, suffixName, featureMap, Array(featureMaxIndexMap, seqMaxLenMap), dataType, broadcastChunkInfo, isPadding)
      info("================= joint encode feature transform finished =================")

      // transform the weighted separate encode feature
      tempData = FeatureFitAndTransformNew.transformCategoricalFeature(modelTrainConfigSavePath, tempData, WEIGHTED_SEQ, suffixName, featureMap, Array(featureMaxIndexMap, seqMaxLenMap), dataType, broadcastChunkInfo, isPadding)
      info("================= weightedSeqFeature transform finished =================")

      // transform the accumulate encode features
      tempData = FeatureFitAndTransformNew.transformCategoricalFeature(modelTrainConfigSavePath, tempData, ACU, suffixName, featureMap, Array(featureMaxIndexMap, seqMaxLenMap), dataType, broadcastChunkInfo, isPadding)
      info("================= accumulateEncode transform finished =================")

      // transform the hash encode feature
      info(s"*************${dataType}*************")
      if (TransformConfigModule.hashJointPrefixMap == null || TransformConfigModule.hashJointPrefixMap.isEmpty) {
        TransformConfigModule.hashJointPrefixMap = loadAndParseModelFeatureSet(MODEL_FEATURE_SET)
          .filter(feature => feature.encode.equals(HASH) || feature.encode.equals(AUTO_HASH) || feature.encode.equals(AUTO_HASHWEIGHTED) )
          // 将交叉特征中的#号连接符替换为__
          .map(feature => (feature.name.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR), feature.hashJointPrefix))
          .toMap
      }
      // hashEncodeMap 已不含长序特征（长序写入独立的 longSeqHashEncodeMap）
      if(doComputeCollision){
        HashEncode.computeCollision(spark, tempData, hashEncodeMap, isModuleInHash, null)
      }

      tempData = FeatureFitAndTransformNew.transformHashFeature(tempData, suffixName, hashEncodeMap, seqMaxLenMap, minCnt4Features, isPadding, minCount, isDoubleHash, null)
      info("================= hash Encode transform finished =================")

      // long sequence autoHash transform => Array input, no split
      if (TransformParamModule.longSeqHashEncodeMap.nonEmpty) {
        tempData = FeatureFitAndTransformNew.transformHashLongSeqFeature(tempData, suffixName, TransformParamModule.longSeqHashEncodeMap, seqMaxLenMap, isPadding)
        info("================= long seq hash Encode transform finished =================")
      }
      dataType match {
        case "train" => trainData = tempData
        case "valid" => validData = tempData
        case "test" => testData = tempData
      }
    }

    // step4: 保存featureMap以及后续训练所需文件
    // 仅在fit时生成featureMap文件
    FeatureFitAndTransformNew.saveTransformMap(spark, param, featureMap, featureMaxIndexMap, seqMaxLenMap, meanAndStdMap, hashEncodeMap, jointEncodeFeatures, TransformParamModule.longSeqHashEncodeMap)

    if (sampleZipNum > 1) {
      trainDataCnt = trainDataCnt - trainDataCnt % sampleZipNum
      validDataCnt = validDataCnt - validDataCnt % sampleZipNum
      testDataCnt = testDataCnt - testDataCnt % sampleZipNum
    }
    if (saveByColumn.isEmpty || !saveByColumn.equalsIgnoreCase("true")) {
      FeatureFitAndTransformNew.saveModelTrainConfig(spark, modelTrainConfigSavePath, dataLocalSavePath,
        featureMaxIndexMap, seqMaxLenMap, trainDataCnt, validDataCnt, testDataCnt, onlyTransform = TransformParamModule.transform, statInfoMap, sampleZipNum, groupParams)
    } else {
      val trainCounts = if (trainData != null) trainData.groupBy("pt_d").count().collect()
        .map(r => r.getString(0) -> r.getLong(1)).toMap else Map.empty[String, Long]
      val validCounts = if (validData != null) validData.groupBy("pt_d").count().collect()
        .map(r => r.getString(0) -> r.getLong(1)).toMap else Map.empty[String, Long]
      val testCounts = if (testData != null) testData.groupBy("pt_d").count().collect()
        .map(r => r.getString(0) -> r.getLong(1)).toMap else Map.empty[String, Long]

      val allDates = trainCounts.keySet ++ validCounts.keySet ++ testCounts.keySet
      allDates.foreach { date =>
        val dataPath = String.join(SLASH_SEP, savePathConfigByColumn, date)
        val tempPath = String.join(SLASH_SEP, dataPath, "modelTrain.json")
        FeatureFitAndTransformNew.saveModelTrainConfig(spark, tempPath, dataLocalSavePath,
          featureMaxIndexMap, seqMaxLenMap, trainCounts.getOrElse(date, 0L), validCounts.getOrElse(date, 0L), testCounts.getOrElse(date, 0L), onlyTransform = TransformParamModule.transform, statInfoMap, sampleZipNum, groupParams)
      }
    }

    // step可选: 生成一致性校验文件
    if (consistencyCheckEnable) {
      try {
        val sampledTrainData: DataFrame = SamplingUtils.samplingByCount(trainData, consistencyCheckSampleNum)
        startConsistencyCheck(spark, suffixName, sampledTrainData)
      } catch {
        case ex: Exception =>
          LOGGER.error(s"consistencyCheck error with message: ${ex.getMessage}", ex)
      }
    }

    // step5: 将转换后DataFrame保存为csv|tfrecord文件格式
    // 选择label与转换后的特征
    if (!dataSrc && unionData != null) {
      // 三方数据，并且不保留 data_src 列 删除
      separateEncodeFeatures = separateEncodeFeatures.filterNot(_.equals(dataSrcCol))
    }

    if (denseFeatures != null && denseFeatures.length > 0)
      featureCols = featureCols.++(denseFeatures.map(_ + suffixName))
    if (pureDenseFeatures != null && pureDenseFeatures.length > 0)
      featureCols = featureCols.++(pureDenseFeatures.map(_ + suffixName))
    if (separateEncodeFeatures != null && separateEncodeFeatures.length > 0)
      featureCols = featureCols.++(separateEncodeFeatures.map(_.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR) + suffixName))

    if (rawEncodeFeatures != null && rawEncodeFeatures.length > 0)
      featureCols = featureCols.++(rawEncodeFeatures.map(_.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR) + suffixName))

    if (jointEncodeFeatures != null && jointEncodeFeatures.size > 0) {
      for (jointFeat <- jointEncodeFeatures) {
        featureCols = featureCols.++(jointFeat._2.map(_.trim + suffixName)
          .map(name => name.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR)))
      }
    }
    if (weightedSeqFeatures != null && weightedSeqFeatures.length > 0) {
      featureCols = featureCols.++(weightedSeqFeatures.map(_ + FEATURE_VALUE_SUFFIX))
      featureCols = featureCols.++(weightedSeqFeatures.map(_ + FEATURE_WEIGHT_SUFFIX))
    }

    if (hashEncodeMap.nonEmpty) {
      val hashEncodeFeatureBuffer = ArrayBuffer.empty[String]
      hashEncodeMap.keys.map(feature => {
        if (arrayContainsString(autoHashWeightedFeatures, feature)){
          hashEncodeFeatureBuffer += (feature + FEATURE_VALUE_SUFFIX + suffixName)
          hashEncodeFeatureBuffer += (feature + FEATURE_WEIGHT_SUFFIX + suffixName)
        }else{
          hashEncodeFeatureBuffer += (feature + suffixName)
        }
      })
      featureCols ++= hashEncodeFeatureBuffer
    }

    // 长序 hash 特征输出列
    if (TransformParamModule.longSeqHashEncodeMap.nonEmpty) {
      featureCols ++= TransformParamModule.longSeqHashEncodeMap.keys.map(_ + suffixName).toSeq
    }

    // 长序 pureDense 特征输出列
    if (pureDenseLongSeqFeatures != null && pureDenseLongSeqFeatures.length > 0) {
      featureCols = featureCols.++(pureDenseLongSeqFeatures.map(_ + suffixName))
    }

    if (accumulateFeatures != null && accumulateFeatures.length > 0) {
      featureCols = featureCols.++(accumulateFeatures.map(_.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR) + suffixName))
    }
    for (feaName <- featureCols){
      info(s"outFeatureNames:${feaName}")
    }


    // 按照时间顺序计算traindata里面的样本权重
    var selectedCol = Array[String]()
    var newCols = Array[String]()

    if (!(labelCol.length == 1 && labelCol(0).isEmpty)) {
      selectedCol = selectedCol.++(labelCol)
      newCols = newCols.++(labelCol)
    }
    if (materialSwitch) {
      selectedCol = selectedCol.++(Array(Const.GROUP_ID))
      newCols = newCols.++(Array(Const.GROUP_ID))
    }
    if (saveByColumn.nonEmpty && saveByColumn.equalsIgnoreCase("true")) {
      selectedCol = selectedCol.++(Array("pt_d"))
      newCols = newCols.++(Array("pt_d"))
    }
    if (sampleWeightCol != null && sampleWeightCol(0).length != 0) {
      if (!sampleWeightMethod.equals("")) {
        trainData = FeatureFitAndTransformNew.computeSampleWeight(trainData, sampleWeightCol(0), sampleWeightMethod)
      }
      selectedCol = selectedCol.++(sampleWeightCol).++(uniqueId).++(featureCols)
      newCols = newCols.++(sampleWeightCol).++(uniqueId).++(featureCols)
    } else {
      selectedCol = selectedCol.++(uniqueId).++(featureCols)
      newCols = newCols.++(uniqueId).++(featureCols)
    }

    // 增加透传字段
    if (requiredPassFeatures.nonEmpty) {
      selectedCol = selectedCol.++(requiredPassFeatures)
      newCols = newCols.++(requiredPassFeatures)
    }

    val finalSelectedCol = selectedCol.map(col(_))
    val finalNewCols = newCols.map(name => Utils.encodeFeatureName(name.replaceAll(suffixName, "")))

    for (data <- Array(Tuple2(trainData, "train"), Tuple2(validData, "valid"), Tuple2(testData, "test"))) {
      var tempData = data._1
      val dataType = data._2
      if (tempData != null) {
        if (dataType.equals("train") && externalOnly) {
          tempData = tempData.filter(s"$dataSrcCol == 0").toDF()
        }
        LogUtil.infoLog(LOGGER, s"${dataType} dataset have been saved successfully.")
        tempData = tempData.select(finalSelectedCol: _*).toDF(finalNewCols: _*)
        if (isFeatureConcat) {
          val sparseConcatExpr = sparseFeatureCols.sortWith(_ < _).map(col).reduce((a, b) => concat(a, b))
          val denseConcatExpr = denseFeatureCols.sortWith(_ < _).map(col).reduce((a, b) => concat(a, b))
          val allFeatureCols = sparseFeatureCols.++(denseFeatureCols)
          tempData = tempData.withColumn("sparse_concat", sparseConcatExpr).withColumn("dense_concat", denseConcatExpr).drop(allFeatureCols: _*)
        }
        if (saveByColumn.isEmpty || !saveByColumn.equalsIgnoreCase("true")) {
          FeatureFitAndTransformNew.saveHdfsFile(spark, tempData, labelCol, saveFormat, dataHdfsSavePath, outputPartitionsFor(dataType), resultFileSizeInMB, dataType, compress, sampleZipNum, groupParams, materialFeatures, materialSwitch, TransformParamModule.multipartUploadThreshold)
        } else {
          val uniqueValues = tempData.select(col("pt_d"))
            .distinct()
            .collect()
            .map(_.getString(0))
          uniqueValues.foreach { value =>
            val filteredDF = tempData.filter(col("pt_d") === value)
            val saveByColumnPath = savePathByColmun + "/" + value
            FeatureFitAndTransformNew.saveHdfsFile(spark, filteredDF, labelCol, saveFormat, saveByColumnPath, outputPartitionsFor(dataType), resultFileSizeInMB, dataType, compress, sampleZipNum, groupParams, materialFeatures, materialSwitch, TransformParamModule.multipartUploadThreshold)
          }
        }
        LogUtil.infoLog(LOGGER, s"${dataType} dataset have been saved successfully.")
      }
      // train 的 meta 采样仍依赖 DAG，留到 finally；其余数据集到此已消费完。
      if (dataType != "train" || metaFeatureList.isEmpty) {
        dagPreProcess.releaseOutput(dataType)
        releaseHiveCache(dataType)
      }
      dataType match {
        case "train" => trainData = tempData
        case "valid" => validData = tempData
        case "test" => testData = tempData
      }
    }

    if (metaFeatureList.nonEmpty) {
      val isDebugMode = TransformParamModule.isDebugMode
      val (metaGroupA, metaGroupB) = stratifiedMetaFeatureSampling(spark, trainData, metaMinBatch, metaFeatureList, isDebugMode)
      FeatureFitAndTransformNew.saveHdfsFile(spark, metaGroupA, labelCol, saveFormat, dataHdfsSavePath, outputRepartitionNum, resultFileSizeInMB, "meta/groupA", compress, sampleZipNum, groupParams, materialFeatures, materialSwitch, TransformParamModule.multipartUploadThreshold)
      FeatureFitAndTransformNew.saveHdfsFile(spark, metaGroupB, labelCol, saveFormat, dataHdfsSavePath, outputRepartitionNum, resultFileSizeInMB, "meta/groupB", compress, sampleZipNum, groupParams, materialFeatures, materialSwitch, TransformParamModule.multipartUploadThreshold)
    }

    if (storageMode != null && storageMode.equalsIgnoreCase("nsp")) {
      val envConfig = NspUtils.init()
      val nspService = new NspService(envConfig.cloudMapConfig, envConfig.nspConfig, datasetInfo.getRegion)
      val basePath = handleNspSavePath(dataHdfsSavePath)
      if (TransformParamModule.isWriteSuccessFile) {
        info("data write success,now to write success_file")
        val success_file = basePath + "_SUCCESS"
        nspService.putObjectWithResponse(success_file, "_SUCCESS".getBytes(Charset.forName("UTF-8")), datasetInfo.isCrossRegion)
      } else {
        info("skip writing success_file, isWriteSuccessFile is false")
      }
      nspService.close()
      info("data write success,now to preload")
      val DataMangerService = new DataMangerService()
      val preloadPaths = new util.ArrayList[String]()
      preloadPaths.add(basePath)
      info(s"preload path  ${basePath}")
      DataMangerService.preloadDataSet(datasetInfo, preloadPaths)
    }

    // 增量transform下,increaseTransformBaseVer.txt
    if (TransformParamModule.transform && updateTransformBaseDateFlag) {
      var savePath = modelTrainConfigSavePath.split("\\/").dropRight(2).mkString("/") + "/" + INCREASE_DATA_BASE_VER_TXT
      if (modelTrainConfigSavePath.split("\\/").dropWhile(_ != "config").length == 4) {
        savePath = modelTrainConfigSavePath.split("\\/").reverse.dropWhile(_ != "config").reverse.mkString("/") + "/" + INCREASE_DATA_BASE_VER_TXT
      }
      info(s"savepath : ${savePath}")
      Utils.saveTextFile(spark, increaseDataBaseVer, savePath)
      info(s"successfully save increaseDataBaseVer : ${increaseDataBaseVer}")
    }
    // 缓存由下方 finally 按所有权释放。
    mutable.Map("train" -> originTrainDF)
    } finally {
      // 每个缓存独立清理，单个清理失败不阻断其他资源释放。
      ownedHiveCaches.keys.toList.foreach { key =>
        try releaseHiveCache(key)
        catch { case scala.util.control.NonFatal(e) =>
          LOGGER.warn(s"Failed to release Hive cache: $key", e)
        }
      }
      try {
        dagPreProcess.close()
      } catch {
        case scala.util.control.NonFatal(e) =>
          LOGGER.warn("Failed to close DAG preprocess", e)
      }
    }
  }

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
    val numPartitions = if (outputPartition > 0) {
      // 用户明确配置了输出分区数，直接用，不估算
      outputPartition
    } else {
      // 一个 action 同时计数和抽样估算，避免 count 后再次扫描。
      // 固定 partition seed 使同一分区布局下重试使用相同采样序列。
      val sampleFraction = 0.001
      val sampleStats = input.rdd.mapPartitionsWithIndex { (partitionId, rows) =>
        val random = new java.util.Random(1729L + partitionId.toLong)
        var count = 0L
        var bytes = 0L
        while (rows.hasNext) {
          val row = rows.next()
          count += 1L
          if (random.nextDouble() < sampleFraction) {
            bytes += row.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
          }
        }
        Iterator.single((count, bytes))
      }.fold((0L, 0L)) { (left, right) =>
        (left._1 + right._1, left._2 + right._2)
      }
      val cnt = sampleStats._1
      info(s"data count is ${cnt}")

      if (cnt > 10000) {
        // 定义不同输出格式和压缩方式的修正系数，模拟写入后实际数据大小的变化
        val formatFactors: Map[String, Double] = Map(
          "csv" -> (if (compress) 0.07 else 0.7),
          "parquet" -> (if (compress) 0.06 else 0.06),
          "tfrecord" -> (if (compress) 0.03 else 0.90)
        )
        val factor = formatFactors.getOrElse(fileFormat.toLowerCase, 1.0)

        val sampleSizeInBytes = sampleStats._2

        val estimatedTotalSizeInBytes = sampleSizeInBytes * (1 / sampleFraction) * factor
        val estimatedTotalSizeInMB = estimatedTotalSizeInBytes / (1024 * 1024)

        LOGGER.info(s"estimated $dataType dataset size is ${estimatedTotalSizeInMB / 1024}GB")

        // 计算分区数
        if (sampleSizeInBytes == 0L) {
          // 空样本不能推断为零字节，也不能给 writer 传入 0 分区。
          math.max(1, input.rdd.getNumPartitions)
        } else if (resultFileSizeInMB > 0) {
          math.max(1, Math.ceil(estimatedTotalSizeInMB / resultFileSizeInMB).toInt)
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
    val dateBounds = dateInfo.first()
    var inputs = input.withColumn("max_date", to_date(lit(dateBounds.get(0)), "yyyyMMdd"))
      .withColumn("min_date", to_date(lit(dateBounds.get(1)), "yyyyMMdd"))
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
