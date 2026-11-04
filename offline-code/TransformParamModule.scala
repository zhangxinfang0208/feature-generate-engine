```
case class JointFeatureInfo(jointPrefix:String, featureName:String)
object TransformParamModule {
  private val LOGGER = LoggerFactory.getLogger(this.getClass)

  private val DATE = "date"
  private val MODEL_JSON_PATH = "modelJsonPath"
  private val REPARTITION_NUM = "repartitionNum"
  private val OUTPUT_PARTITION_NUM = "outputPartitionNum"
  private val SAVE_FORMAT = "saveFormat"
  private val COMPRESS = "compress"
  private val MIN_COUNT = "minCount"
  private val TRAIN_SQL = "trainSql"
  private val VALID_SQL = "validSql"
  private val TEST_SQL = "testSql"
  private val EXTERNAL_SQL = "externalSql"
  private val NORM_TYPE = "normType"
  private val UNIQUE_ID = "uniqueId"
  private val LABEL = "label"
  private val DATA_SAVE_PATH = "dataSavePath"
  private val DATA_LOCAL_PATH = "dataLocalPath"
  private val ONLINE_TRANSFORM_JSON_SAVE_PATH = "onlineTransformJsonSavePath"
  private val MODEL_TRAIN_SAVE_PATH = "modelTrainSavePath"
  private val WARMUP_DATE = "warmupDate"
  private val PREVIOUS_DATE = "previousDate"
  private val MASK_RATE = "maskRate"
  private val HASH_BUF_PERCENT = "hashBufPercent"
  private val TRANSFORM = "transform"
  private val SAMPLE_WEIGHT_COL = "sampleWeightCol"
  private val COMPUTE_SAMPLE_WEIGHT_METHOD = "computeSampleWeightMethod"
  private val SAMPLE_SCOPE = "sampleScope"
  private val DATA_SRC = "dataSrc"
  private val DEBUG = "debug"
  private val SAMPLE_ZIP_NUM = "sampleZipNum"
  private val IS_PADDING = "isPadding"
  private val isRawNumTransform = "isRawNumTransform"
  private val GROUP_KEY = "groupKey"
  private val GROUP_QUEUE_LEN = "groupQueueLen"
  private val GROUP_RANK_INDICATOR = "groupRankIndicator"
  private val GROUP_PHASE = "groupPhase"
  private val GROUP_RANK_TOP_K = "groupRankTopK"
  private val META_MIN_BATCH = "minBatch"
  private val ACU_BUF_PERCENT = "accumulateBufPercent"
  private val IS_MODULE_IN_HASH = "isModuleInHash"
  private val IS_MODULE_IN_HASH_ENHANCED = "isModuleInHashEnhanced"
  private val HASH_ABS_FLAG = "hashAbsFlag"
  private val EXTERNAL_ONLY = "externalOnly"
  private val IS_CACHE = "isCache"
  private val PARAM_STORAGE_LEVEL = "storageLevel"
  private var param: TaskParameter = null
  private val TEST_SET_RATIO = "testSetRatio"
  private val FEATURE_PARA = "$features"
  private val IS_REPARTITION_OUTPUT_DATA = "isRepartitionOutputData"
  private val IS_SAVE_ONLINE_FEATURE_MAP = "isSaveOnlineFeatureMap"
  private val IS_FEATURE_CONCAT = "isFeatureConcat"
  private val RESULT_FILE_SIZE_MB = "resultFileSizeInMB"
  private val PARAM_COMPUTE_COLLISION = "doComputeCollision"
  private val PARAM_MATERIAL_SWITCH = "materialSwitch"
  private val savePathByColmunName = "dataSavePathByColumn"
  private val SAVE_PATH_CONFIG_BY_COLUMN = "savePathConfigByColumn"
  private val SAVE_BY_COLUMN = "saveByColumn"
  private val CONSISTENCY_CHECK_ENABLE = "consistencyCheckEnable"
  private val CONSISTENCY_CHECK_SAMPLE_NUM  = "consistencyCheckSampleNum"
  private val USE_COLUMN_EXPR_OPT = "useColumnExprOpt"
  private val DATA_SET_INFO = "dataSetInfo"
  private val STORAGE_MODE = "storageMode"
  private val IS_WRITE_SUCCESS_FILE = "isWriteSuccessFile"
  private val MULTIPART_UPLOAD_THRESHOLD = "multipartUploadThreshold"

  private val PARAM_EMPTY_PADDING = "emptyPadding"

  val SEQ_MAX_LENGTH = "seqMaxLength"
  val CXR = "cxr"

  val LATEST_FULL_DATA_TXT = "latestFullData.txt"
  val LATEST_INCREASE_DATA_TXT = "latestIncreaseData.txt"
  val INCREASE_DATA_BASE_VER_TXT = "increaseDataBaseVer.txt"

  // 长序列特征类型标识
  val LONG_SEQUENCE_TYPE = "LONG_SEQUENCE"

  // 长序列容器特征集合（featureValueType == "LONG_SEQUENCE"）
  var longSeqContainers = Array.empty[Feature]

  // 长序列特征名集合（容器 name + side_info 中的特征名），这些特征从普通特征列表中剔除
  var longSeqFeatureNames: Set[String] = Set.empty

  // 长序列特征集合（主序列 + 辅助序列），独立于普通特征
  var longSeqFeatures = Array.empty[Feature]

  // 长序列 autoHash 编码特征名
  var autoHashLongSeqFeatures = Array.empty[String]

  // 长序列 pureDense 编码特征名
  var pureDenseLongSeqFeatures = Array.empty[String]

  // 长序列 autoHash 特征的 hash bucket Map，独立于 hashEncodeMap
  var longSeqHashEncodeMap = mutable.Map[String, Int]().empty
  var modelJsonPath = "/opt/huawei/schedule-train/algorithm/featureConfig/modelFeatureSet.json"
  var date: String = _
  var isDebugMode: Boolean = _
  var partitionNum: Int = _
  var outputRepartitionNum: Int = _
  var trainSql: String = _
  var validSql: String = _
  var testSql: String = _
  var externalSql: String = _
  var minCount: Int = _
  var labelCol: Array[String] = _
  var maskRate: Double = _
  var hashBufPercent: Double = _
  var isModuleInHash: Boolean = _
  var isModuleInHashEnhanced: Boolean = _ // 增强版Hash32算法开关,默认不开启
  var hashAbsFlag: Boolean = _ // 是否使用绝对值hash取模,默认开启
  var accumulateBufPercent: Int = _
  var normType: String = _ // dense特征转换类型，包括standard标准归一化与minMax归一化
  var compress: Boolean = _ // 输出文件是否压缩
  var uniqueId: Array[String] = _ // 每条记录的唯一标识
  var saveFormat: String = _
  var sampleWeightCol: Array[String] = _ // 对样本使用时间加权，默认使用pt_d特征
  var sampleWeightMethod: String = _
  var sampleScope: String = _
  var dataSrc: Boolean = _
  var sampleZipNum: Int = _
  var isPadding: Boolean = _   // 对序列特征补全开关，默认开启
  var RawNumTransform: Boolean = _
  var groupParams: Map[String,String] = _ // 分组存储，[groupKey: 分组标识字段, groupQueueLen: 分组队列长度(不足补全，超出截断),
              // groupRankIndicator: 分组标记排序的字段] groupPhase: 分组适配阶段，train,valid,test，不填默认全选]
  var transform: Boolean = _ // 是否仅对验证集|测试集进行特征转换
  var warmupDate: String = _
  var previousDate: String = _
  var dataHdfsSavePath: String = _
  var dataLocalSavePath: String = _
  var modelTrainConfigSavePath: String = _
  var configSaveRootPath: String = _ // config文件保存根目录，用于保存各个map文件
  var onlineTransformJsonSavePath: String = _
  var metaMinBatch: Int = _ // meta feature transform
  var externalOnly: Boolean = _
  var isCache: Boolean = _ // 是否缓存数据集
  var storageLevel: String = _
  var isRepartitionOutputData: Boolean = _ // 输出表数据均衡
  var isDoubleHash:Boolean = _ // 哈希前置场景下，交叉特征需要与在线侧保持相同的二次hash的逻辑
  var doComputeCollision:Boolean = true //是否计算hash冲突率
  var materialSwitch: Boolean = false // 物料测特征开关
  var saveByColumn: String = _
  var savePathByColmun: String = _
  var savePathConfigByColumn: String = _
  var storageMode: String = _
  var isWriteSuccessFile: Boolean = _ // 是否写入_SUCCESS文件

  var resultFileSizeInMB:Int = _ // transform输出的文件的未压缩大小
  var multipartUploadThreshold: Int = 20 * 1024 * 1024 // NSP分段上传阈值，默认20MB

  var emptyPaddingMap:Map[String, String] = _ // 特征补空table, feature -> padding_key, 只针对大模型

  // ----------------------------   特征集编码 --------------------------------
  val HASH = "hash"
  val DENSE = "dense"
  val PURE_DENSE = "pureDense"
  val SEPARATE = "separate"
  val JOINT = "joint"
  val AUTO_HASH = "autoHash"
  val AUTO_HASHWEIGHTED = "autoHashWeighted"
  val ACU = "accumulate"
  val WEIGHTED_SEQ = "weighted"
  val RAW_ENCODE = "raw"

  // ---------------------- 自定义参数中对应编码 ----------------------------
  val HASH_ENCODE = "hashEncode"
  val DENSE_ENCODE = "dense"
  val PURE_DENSE_ENCODE = "pureDense"
  val SEPERATE_ENCODE = "seperateEncode"
  val SEPARATE_ENCODE = "separateEncode"
  val JOINT_ENCODE = "jointEncode"
  val AUTO_HASH_ENCODE = "autoHash"
  val AUTO_HASHWEIGHTED_ENCODE = "autoHashWeighted"
  val ACU_ENCODE = "accumulate"
  val WEIGHTED_SEPARATE_ENCODE = "weightedSeparateEncode"
  val RAW_SEP_ENCODE = "rawEncode"
  var OBS_ROOT = "obsRoot"


  // ----------------------------   特征编码对应特征字段集 --------------------------------
  var pureDenseFeatures = Array.empty[String]
  var denseFeatures = Array.empty[String]
  var separateEncodeFeatures = Array.empty[String]
  var jointEncodeFeatures = Map.empty[String, Array[String]]
  var autoHashFeatures = Array.empty[String]
  var autoHashWeightedFeatures = Array.empty[String]
  var accumulateFeatures = Array.empty[String]
  var weightedSeqFeatures = Array.empty[String]
  var rawEncodeFeatures = Array.empty[String]
  var hashEncodeMap = mutable.Map[String, Int]().empty
  var testSetRatio: Int = 10
  var obsRoot:String = ""

  val encodeMapping = Map(HASH -> HASH_ENCODE, DENSE -> DENSE_ENCODE, AUTO_HASHWEIGHTED -> AUTO_HASHWEIGHTED_ENCODE,
   PURE_DENSE -> PURE_DENSE_ENCODE, SEPARATE -> SEPARATE_ENCODE, JOINT -> JOINT_ENCODE,
   AUTO_HASH -> AUTO_HASH_ENCODE, ACU -> ACU_ENCODE, WEIGHTED_SEQ -> WEIGHTED_SEPARATE_ENCODE, RAW_ENCODE -> RAW_SEP_ENCODE)

  // ----------------------------- 在线任务中保存onlineFeatureMap任务是否开启
  var isSaveOnlineFeatureMap:Boolean = _

  // ----------------------------- 原子化操作，后续可以独立到Operator库中
  var isFeatureConcat: Boolean = _
  var sparseFeatureCols = Array.empty[String]
  var denseFeatureCols = Array.empty[String]

  // ----------------------------- 一致性校验参数----
  var consistencyCheckSampleNum: Int = 100
  var consistencyCheckEnable:Boolean = true

  // AR1000235D: Column 表达式优化开关，默认 false 走老代码，true 走优化路径
  var useColumnExprOpt: Boolean = false

  def init(param: TaskParameter): Unit = {
   info(s"init param start !!!")
   this.param = param
   this.modelJsonPath = param.getString(MODEL_JSON_PATH, Some(modelJsonPath))
   this.date = param.getString(DATE)
   this.partitionNum = param.getInt(REPARTITION_NUM, Some(-1))
   this.outputRepartitionNum = param.getInt(OUTPUT_PARTITION_NUM, Some(-1))
   this.trainSql = param.getStringV2(TRAIN_SQL, Some(null))
   this.validSql = param.getStringV2(VALID_SQL, Some(null))
   this.testSql = param.getStringV2(TEST_SQL, Some(null))
   this.externalSql = param.getRawStringOrNull(EXTERNAL_SQL)
   this.minCount = param.getInt(MIN_COUNT, Some(-1))
   this.labelCol = param.getStringValues(LABEL, Some(Array("")), ",")
   this.maskRate = param.getDouble(MASK_RATE, Some(0.0))
   this.hashBufPercent = param.getDouble(HASH_BUF_PERCENT, Some(0.0))
   this.isModuleInHash = param.getBoolean(IS_MODULE_IN_HASH, Some(true))
   this.isModuleInHashEnhanced = param.getBoolean(IS_MODULE_IN_HASH_ENHANCED, Some(false))
   this.hashAbsFlag = param.getBoolean(HASH_ABS_FLAG, Some(true))
   this.accumulateBufPercent = param.getInt(ACU_BUF_PERCENT, Some(3))
   this.normType = param.getString(NORM_TYPE, Some("standard"))
   this.uniqueId = param.getStringValues(UNIQUE_ID, Some(Array("log_id")), ",")
   this.saveFormat = param.getString(SAVE_FORMAT, Some("tfrecord"))
   this.compress =  if (saveFormat == "csv") param.getBoolean(COMPRESS, Some(false)) else param.getBoolean(COMPRESS, Some(true))
   this.sampleWeightCol = param.getStringValues(SAMPLE_WEIGHT_COL, Some(Array("")), ",")
   this.sampleWeightMethod = param.getString(COMPUTE_SAMPLE_WEIGHT_METHOD, Some(""))
   this.sampleScope = param.getString(SAMPLE_SCOPE, Some(""))
   this.dataSrc = param.getBoolean(DATA_SRC, Some(false))
   this.sampleZipNum = param.getInt(SAMPLE_ZIP_NUM, Some(1))
   this.isPadding = param.getBoolean(IS_PADDING, Some(true))
   this.RawNumTransform = param.getBoolean(isRawNumTransform, Some(false))
   this.isWriteSuccessFile = param.getBoolean(IS_WRITE_SUCCESS_FILE, Some(true))
   this.groupParams = Map(
    "groupKey" -> param.getString(GROUP_KEY, Some("")),
    "groupQueueLen" -> param.getString(GROUP_QUEUE_LEN, Some("1")),
    "groupRankIndicator" -> param.getString(GROUP_RANK_INDICATOR, Some("")),
     "groupPhase" -> param.getString(GROUP_PHASE, Some("")),
     "groupRankTopK" -> param.getString(GROUP_RANK_TOP_K, Some("0"))
   )
   this

   this.isDebugMode = param.getBoolean(DEBUG, Some(false))
   this.transform = param.getBoolean(TRANSFORM, Some(false))
   this.dataHdfsSavePath = param.getString(DATA_SAVE_PATH)
   this.dataLocalSavePath = param.getString(DATA_LOCAL_PATH, Some(dataHdfsSavePath))
   this.modelTrainConfigSavePath = param.getString(MODEL_TRAIN_SAVE_PATH)
   this.configSaveRootPath = modelTrainConfigSavePath.split("\\/").dropRight(1).mkString("/")
   this.onlineTransformJsonSavePath = param.getString(ONLINE_TRANSFORM_JSON_SAVE_PATH,Some(""))
   this.metaMinBatch = param.getInt(META_MIN_BATCH, Some(0))
   this.externalOnly = param.getBoolean(EXTERNAL_ONLY, Some(false))
   this.isCache = param.getBoolean(IS_CACHE, Some(true))
   this.storageLevel = param.getString(PARAM_STORAGE_LEVEL, Some("MEMORY_AND_DISK_SER"))
   this.testSetRatio = param.getInt(TEST_SET_RATIO, Some(10))
   this.isRepartitionOutputData = param.getBoolean(IS_REPARTITION_OUTPUT_DATA, Some(false))
   this.isSaveOnlineFeatureMap = param.getBoolean(IS_SAVE_ONLINE_FEATURE_MAP, Some(false))
   this.isFeatureConcat = param.getBoolean(IS_FEATURE_CONCAT, Some(false))
   this.resultFileSizeInMB = param.getInt(RESULT_FILE_SIZE_MB, Some(-1))
   this.emptyPaddingMap = param.getAsStringMap(PARAM_EMPTY_PADDING, Some(Map.empty))
   this.warmupDate = param.getString(WARMUP_DATE,Some(""))
   this.previousDate = param.getString(PREVIOUS_DATE,Some(""))
   this.doComputeCollision = param.getBoolean(PARAM_COMPUTE_COLLISION, Some(true))
   this.materialSwitch = param.getBoolean(PARAM_MATERIAL_SWITCH,Some(false))
   this.savePathByColmun = param.getString(savePathByColmunName, Some(Strings.EMPTY))
   this.saveByColumn = param.getString(SAVE_BY_COLUMN, Some(Strings.EMPTY))
   this.savePathConfigByColumn = param.getString(SAVE_PATH_CONFIG_BY_COLUMN, Some(Strings.EMPTY))
   this.consistencyCheckEnable = param.getBoolean(CONSISTENCY_CHECK_ENABLE, Some(false))
   this.consistencyCheckSampleNum = param.getInt(CONSISTENCY_CHECK_SAMPLE_NUM, Some(10))
   this.useColumnExprOpt = param.getBoolean(USE_COLUMN_EXPR_OPT, Some(false))
   this.storageMode = param.getString(STORAGE_MODE, Some(""))
   this.obsRoot = param.getString(OBS_ROOT, Some(""))
   this.multipartUploadThreshold = param.getInt(MULTIPART_UPLOAD_THRESHOLD, Some(20 * 1024 * 1024))


   TransformConfigModule.parseConfigJson(normType, modelJsonPath, groupParams)
   checkCrossFeatures4DoubleHash()
   replaceSqlFeatures()
   identifyLongSeqFeatures()
   initEncodeFeatures()
   initLongSeqHashEncodeMap()
   initSeqMaxLength()
   initDatasetInfo()
   checkDuplicateFeature()
   info(s"init param end !!!")
  }

  private def initDatasetInfo() = {
   val path = "dataSet.json"
   try {
    val jsonStr = Utils.loadJsonToString(path)
    require(jsonStr.nonEmpty, s"dataSet.json $path is empty")
    datasetInfo = parseDatasetInfo(jsonStr)
    info(s"datasetInfoMap = $datasetInfo")
   } catch {
    case e: Exception =>
     info(s"Failed to load dataset.json from path: $path, error: ${e.getMessage}, using default datasetInfo")
     datasetInfo = new DataSetEntity()
   }
  }

  def consistencyRepalceFeatures(tranFeatures: String, testSql: String, param:TaskParameter): String = {
   // 如果执行一致性校验逻辑，需要确保sql中查询出宽表pt_h或pt_d和主键,将其加入$features中

   val offlinePrimaryKey = param.getString("offlinePrimaryKey", Some("empty"))
   //将 tranFeatures 转换为一个可变的 Set
   var featuresSet = if (StringUtils.isBlank(tranFeatures)) {
    scala.collection.mutable.Set[String]()
   } else {
    tranFeatures.split(',').map(_.trim).filter(_.nonEmpty).to[scala.collection.mutable.Set]
   }
   // 判断 offlinePrimaryKey 是否需要加入
   if (offlinePrimaryKey != "empty" && offlinePrimaryKey != "" &&
    !featuresSet.contains(offlinePrimaryKey) && !testSql.contains(offlinePrimaryKey)) {
    featuresSet.add(offlinePrimaryKey)
    info(s"Added offlinePrimaryKey '$offlinePrimaryKey' to features.")
   }

   // 处理额外的列名
   val offlineExtraColumnNames = param.getString("offlineExtraColumnNames", Some("empty")) // 获取参数，默认值为空字符串
   if (offlineExtraColumnNames != "" && offlineExtraColumnNames != "empty") {
    val extraCols = offlineExtraColumnNames.split(',').map(_.trim).filter(_.nonEmpty)
    val columnsToAdd = extraCols.filter(col => !featuresSet.contains(col) && !testSql.contains(col))
    if (columnsToAdd.nonEmpty) {
     featuresSet ++= columnsToAdd
     info(s"Added extra columns from offlineExtraColumnNames: '${columnsToAdd.mkString(", ")}' to features.")
    }
   }

   // 3. 检查所有 SQL 语句中是否包含 "pt_h" 或 "pt_d"，并将其加入 featuresSet
   val partitionKeysFound = if (testSql.contains("pt_h")) {"pt_h"} else { "pt_d"}
   if (!featuresSet.contains(partitionKeysFound)) {
    featuresSet.add(partitionKeysFound)
    info(s"Added partition key '$partitionKeysFound' (found in SQL) to features.")
   }
   // 4. 将更新后的 Set 转换回逗号分隔的字符串
   val tranFeaturesNew = featuresSet.mkString(",")
   info(s"Updated tranFeatures: $tranFeatures")
   tranFeaturesNew
  }

  // 替换sql中的$features
    private def replaceSqlFeatures(): Unit = {
        info("replaceSqlFeatures start!!!")
    //若开启一致性校验，确保sql中查询出宽表pt_h或pt_d和主键,将其加入$features中
    if (param.getBoolean("consistencyCheck", Some(false))) {
     tranFeatures = consistencyRepalceFeatures(tranFeatures, testSql, param)
    }
   // 替换sql
   if (StringUtils.isNoneBlank(trainSql)) {
    this.trainSql = trainSql.replace(FEATURE_PARA, tranFeatures)
   }
   if (StringUtils.isNoneBlank(validSql)) {
    this.validSql = validSql.replace(FEATURE_PARA, tranFeatures)
   }
   if (StringUtils.isNoneBlank(testSql)) {
    this.testSql = testSql.replace(FEATURE_PARA, tranFeatures)
   }
        if (StringUtils.isNoneBlank(externalSql)) {
            this.externalSql = externalSql.replace(FEATURE_PARA, tranFeatures)
        }
    }

  private def initEncodeFeatures(): Unit = {
   // 定义转换的特征
   pureDenseFeatures = getFeatureCombinations(PURE_DENSE)
   denseFeatures = getFeatureCombinations(DENSE)
   separateEncodeFeatures = getFeatureCombinations(SEPARATE)
   jointEncodeFeatures = getJointFeatures()
   autoHashFeatures = getFeatureCombinations(AUTO_HASH)
   autoHashWeightedFeatures = getFeatureCombinations(AUTO_HASHWEIGHTED)
   accumulateFeatures = getFeatureCombinations(ACU)
   weightedSeqFeatures = getFeatureCombinations(WEIGHTED_SEQ)
   rawEncodeFeatures = getFeatureCombinations(RAW_ENCODE)
   initHashEncodeMap(hashEncodeMap)

   // 将离散特征与连续特征分类，便于后续归并特征域
   sparseFeatureCols = sparseFeatureCols.++(autoHashFeatures).++(autoHashWeightedFeatures).++(separateEncodeFeatures)
   for (jointFeat <- jointEncodeFeatures) sparseFeatureCols = sparseFeatureCols.++(jointFeat._2)
   // 针对交叉特征,需要讲交叉分隔符进行替换
   sparseFeatureCols = sparseFeatureCols.map { featName =>
    if(featName.contains(Const.CONFIG_CROSS_SEP_CHAR)){
     featName.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR)
    }else{
     featName
    }
   }
   denseFeatureCols = denseFeatureCols.++(denseFeatures).++(pureDenseFeatures)
  }

  private def checkDuplicateFeature() = {
   info("checkDuplicateFeature start!!!")
   val duplicateFeatures = (pureDenseFeatures ++ denseFeatures ++ separateEncodeFeatures
    ++ jointEncodeFeatures.flatMap(_._2.iterator) ++
    accumulateFeatures ++ weightedSeqFeatures ++ hashEncodeMap.keys)
    .map((_, 1)).groupBy(_._1).map(f => (f._1, f._2.map(_._2).sum)).filter(_._2 > 1)
   if (duplicateFeatures.nonEmpty) {
    throw new IllegalArgumentException(s"found more then one feature named ${duplicateFeatures.keySet.mkString(",")}, please check")
   }
   info("checkDuplicateFeature end!!!")
  }

  private def initSeqMaxLength() = {
   val seqMaxLength = param.getString(SEQ_MAX_LENGTH, Some(""))
   // 从配置文件中初始化自定义的特征长度与hash特征 transform json配置的优先级 > 特征的拓展属性
   if (StringUtils.isNoneBlank(seqMaxLength)) {
    // 去除不在模型特征集中的特征
    val filterSparseTypeMap = parseJsonToMap(seqMaxLength).filter(f => sparseTypeFeatures.contains(f._1))
    seqMaxLenMap = seqMaxLenMap ++ filterSparseTypeMap
   }
   info(s"seqMaxLenMap = $seqMaxLenMap")
  }

  /**
   * 长序列特征识别：只依赖 features，在 initEncodeFeatures 之前调用
   * 识别 longSeqContainers / longSeqFeatureNames / longSeqFeatures / autoHashLongSeqFeatures / pureDenseLongSeqFeatures
   */
  private def identifyLongSeqFeatures(): Unit = {
   // 1. 识别长序列容器
   longSeqContainers = features.filter(_.featureValueType == LONG_SEQUENCE_TYPE)
   if (longSeqContainers.isEmpty) {
    info("no long sequence features found")
    return
   }
   info(s"longSeqContainers size = ${longSeqContainers.length}, names = ${longSeqContainers.map(_.name).mkString(",")}")

   // 2. 收集需要剔除的特征名（容器 name + side_info 中的特征名）
   longSeqFeatureNames = longSeqContainers.flatMap { container =>
    val mainSeqName = container.name
    val sideInfoNames = if (StringUtils.isNotBlank(container.side_info)) {
     container.side_info.split(",").map(_.trim).filter(_.nonEmpty)
    } else {
     Array.empty[String]
    }
    sideInfoNames :+ mainSeqName
   }.toSet
   info(s"longSeqFeatureNames = ${longSeqFeatureNames.mkString(",")}")

   // 3. 长序列特征集合（在 features 中存在且名字在 longSeqFeatureNames 中）
   longSeqFeatures = features.filter(f => longSeqFeatureNames.contains(f.name))

   // 4. 编码归类：长序列特征按编码分类
   autoHashLongSeqFeatures = longSeqFeatures.filter(_.encode == AUTO_HASH).map(_.name)
   pureDenseLongSeqFeatures = longSeqFeatures.filter(_.encode == PURE_DENSE).map(_.name)
   info(s"autoHashLongSeqFeatures = ${autoHashLongSeqFeatures.mkString(",")}, pureDenseLongSeqFeatures = ${pureDenseLongSeqFeatures.mkString(",")}")
  }

  /**
   * 长序列 autoHash 特征的 hash bucket 初始化，与 initHashEncodeMap 对称
   * 只处理长序 autoHash 特征，写入独立的 longSeqHashEncodeMap
   */
  private def initLongSeqHashEncodeMap(): Unit = {
   if (longSeqFeatures.isEmpty) return
   longSeqFeatures.filter(_.encode == AUTO_HASH).foreach(feature => {
    if (isModuleInHash && feature.isModuleInHash) {
     longSeqHashEncodeMap += feature.name -> feature.hashBucket
    } else {
     longSeqHashEncodeMap += feature.name -> 1
    }
   })
   info(s"longSeqHashEncodeMap = ${longSeqHashEncodeMap.mkString(",")}")
  }

  // 去除parseModelJson后 过滤掉$features
  private def getFeatureCombinations(encode: String): Array[String] = {
   val definedFeatures = getDefinedEncodeFeatures(encodeMapping(encode)).filter(!_.equals(FEATURE_PARA))
   val features = (getFeaturesByEncode(encode) ++ definedFeatures).distinct
    .filterNot(longSeqFeatureNames.contains)  // 从源头过滤长序特征
   // encodeMap中一开始只存放特征集里的特征，这里给所有json配置里的编码都重新加到encodeMap里
   encodeMap = encodeMap + (encode -> (encodeMap.getOrElse(encode,Array.empty[Feature])
    ++ definedFeatures.map(name => Feature(name,encode))))
   info(s"encode ${encode} features size = ${features.length}  value = ${features.mkString(",")}")
   features
  }


  private def getDefinedEncodeFeatures(encode: String) = {
   if (encode.equals(SEPARATE_ENCODE) && (param.isDefined(SEPARATE_ENCODE) || param.isDefined(SEPERATE_ENCODE))) {
    param.getString(SEPARATE_ENCODE, Some(param.getString(SEPERATE_ENCODE, Some("")))).split(",")
     .filter(StringUtils.isNoneBlank(_)).map(_.trim)
   } else if (param.isDefined(encode)) {
    param.getString(encode).split(",").filter(StringUtils.isNoneBlank(_)).map(_.trim)
   } else {
    Array.empty[String]
   }
  }

  private def getJointFeatures(): Map[String, Array[String]] = {
   var features = if (encodeMap.contains(JOINT)) encodeMap(JOINT)
    .map(feature => JointFeatureInfo(feature.jointPrefix, feature.name)).groupBy(_.jointPrefix).mapValues(_.map(_.featureName)) else Map.empty[String, Array[String]]
   if (param.isDefined(encodeMapping(JOINT))) {
    param.getStringValues(encodeMapping(JOINT), Some(Array.empty[String]), ",")
                .filter(!_.equals(FEATURE_PARA))
                .foreach(feature => {
                    val key = feature.split(":")(0).trim
                    val value = feature.split(":")(1).split(Const.CONFIG_CROSS_SEP_CHAR).map(_.trim)
                    features += key -> value
                })
   }
   info(s"encode joint features size = ${features.size}  value = ${features.map(f => f._1 + ":" + f._2.mkString("#")).mkString(",")}")
   features
  }

  private def initHashEncodeMap(hashMap: mutable.Map[String, Int]): Unit = {
   encodeMap.filter(entry => {
    HASH.equals(entry._1) || AUTO_HASH.equals(entry._1) || AUTO_HASHWEIGHTED.equals(entry._1)
   }).flatMap(_._2).foreach(feature => {
    // 长序特征不加入 hashEncodeMap，使用独立的 longSeqHashEncodeMap
    if (longSeqFeatureNames.contains(feature.name)) {
     return
    }
    if(isModuleInHash && feature.isModuleInHash){
     hashMap += feature.name -> feature.hashBucket
    }else{
     hashMap += feature.name -> 1
    }
   })
   if (param.isDefined(encodeMapping(HASH))) {
    hashMap ++= parseJsonToMap(param.getString(HASH_ENCODE).replace(FEATURE_PARA, "")).map(feature =>
     feature._1.replace(Const.CONFIG_CROSS_SEP_CHAR, Const.ACTUAL_SEP_CHAR) -> feature._2)
   }
   info(s"encode hash features size = ${hashMap.size}  value = ${hashMap.mkString(",")}")
  }

  private def checkCrossFeatures4DoubleHash(): Unit = {
   this.isDoubleHash = TransformConfigModule.checkCrossFeature4DoubleHash()
  }

  def checkInputParam(param: TaskParameter): Unit = {
   val needParam = Array(DATE, SAVE_FORMAT, UNIQUE_ID, DATA_SAVE_PATH, DATA_LOCAL_PATH, ONLINE_TRANSFORM_JSON_SAVE_PATH, MODEL_TRAIN_SAVE_PATH)
   for (in <- needParam) {
    if (!param.isDefined(in)) throw new IllegalArgumentException(s"the input:${in} not in the config json file,please check")
   }
  }

  def setCompressMode(spark: SparkSession, param: TaskParameter): Unit = {
   if (param.getString(SAVE_FORMAT) == "tfrecord" && ((param.isDefined(COMPRESS) && param.getBoolean(COMPRESS)) || !param.isDefined(COMPRESS))) {
    spark.conf.set("spark.hadoop.mapreduce.output.fileoutputformat.compress", true)
    spark.conf.set("spark.hadoop.mapreduce.output.fileoutputformat.compress.codec",
     "org.apache.hadoop.io.compress.GzipCodec")
    val sc = spark.sparkContext
    sc.hadoopConfiguration.set("mapreduce.output.fileoutputformat.compress", "true")
    sc.hadoopConfiguration.set("mapreduce.output.fileoutputformat.compress.codec",
     "org.apache.hadoop.io.compress.GzipCodec")
    sc.hadoopConfiguration.set("mapreduce.output.fileoutputformat.compress.type", "BLOCK")
   }
  }

  def parseJsonToMap(json: String): mutable.Map[String, Int] = {
   if (StringUtils.isBlank(json)) return mutable.Map.empty[String,Int]
   val mapper = new ObjectMapper()
   val node = mapper.readValue(json, classOf[util.HashMap[String, Int]])
   JavaConversions.mapAsScalaMap(node)
  }



  def parseDatasetInfo(json: String): DataSetEntity = {
   // 1. 检查 JSON 是否为空
   if (StringUtils.isBlank(json)) {
    // 如果是空字符串，返回一个默认的 DataSetEntity 实例
    return new DataSetEntity() // 假设你有一个无参构造函数或默认值
   }
   // 2. 创建 ObjectMapper（建议只创建一次，但这里暂且每次创建）
   val mapper = new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
   mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
   try {
    // 3. 解析 JSON 到 DataSetEntity
    val node = mapper.readValue(json, classOf[DataSetEntity])
    node // 返回解析结果
   } catch {
    case e: Exception =>
     // 4. 如果解析失败，返回一个默认的 DataSetEntity 实例
     LogUtil.infoLog(LOGGER, s"dataset 解析失败: $json")
     new DataSetEntity() // 或者你定义的默认实例
   }
  }


  def info(msg: String): Unit = {
   LogUtil.infoLog(LOGGER, msg)
  }
}
```