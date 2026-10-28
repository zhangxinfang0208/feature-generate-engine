class DagPreprocess(taskMeta: TaskMetaData)
    extends RcmTaskBatch(taskMeta) with AutoCloseable {

  private val LOGGER = LoggerFactory.getLogger(this.getClass)
  private val ownedBroadcasts = mutable.ArrayBuffer.empty[Broadcast[_]]
  private val ownedOutputs = new util.ArrayList[DataFrame]()
  private var activeDag = false

  def hasActiveDag: Boolean = activeDag

  override def getTaskDesc: String =
    "DAG 引擎预处理：基于模型特征集 JSON 计算衍生特征，追加为新列"

  override def getTaskApiType: TaskApiType = TaskApiType.FEATURE_ENGINEERING

  override def execute(
      spark: SparkSession,
      input: mutable.Map[String, DataFrame]
  ): mutable.Map[String, DataFrame] = execute(spark, input, Set.empty[String])

  /** arrayOutputNames 是下游要求 ArrayType 的最终输出列名。 */
  def execute(
      spark: SparkSession,
      input: mutable.Map[String, DataFrame],
      arrayOutputNames: Set[String]
  ): mutable.Map[String, DataFrame] = {
    import DagPreprocess._
    activeDag = false
    val parameter = taskMeta.parameter
    val path = parameter.getString("modelJsonPath", Some(MODEL_FEATURE_SET))
    val planId = parameter.getString("dagPlanId", Some("offline-dag-preprocess"))
    val seqSep = textOrDefault(parameter.getString("sequenceSep", Some("^")), "^")
    val configuredBatchSize = parameter.getInt("batchSize", Some(1000))
    val batchSize = if (configuredBatchSize > 0) configuredBatchSize else 1000
    // 默认兼容原来的 trim + filter；位置对齐序列应离在线同时启用 preserve。
    val preserveEmptyTokens = parameter.getBoolean("dagPreserveEmptyTokens", Some(false))
    val splitSingletonString = parameter.getBoolean("dagSplitSingletonString", Some(true))
    val strictOutput = parameter.getBoolean("dagStrictOutput", Some(false))
    val resultKeyMode = textOrDefault(
      parameter.getString("dagResultKeyMode", Some("storeName")), "storeName")
    require(Set("storeName", "name").contains(resultKeyMode),
      "dagResultKeyMode must be storeName or name")

    val configJson = Utils.loadFile(path)
    require(configJson != null && configJson.trim.nonEmpty,
      s"model feature set json is empty: $path")
    // 只解析一次 JSON 树。结构错误必须报错，不能作为无 DERIVED 透传。
    val root = new ObjectMapper().readTree(configJson)
    require(root != null && root.isObject && root.path("features").isArray,
      s"model feature set must contain a features array: $path")
    val nodes = root.path("features").elements().asScala.toVector
    require(nodes.forall(n => n != null && n.isObject), "features entries must be objects")
    if (!nodes.exists(n => enumValue(nodeText(n, "definition_type"), "BASE") == "DERIVED")) {
      info("No DERIVED features, skip DAG preprocess")
      return input
    }

    val config = FeatureConfigLoader.load(configJson)
    val features = config.features().asScala.toVector
    val issues = validateDerivedFeatures(config)
    require(issues.isEmpty, "DAG config validation failed:\n" +
      issues.zipWithIndex.map { case (msg, index) => s"  ${index + 1}. $msg" }.mkString("\n"))

    val outputs = features.filter { f =>
      enabled(f) && isDerived(f) && enumValue(f.outputPolicy(), "OUTPUT") == "OUTPUT"
    }.map { f =>
      val name = textOrDefault(f.name(), "")
      val columnName = textOrDefault(f.storeName(), name)
      val shape = enumValue(f.valueShape(), "SCALAR")
      val featureType = enumValue(f.`type`(), "STRING")
      val arrayOutput = arrayOutputNames.contains(columnName)
      require(!arrayOutput || shape != "SCALAR",
        s"Array output '$columnName' must have SEQUENCE or VECTOR shape")
      // 字符串输出保持旧默认值行为；数值数组默认值必须可转换为对应数值。
      val configuredDefault = Option(f.defaultValue()).map(_.toString.trim).filter(_.nonEmpty)
      val dft = configuredDefault.getOrElse(
        if (arrayOutput && isNumeric(featureType)) "0" else "missing")
      if (arrayOutput) convertValue(dft, featureType)
      OutputSpec(if (resultKeyMode == "name") name else columnName,
        columnName, shape, featureType, dft, arrayOutput)
    }.toArray
    if (outputs.isEmpty) {
      info("No enabled DERIVED OUTPUT features, skip DAG preprocess")
      return input
    }
    requireUnique(outputs.map(_.columnName).toSeq, "DAG output column")

    // 严格兼容模式默认 NONE，保持原有跨 action 重算行为。
    // 仅当上游输入和 DAG 均确定、无副作用时，显式启用缓存复用结果。
    // 不额外 count 预热；首个下游 action 按需物化。丢块或任务重试仍可能重算。
    val outputStorageLevel = OfflineSparkSupport.dagStorageLevel(
      parameter.getString("dagOutputStorageLevel", Some("NONE")))

    val driverEngine = FeatureDagEngine.init(configJson, InitOptions.offline(planId))
    val requiredNames = try {
      driverEngine.requiredInputNames().asScala.toSet
    } finally {
      closeEngine(driverEngine.asInstanceOf[AnyRef])
    }

    val sepByName = nodes.filter { n =>
      enumValue(nodeText(n, "definition_type"), "BASE") == "BASE"
    }.map { n =>
      val name = textOrDefault(nodeText(n, "name"), "")
      val sep = Option(nodeText(n, "list_separator")).map(_.trim).filter(_.nonEmpty)
        .orElse(Option(nodeText(n, "sequence_sep")).map(_.trim).filter(_.nonEmpty))
        .getOrElse(seqSep)
      // 兼容已知旧格式 \^；其他反斜杠保留为字面量，不做全局删除。
      name -> (if (sep == "\\^") "^" else sep)
    }.toMap

    val sources = features.filter { f =>
      enabled(f) && enumValue(f.definitionType(), "BASE") == "BASE" &&
        requiredNames.contains(textOrDefault(f.name(), ""))
    }.map { f =>
      val name = textOrDefault(f.name(), "")
      val inferredShape = if (Option(f.sequenceMaxLength()).exists(_.intValue() > 1)
        ) "SEQUENCE" else "SCALAR"
      val shape = enumValue(f.valueShape(), inferredShape)
      require(Set("SCALAR", "SEQUENCE", "VECTOR").contains(shape), s"Invalid shape for '$name': $shape")
      val featureType = enumValue(f.`type`(), "STRING")
      val dft = Option(f.defaultValue()).map(_.toString.trim).filter(_.nonEmpty)
        .getOrElse(if (isNumeric(featureType)) "0" else "missing")
      convertValue(dft, featureType) // Driver 预校验，避免每个 task 重试同一坏配置。
      SourceSpec(name, shape, featureType, sepByName.getOrElse(name, seqSep), dft)
    }.toArray
    val unmapped = requiredNames -- sources.map(_.name).toSet
    require(unmapped.isEmpty, s"Required DAG inputs have no enabled BASE binding: ${unmapped.toSeq.sorted.mkString(",")}")

    val spec = DagExecutionSpec(configJson, planId, seqSep, batchSize,
      preserveEmptyTokens, splitSingletonString, strictOutput, sources, outputs)
    val bcSpec = spark.sparkContext.broadcast(spec)
    ownedBroadcasts += bcSpec
    val result = mutable.LinkedHashMap.empty[String, DataFrame]
    input.foreach { case (key, df) =>
      require(df != null, s"DAG input DataFrame '$key' is null")
      val output = applyDagEngine(spark, df, key, bcSpec)
      result.put(key, OfflineSparkSupport.persistOwned(output, outputStorageLevel, ownedOutputs))
    }
    activeDag = true
    info(s"DAG prepared: planId=$planId, sources=${sources.length}, outputs=${outputs.length}, " +
      s"batchSize=$batchSize, outputStorageLevel=$outputStorageLevel")
    result
  }

  private def applyDagEngine(
      spark: SparkSession,
      df: DataFrame,
      dataKey: String,
      bcSpec: Broadcast[DagPreprocess.DagExecutionSpec]
  ): DataFrame = {
    import DagPreprocess._
    val spec = bcSpec.value
    val caseSensitive = spark.conf.get("spark.sql.caseSensitive", "false").toBoolean
    def canonical(name: String): String = if (caseSensitive) name else name.toLowerCase(Locale.ROOT)
    requireUnique(df.schema.fieldNames.map(canonical).toSeq, s"$dataKey input column")
    requireUnique(spec.outputs.map(o => canonical(o.columnName)).toSeq, s"$dataKey output column")
    val existing = df.schema.fieldNames.map(canonical).toSet
    val conflicts = spec.outputs.map(_.columnName).filter(n => existing.contains(canonical(n)))
    require(conflicts.isEmpty, s"DAG output columns already exist in '$dataKey': ${conflicts.mkString(",")}")
    val indexByName = df.schema.fieldNames.zipWithIndex.map { case (n, i) => canonical(n) -> i }.toMap
    val indices = spec.sources.map(s => indexByName.getOrElse(canonical(s.name), -1))
    spec.sources.zip(indices).filter(_._2 < 0).foreach { case (s, _) =>
      info(s"Source '${s.name}' missing in '$dataKey'; apply null/default binding")
    }
    val schema = StructType(df.schema.fields ++ spec.outputs.map { o =>
      val dt: DataType = if (o.arrayOutput) ArrayType(elementType(o.featureType), containsNull = false)
        else StringType
      StructField(o.columnName, dt, nullable = true)
    })
    // 闭包只引用小型索引数组、数据集标识及 Broadcast；执行方法全部位于 object。
    val resultRDD = df.rdd.mapPartitions { iter =>
      DagPreprocess.runPartition(iter, bcSpec.value, indices, dataKey)
    }
    spark.createDataFrame(resultRDD, schema)
  }

  override def close(): Unit = {
    // 调用者必须在所有下游 action 结束后 close；先释放本类缓存，再销毁其血缘依赖的广播。
    try OfflineSparkSupport.releaseOwned(ownedOutputs)
    catch { case NonFatal(e) => LOGGER.warn("Failed to release DAG output caches", e) }
    ownedBroadcasts.reverseIterator.foreach { bc =>
      try bc.destroy()
      catch { case NonFatal(e) => LOGGER.warn("Failed to destroy DAG broadcast", e) }
    }
    ownedBroadcasts.clear()
    activeDag = false
  }

  private def info(msg: String): Unit = LogUtil.infoLog(LOGGER, msg)

  private def validateDerivedFeatures(config: FeatureSetConfig): List[String] = {
    import DagPreprocess._
    val issues = mutable.ArrayBuffer.empty[String]
    val features = config.features().asScala.toVector
    features.groupBy(f => textOrDefault(f.name(), "")).foreach { case (name, values) =>
      if (name.isEmpty) issues += "Feature name must not be blank"
      else if (values.size > 1) issues += s"Duplicate feature name '$name'"
    }
    val featureEnabled = features.map(f => textOrDefault(f.name(), "") -> enabled(f)).toMap
    val operators = OperatorRegistry.standard()
    val parser = new ExpressionParser()
    features.filter(enabled).foreach { feature =>
      val name = textOrDefault(feature.name(), "<unnamed>")
      val definition = enumValue(feature.definitionType(), "BASE")
      if (!Set("BASE", "DERIVED").contains(definition)) {
        issues += s"[$name] invalid definition_type '$definition'"
      } else if (definition == "BASE") {
        if (Option(feature.expression()).exists(_.trim.nonEmpty))
          issues += s"[$name] expression must be blank for BASE"
      } else {
        if (Option(feature.`type`()).forall(_.trim.isEmpty)) issues += s"[$name] type is required"
        val shape = enumValue(feature.valueShape(), "SCALAR")
        if (!Set("SCALAR", "SEQUENCE", "VECTOR").contains(shape)) issues += s"[$name] invalid value_shape '$shape'"
        if (Option(feature.valueShape()).forall(_.trim.isEmpty))
          LOGGER.warn(s"[$name] value_shape missing; adapter defaults to SCALAR")
        val scopes = Option(feature.entityScopes()).map(_.asScala.toSeq).getOrElse(Seq.empty)
        if (scopes.isEmpty) LOGGER.warn(s"[$name] entity_scopes missing; engine validation applies")
        scopes.foreach { scope =>
          if (!Set("USER", "SCENE", "ITEM").contains(enumValue(scope, "")))
            issues += s"[$name] invalid entity_scope '$scope'"
        }
        Option(feature.expression()).map(_.trim).filter(_.nonEmpty) match {
          case None => issues += s"[$name] expression is required"
          case Some(expression) =>
            try {
              val calls = mutable.ArrayBuffer.empty[AstCall]
              val refs = mutable.LinkedHashSet.empty[String]
              collectAst(parser.parse(expression), calls, refs)
              calls.foreach { call =>
                val op = operators.find(call.functionName())
                if (!op.isPresent) issues += s"[$name] unknown operator '${call.functionName()}'"
                else {
                  val d = op.get()
                  val n = call.arguments().size()
                  if (n < d.minArguments() || n > d.maxArguments())
                    issues += s"[$name] operator '${call.functionName()}' expects ${d.minArguments()}-${d.maxArguments()} args; got $n"
                }
              }
              refs.foreach { ref =>
                featureEnabled.get(ref) match {
                  case None => issues += s"[$name] undefined feature '$ref'"
                  case Some(false) => issues += s"[$name] disabled feature '$ref'"
                  case _ =>
                }
              }
            } catch {
              case NonFatal(e) => issues += s"[$name] expression validation failed: ${e.getMessage}"
            }
        }
      }
    }
    issues.toList
  }

  private def collectAst(node: AstNode, calls: mutable.Buffer[AstCall], refs: mutable.Set[String]): Unit = {
    node match {
      case ref: AstFeatureRef => refs += ref.featureName()
      case call: AstCall =>
        calls += call
        call.arguments().asScala.foreach(collectAst(_, calls, refs))
      case array: AstArrayLiteral => array.elements().asScala.foreach(collectAst(_, calls, refs))
      case obj: AstObjectLiteral => obj.fields().values().asScala.foreach(collectAst(_, calls, refs))
      case _ =>
    }
  }
}

