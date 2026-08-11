param(
    [ValidateSet("all", "scalar", "sequence", "batch")]
    [string]$Demo = "all"
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
    $demoClasses = @{
        scalar = "com.example.featuredag.demo.ScalarOperatorsDemo"
        sequence = "com.example.featuredag.demo.SequenceOperatorsDemo"
        batch = "com.example.featuredag.demo.OfflineBatchOperatorsDemo"
    }
    $selected = if ($Demo -eq "all") {
        @("scalar", "sequence", "batch")
    } else {
        @($Demo)
    }

    foreach ($name in $selected) {
        & java -cp $classpath $demoClasses[$name]
        if ($LASTEXITCODE -ne 0) {
            throw "Demo $name failed with exit code $LASTEXITCODE"
        }
    }
} finally {
    Pop-Location
}
