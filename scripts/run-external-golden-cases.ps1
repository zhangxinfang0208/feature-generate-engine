$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repositoryRoot
try {
    & mvn -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt'
    if ($LASTEXITCODE -ne 0) {
        throw "Maven test compilation failed with exit code $LASTEXITCODE"
    }

    $dependencyClasspath = (Get-Content -LiteralPath 'target/test-classpath.txt' -Raw).Trim()
    $classpath = "target/test-classes;target/classes;$dependencyClasspath"
    $featureSet = Join-Path $repositoryRoot 'src/test/resources/external-golden-cases/recommendation-model-feature-set.json'
    $cases = Join-Path $repositoryRoot 'src/test/resources/external-golden-cases/recommendation-offline-cases.json'
    & java -ea -cp $classpath com.example.featuredag.blackbox.ExternalGoldenCasesVerifier $featureSet $cases
    if ($LASTEXITCODE -ne 0) {
        throw "Golden case verification failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