object DagPreprocess {
  private case class SourceSpec(name: String, shape: String, featureType: String,
    sep: String, defaultValue: String) extends Serializable
  private case class OutputSpec(resultKey: String, columnName: String, shape: String,
    featureType: String, defaultValue: String, arrayOutput: Boolean) extends Serializable
  private case class DagExecutionSpec(configJson: String, planId: String, seqSep: String,
    batchSize: Int, preserveEmptyTokens: Boolean, splitSingletonString: Boolean,
    strictOutput: Boolean, sources: Array[SourceSpec], outputs: Array[OutputSpec]) extends Serializable
  private case class RuntimeSource(spec: SourceSpec, index: Int, pattern: Pattern, defaultValue: AnyRef) {
    // 每分区、每字段判断一次；空分隔符和 UTF-16 代理项保留原正则边界语义。
    val useLiteralScan: Boolean = {
      var supported = spec.sep.nonEmpty
      var i = 0
      while (supported && i < spec.sep.length) {
        supported = !Character.isSurrogate(spec.sep.charAt(i))
        i += 1
      }
      supported
    }
  }

  private def textOrDefault(value: String, fallback: String): String =
    Option(value).map(_.trim).filter(_.nonEmpty).getOrElse(fallback)
  private def enumValue(value: String, fallback: String): String =
    textOrDefault(value, fallback).toUpperCase(Locale.ROOT)
  private def nodeText(node: JsonNode, key: String): String =
    Option(node.get(key)).filterNot(_.isNull).map(_.asText()).orNull
  private def enabled(feature: FeatureConfig): Boolean = Option(feature.toUse()).forall(_.booleanValue())
  private def isDerived(feature: FeatureConfig): Boolean = enumValue(feature.definitionType(), "BASE") == "DERIVED"
  private def isNumeric(t: String): Boolean = Set("INT", "INTEGER", "BIGINT", "DOUBLE").contains(t)
  private def elementType(t: String): DataType = t match {
    case "INT" | "INTEGER" | "BIGINT" => LongType
    case "DOUBLE" => DoubleType
    case _ => StringType
  }
  private def requireUnique(names: Seq[String], description: String): Unit = {
    val duplicates = names.groupBy(identity).collect { case (name, xs) if xs.size > 1 => name }.toSeq.sorted
    require(duplicates.isEmpty, s"Duplicate $description: ${duplicates.mkString(",")}")
  }

