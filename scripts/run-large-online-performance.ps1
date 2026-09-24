param(
    [ValidateRange(1, 3600)][int]$WarmupSeconds = 15,
    [ValidateRange(1, 3600)][int]$MeasureSeconds = 30,
    [ValidateRange(1, 100000)][int]$SequenceLength = 384,
    [ValidateRange(1, 10000)][int]$Candidates = 500,
    [ValidateRange(1, 100)][int]$Runs = 3,
    [ValidatePattern('^\d+[mgMG]$')][string]$Heap = '2g',
    [switch]$CaptureDiagnostics,
    [switch]$Profile,
    [string]$OutputDirectory = 'target/large-online-performance'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $javaExecutable = (Get-Command java -CommandType Application | Select-Object -First 1).Source
    $javaSettings = & $javaExecutable -XshowSettings:properties -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $javaSettings -notmatch '(?m)^\s*java.specification.version\s*=\s*21\s*$') {
        throw "This benchmark requires JDK 21. Resolved Java: $javaExecutable"
    }
    & mvn -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
    $cp = "target/test-classes;target/classes;$(Get-Content target/test-classpath.txt -Raw)".Trim()
    $observe = $CaptureDiagnostics.IsPresent.ToString().ToLowerInvariant()
    $sample = $Profile.IsPresent.ToString().ToLowerInvariant()
    for ($run = 1; $run -le $Runs; $run++) {
        $out = Join-Path $OutputDirectory "run-$run"
        if (Test-Path $out) { throw "Output already exists: $out. Choose a new OutputDirectory." }
        New-Item -ItemType Directory -Path $out -Force | Out-Null
        # Relative log path avoids drive-letter ambiguity in -Xlog syntax.
        $gcLog = "target/large-online-gc-$([Guid]::NewGuid().ToString('N')).log"
        $jvm = @("-Xms$Heap", "-Xmx$Heap", '-XX:+UseZGC', '-XX:+ZGenerational',
            '-XX:FlightRecorderOptions=stackdepth=128', "-Xlog:gc*,safepoint:file=${gcLog}:time,uptime,level,tags")
        & $javaExecutable @jvm -cp $cp com.example.featuredag.performance.LargeOnlineBenchmark `
            $out $WarmupSeconds $MeasureSeconds $SequenceLength $Candidates $observe $sample 2>&1 |
            Tee-Object -FilePath (Join-Path $out 'console.log')
        $javaExit = $LASTEXITCODE
        if (Test-Path $gcLog) { Move-Item -LiteralPath $gcLog -Destination (Join-Path $out 'gc.log') }
        if ($javaExit -ne 0) { throw "Benchmark JVM failed: $javaExit" }
        if ($Profile) {
            & $javaExecutable -cp $cp com.example.featuredag.performance.JfrFlamegraphExporter (Join-Path $out 'measurement.jfr') $out
            if ($LASTEXITCODE -ne 0) { throw 'JFR export failed' }
            & python scripts/render-jfr-flames.py $out
            if ($LASTEXITCODE -ne 0) { throw 'Flame graph rendering failed' }
        }
    }
} finally { Pop-Location }
