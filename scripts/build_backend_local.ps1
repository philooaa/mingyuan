# Build in a fresh directory so a running local backend cannot lock the output jar.
# Only isolated build output is written; this script does not restart services.
[CmdletBinding()]
param(
    [string]$MavenHome = (Join-Path $env:USERPROFILE 'Documents\Codex\2026-07-17\ge\apache-maven-3.8.8'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Settings = (Join-Path $env:USERPROFILE 'Documents\Codex\2026-07-17\ge\settings.xml'),
    [string]$TestFilter = 'ScheduleDateRuleTest'
)
$ErrorActionPreference = 'Stop'
$maven = Join-Path $MavenHome 'bin\mvn.cmd'
foreach ($required in @($maven, (Join-Path $JavaHome 'bin\java.exe'), $Settings)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing build tool/configuration: $required" }
}
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\RuoYi-Vue-master')).Path
$build = Join-Path $source ('target\local-build-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $build | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'pom.xml') -Destination $build
foreach ($module in @('ruoyi-common','ruoyi-system','ruoyi-framework','ruoyi-quartz','ruoyi-generator','ruoyi-admin')) {
    & robocopy (Join-Path $source $module) (Join-Path $build $module) /E /XD target node_modules /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Failed to copy module: $module" }
}
$previousJava = $env:JAVA_HOME
$previousPath = $env:PATH
Push-Location $build
try {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
    $arguments = @('-s', $Settings, '-pl', 'ruoyi-admin', '-am', 'package', "-Dtest=$TestFilter", '-Dsurefire.failIfNoSpecifiedTests=false')
    & $maven @arguments *> build.log
    $buildExit = $LASTEXITCODE
    Get-Content -LiteralPath 'build.log' -Tail 45
    if ($buildExit -ne 0) { throw "Build failed ($buildExit). Full log: $build\build.log" }
    Write-Output "Verified backend artifact: $build\ruoyi-admin\target\ruoyi-admin.jar"
    Write-Output 'No services were restarted and no database migrations were executed.'
}
finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:PATH = $previousPath
}