  private def closeEngine(engine: AnyRef): Unit = engine match {
    case closeable: AutoCloseable =>
      try closeable.close()
      catch { case NonFatal(e) => LoggerFactory.getLogger(classOf[DagPreprocess]).warn("Failed to close DAG engine", e) }
    case _ => // 未假定引擎有 close() API；只有实现 AutoCloseable 才释放。
  }

  private def runPartition(iter: Iterator[Row], spec: DagExecutionSpec,
    indices: Array[Int], dataKey: String): Iterator[Row] = {
    if (!iter.hasNext) return Iterator.empty
    val engine = FeatureDagEngine.init(spec.configJson, InitOptions.offline(spec.planId))
    val task = TaskContext.get()
    if (task != null) task.addTaskCompletionListener[Unit] { _ => closeEngine(engine.asInstanceOf[AnyRef]) }
    val runtimeSources = new Array[RuntimeSource](math.min(spec.sources.length, indices.length))
    var sourceIndex = 0
    while (sourceIndex < runtimeSources.length) {
      val source = spec.sources(sourceIndex)
      runtimeSources(sourceIndex) = RuntimeSource(source, indices(sourceIndex),
        Pattern.compile(Pattern.quote(source.sep)), convertValue(source.defaultValue, source.featureType))
      sourceIndex += 1
    }
    val rowMapCapacity = math.max(16, (runtimeSources.length / 0.75).toInt + 1)
    val prefix = if (task == null) s"${spec.planId}:$dataKey:local" else
      s"${spec.planId}:$dataKey:s${task.stageId()}:p${task.partitionId()}:a${task.taskAttemptId()}"
    LoggerFactory.getLogger(classOf[DagPreprocess]).info(
      s"DAG partition initialized: $prefix, batchSize=${spec.batchSize}")
    require(spec.batchSize > 0,
      s"size=${spec.batchSize} and step=${spec.batchSize}, but both must be positive")
    new Iterator[Row] {
      // 仅复用分区私有的 Row 引用缓冲；请求 Map/List 和输出数组均独立拥有。
      private val rows = new util.ArrayList[Row](math.min(spec.batchSize, 1024))
      private var batchNumber = 0L
      private var outputIndex = 0
      private var resultIterator: util.Iterator[util.Map[String, util.List[_]]] = null
      private var singleOutput: Row = null

      override def hasNext: Boolean = {
        if (outputIndex < rows.size()) return true
        rows.clear()
        resultIterator = null
        singleOutput = null
        outputIndex = 0
        // 与原 grouped 相同：先读取完整批次，再依次构造各行请求。
        while (rows.size() < spec.batchSize && iter.hasNext) rows.add(iter.next())
        if (rows.isEmpty) return false
        val executionId = s"$prefix:b$batchNumber"
        batchNumber += 1L
        if (spec.batchSize == 1) {
          val row = rows.get(0)
          val result = engine.generate(OfflineGenerateRequest.borrowed(executionId,
            buildRowMap(row, runtimeSources, spec, rowMapCapacity)))
          singleOutput = appendOutput(row, result.featureValues(), spec)
        } else {
          val rowMaps = new util.ArrayList[util.Map[String, util.List[_]]](rows.size())
          var index = 0
          while (index < rows.size()) {
            rowMaps.add(buildRowMap(rows.get(index), runtimeSources, spec, rowMapCapacity))
            index += 1
          }
          val resultRows = engine.generateBatch(OfflineBatchGenerateRequest.borrowed(executionId, rowMaps)).rows()
          require(resultRows != null && resultRows.size() == rows.size(),
            s"DAG row count mismatch: $executionId, input=${rows.size()}, output=${if (resultRows == null) -1 else resultRows.size()}")
          // 用 Java iterator 保持顺序，也兼容非随机访问的结果 List。
          resultIterator = resultRows.iterator()
        }
        true
      }

      override def next(): Row = {
        if (!hasNext) throw new NoSuchElementException("next on empty iterator")
        val output = if (spec.batchSize == 1) singleOutput
          else appendOutput(rows.get(outputIndex), resultIterator.next(), spec)
        rows.set(outputIndex, null)
        outputIndex += 1
        output
      }
    }
  }

