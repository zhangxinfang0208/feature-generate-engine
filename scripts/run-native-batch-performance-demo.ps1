param(
    [int]$SequenceLength = 10000,
    [int]$GroupCount = 8,
    [int]$CandidatesPerGroup = 1000,
    [int]$Warmups = 2,
    [int]$Measurements = 5
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory "..")).Path

Push-Location $repositoryRoot
try {
    & mvn -q -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/demo-classpath.txt"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven demo compilation failed with exit code $LASTEXITCODE"
    }

    $classpath = "target/classes;" + (Get-Content -Raw -LiteralPath "target/demo-classpath.txt")
    & java -cp $classpath com.example.featuredag.demo.NativeBatchPerformanceDemo `
        $SequenceLength $GroupCount $CandidatesPerGroup $Warmups $Measurements
    if ($LASTEXITCODE -ne 0) {
        throw "Native Batch performance demo failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
