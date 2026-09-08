param(
    [string]$SequenceLengths = "50,200,1000,3000",
    [int]$GroupCount = 4,
    [int]$CandidatesPerGroup = 500,
    [int]$TargetCount = 5,
    [int]$ValueDomain = 64,
    [int]$Warmups = 5,
    [int]$Measurements = 9
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory "..")).Path

Push-Location $repositoryRoot
try {
    & mvn -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) {
        throw "Maven demo compilation failed with exit code $LASTEXITCODE"
    }

    & java -cp "target/classes" `
        com.example.featuredag.demo.FindIndicesAnyBatchPerformanceDemo `
        $SequenceLengths $GroupCount $CandidatesPerGroup $TargetCount `
        $ValueDomain $Warmups $Measurements
    if ($LASTEXITCODE -ne 0) {
        throw "find_indices_any performance demo failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