  private def convertValue(value: Any, featureType: String): AnyRef = {
    if (value == null) return null
    featureType match {
      case "DOUBLE" => value match {
        case n: java.lang.Double => n // 已为目标不可变装箱类型，避免拆箱后再次装箱。
        case n: java.lang.Number => java.lang.Double.valueOf(n.doubleValue())
        case s: String => java.lang.Double.valueOf(s)
        case _ => throw new IllegalArgumentException(s"Unsupported DOUBLE input: ${value.getClass.getName}")
      }
      case "INT" | "INTEGER" | "BIGINT" => value match {
        case n: java.lang.Long => n
        // 保留原代码 Number.longValue 语义；离在线若禁止小数截断，应共同收紧。
        case n: java.lang.Number => java.lang.Long.valueOf(n.longValue())
        case s: String => java.lang.Long.valueOf(s)
        case _ => throw new IllegalArgumentException(s"Unsupported integer input: ${value.getClass.getName}")
      }
      case _ => value.asInstanceOf[AnyRef]
    }
  }

  private def convertedOrDefault(value: Any, source: RuntimeSource): AnyRef = {
    try convertValue(value, source.spec.featureType)
    catch { case _: NumberFormatException => source.defaultValue }
  }

  private def appendStringSequence(str: String, source: RuntimeSource,
    preserveEmpty: Boolean, list: util.ArrayList[AnyRef]): Unit = {
    if (str.isEmpty) return // 空串仍表示空序列；含分隔符的空位置按开关处理。
    if (!source.useLiteralScan) {
      val tokens = source.pattern.split(str, -1)
      var i = 0
      while (i < tokens.length) {
        val token = tokens(i).trim
        if (token.nonEmpty) list.add(convertedOrDefault(token, source))
        else if (preserveEmpty) list.add(source.defaultValue)
        i += 1
      }
    } else {
      val separator = source.spec.sep
      var from = 0
      var done = false
      while (!done) {
        val next = str.indexOf(separator, from)
        var begin = from
        var end = if (next < 0) str.length else next
        // 严格使用 String.trim 的 <= U+0020 规则，只为最终非空 token 创建子串。
        while (begin < end && str.charAt(begin) <= ' ') begin += 1
        while (begin < end && str.charAt(end - 1) <= ' ') end -= 1
        if (begin < end) list.add(convertedOrDefault(str.substring(begin, end), source))
        else if (preserveEmpty) list.add(source.defaultValue)
        if (next < 0) done = true
        else from = next + separator.length
      }
    }
  }

