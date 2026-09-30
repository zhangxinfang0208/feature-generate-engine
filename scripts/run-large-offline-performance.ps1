param(
    [ValidateRange(1,3600)][int]$WarmupSeconds = 15,
    [ValidateRange(1,3600)][int]$MeasureSeconds = 30,
    [ValidateRange(1,100000)][int]$SequenceLength = 384,
    [int[]]$BatchSizes = @(1,32,128,512,1000),
    [ValidateRange(1,100)][int]$PoolSize = 2,
    [ValidateRange(1,100)][int]$Runs = 3,
    [ValidateSet('engine','request')][string[]]$Scopes = @('engine','request'),
    [switch]$Profile,
    [ValidateSet('snapshot','borrowed')][string]$InputMode = 'borrowed',
    [string]$OutputDirectory = 'target/large-offline-performance'
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & mvn -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; JDK 21 and Maven are required' }
    $cp = "target/test-classes;target/classes;$((Get-Content target/test-classpath.txt -Raw).Trim())"
    $sample = $Profile.IsPresent.ToString().ToLowerInvariant()
    foreach ($run in 1..$Runs) {
        foreach ($scope in $Scopes) {
            foreach ($mode in @('single','batch')) {
                foreach ($size in $BatchSizes) {
                    if ($size -lt 1) { throw 'BatchSizes must be positive' }
                    $out = Join-Path $OutputDirectory "$mode-$scope-b$size-run$run"
                    & java '-XX:FlightRecorderOptions=stackdepth=128' -cp $cp com.example.featuredag.performance.LargeOfflineBenchmark `
                        $out $WarmupSeconds $MeasureSeconds $SequenceLength $size $PoolSize $mode $scope $sample $InputMode
                    if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $out" }
                    if ($Profile) {
                        & java -cp $cp com.example.featuredag.performance.JfrFlamegraphExporter (Join-Path $out 'measurement.jfr') $out
                        if ($LASTEXITCODE -ne 0) { throw 'JFR export failed' }
                        & python scripts/render-jfr-flames.py $out
                        if ($LASTEXITCODE -ne 0) { throw 'Flame graph rendering failed' }
                    }
                }
            }
        }
    }
} finally { Pop-Location }