  private def buildRowMap(row: Row, sources: Array[RuntimeSource], spec: DagExecutionSpec, rowMapCapacity: Int)
      : util.LinkedHashMap[String, util.List[_]] = {
    val rowMap = new util.LinkedHashMap[String, util.List[_]](rowMapCapacity)
    var i = 0
    while (i < sources.length) {
      val source = sources(i)
      val value = if (source.index < 0) null else row.get(source.index)
      val capacity = if (source.spec.shape == "SCALAR") 1 else value match {
        case null => 0
        // 单元素容器可能仍需拆分字符串，保留原来的初始容量。
        case values: util.List[_] => val size = values.size(); if (size == 1) 10 else size
        case values: Array[_] => if (values.length == 1) 10 else values.length
        case values: scala.collection.IndexedSeq[_] => if (values.length == 1) 10 else values.length
        case _ => 10
      }
      val list = new util.ArrayList[AnyRef](capacity)
      if (source.spec.shape == "SCALAR") {
        list.add(if (value == null) source.defaultValue else convertedOrDefault(value, source))
      } else if (value != null) {
        value match {
          case str: String => appendStringSequence(str, source, spec.preserveEmptyTokens, list)
          case seq: Seq[_] =>
            if (spec.splitSingletonString && seq.size == 1 && seq.head.isInstanceOf[String])
              appendStringSequence(seq.head.asInstanceOf[String], source, spec.preserveEmptyTokens, list)
            else {
              val values = seq.iterator
              while (values.hasNext) list.add(convertedOrDefault(values.next(), source))
            }
          case values: util.List[_] =>
            if (spec.splitSingletonString && values.size() == 1 && values.get(0).isInstanceOf[String])
              appendStringSequence(values.get(0).asInstanceOf[String], source, spec.preserveEmptyTokens, list)
            else {
              val iterator = values.iterator()
              while (iterator.hasNext) list.add(convertedOrDefault(iterator.next(), source))
            }
          case values: Array[_] =>
            if (spec.splitSingletonString && values.length == 1 && values(0).isInstanceOf[String])
              appendStringSequence(values(0).asInstanceOf[String], source, spec.preserveEmptyTokens, list)
            else {
              var index = 0
              while (index < values.length) {
                list.add(convertedOrDefault(values(index), source))
                index += 1
              }
            }
          case other => list.add(convertedOrDefault(other, source))
        }
      }
      rowMap.put(source.spec.name, list)
      i += 1
    }
    rowMap
  }

  private def appendOutput(row: Row, features: util.Map[String, util.List[_]],
      spec: DagExecutionSpec): Row = {
    require(features != null, "DAG returned null feature map")
    val inputWidth = row.length
    val values = new Array[Any](inputWidth + spec.outputs.length)
    var i = 0
    while (i < inputWidth) { values(i) = row.get(i); i += 1 }
    var j = 0
    while (j < spec.outputs.length) {
      val output = spec.outputs(j)
      if (spec.strictOutput) require(features.containsKey(output.resultKey),
        s"DAG output key missing: ${output.resultKey} (column=${output.columnName})")
      val list = features.get(output.resultKey)
      val empty = list == null || list.isEmpty
      values(inputWidth + j) = if (output.arrayOutput) {
        val size = if (empty) 1 else list.size()
        val array = new Array[AnyRef](size)
        val stringOutput = elementType(output.featureType) == StringType
        val iterator = if (empty) null else list.iterator()
        var index = 0
        while (index < size) {
          val value = if (empty) output.defaultValue else iterator.next()
          val actual = if (value == null) output.defaultValue else value
          array(index) = if (stringOutput) actual.toString else convertValue(actual, output.featureType)
          index += 1
        }
        array
      } else if (empty) output.defaultValue
      else if (output.shape == "SCALAR") {
        if (spec.strictOutput) require(list.size() == 1, s"SCALAR output '${output.resultKey}' has ${list.size()} values")
        val value = list.get(0)
        if (value == null) output.defaultValue else value.toString
      } else {
        val builder = new java.lang.StringBuilder()
        val iterator = list.iterator()
        var first = true
        while (iterator.hasNext) {
          if (!first) builder.append(spec.seqSep)
          val value = iterator.next()
          builder.append(if (value == null) output.defaultValue else value.toString)
          first = false
        }
        builder.toString
      }
      j += 1
    }
    // 数组为此输出行独占，直接交给 GenericRow，避免 toSeq/fromSeq 再复制一份。
    new GenericRow(values)
  }
}
