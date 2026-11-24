[CmdletBinding()]
param(
    [string]$MySqlHome = 'D:\BaiduNetdiskDownload\mysql\01MySQL\mysql-8.0.31-winx64',
    [string]$RedisHome = 'D:\BaiduNetdiskDownload\ruoyi_envir',
    [string]$Java = '',
    [int]$TimeoutSeconds = 120,
    [switch]$BuildBackend
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$app = Join-Path $root 'RuoYi-Vue-master'
$logs = Join-Path $root 'logs\local-start'
New-Item -ItemType Directory -Path $logs -Force | Out-Null

function Test-Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $Port); return $true } catch { return $false } finally { $client.Dispose() }
}
function Wait-For([scriptblock]$Check, [string]$Name, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do { if (& $Check) { return }; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
    throw "$Name did not become ready within $Seconds seconds. Check logs in $logs."
}
function Test-Http([string]$Url) {
    try { $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3; return ($r.StatusCode -eq 200) } catch { return $false }
}
function Launch([string]$Exe, [string[]]$Arguments, [string]$WorkingDirectory, [string]$Name) {
    if (-not (Test-Path -LiteralPath $Exe -PathType Leaf)) { throw "Missing executable: $Exe" }
    Start-Process -FilePath $Exe -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs "$Name.out.log") -RedirectStandardError (Join-Path $logs "$Name.err.log") | Out-Null
}

try {
    $mysql = Join-Path $MySqlHome 'bin\mysql.exe'
    $mysqld = Join-Path $MySqlHome 'bin\mysqld.exe'
    $redis = Join-Path $RedisHome 'redis-server.exe'
    $redisCli = Join-Path $RedisHome 'redis-cli.exe'
    foreach ($file in @($mysql, $mysqld, $redis, $redisCli)) { if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing executable: $file" } }
    if (!$env:MYSQL_PASSWORD) {
        $securePassword = Read-Host 'MySQL root password' -AsSecureString
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try { $env:MYSQL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    }
    if (!$env:MYSQL_PASSWORD) { throw 'A MySQL password is required.' }
    if (!(Test-Port 3306)) {
        Write-Host 'Starting MySQL...'
        Launch $mysqld @("--defaults-file=`"$(Join-Path $MySqlHome 'my.ini')`"", "--basedir=`"$MySqlHome`"", "--datadir=`"$(Join-Path $MySqlHome 'data')`"", '--console') $MySqlHome 'mysql'
        Wait-For { Test-Port 3306 } 'MySQL' $TimeoutSeconds
    } else { Write-Host 'MySQL port is already open; checking database...' }
    $oldMysqlPwd = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $env:MYSQL_PASSWORD
        $db = & $mysql --host=127.0.0.1 --user=root --connect-timeout=5 --batch --skip-column-names -e 'SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME=CHAR(109,105,110,103,121,117,97,110,101,100,117,109,105,110,105,97,112,112)' 2>$null
        if ($LASTEXITCODE -ne 0 -or $db -ne 'mingyuaneduminiapp') { throw 'MySQL login or database check failed. Check the password and database.' }
        $schemaCheck = & $mysql --host=127.0.0.1 --user=root --connect-timeout=5 --batch --skip-column-names mingyuaneduminiapp -e "SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_course_schedule' AND column_name IN ('custom_dates','schedule_rule_id','schedule_rule_name','schedule_rule_version'))=4 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('edu_schedule_rule','edu_schedule_rule_date','edu_attendance_lesson_guest'))=3,'ready','missing')" 2>$null
        if ($LASTEXITCODE -ne 0 -or $schemaCheck -ne 'ready') { throw 'Database schema is incomplete. Review database/migrations/20261123_schedule_rules.sql and 20261118_attendance_lesson_guests.sql after making a backup. Startup does not apply migrations automatically.' }
    } finally { $env:MYSQL_PWD = $oldMysqlPwd }
    Write-Host 'MySQL database: ready'

    if (!(Test-Port 6379)) {
        Write-Host 'Starting Redis...'
        Launch $redis @("`"$(Join-Path $RedisHome 'redis.windows.conf')`"") $RedisHome 'redis'
        Wait-For { Test-Port 6379 } 'Redis' $TimeoutSeconds
    } else { Write-Host 'Redis port is already open; checking service...' }
    $pong = & $redisCli -h 127.0.0.1 -p 6379 ping 2>$null
    if ($LASTEXITCODE -ne 0 -or $pong -ne 'PONG') { throw 'Redis is not responding to PING on port 6379.' }
    Write-Host 'Redis: ready'

    if ($BuildBackend) {
        if (Test-Port 8080) { throw 'Backend is already running. Stop it before building and starting a replacement.' }
        & (Join-Path $PSScriptRoot 'build_backend_local.ps1')
    }
    if (!(Test-Port 8080)) {
        if (!$Java) { $Java = (Get-Command java.exe -ErrorAction Stop).Source }
        $jar = Get-ChildItem (Join-Path $app 'target') -Filter 'ruoyi-admin.jar' -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Length -gt 50000000 } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (!$jar) { throw 'No packaged backend found. Build it using scripts\build_backend_local.ps1 first.' }
        Write-Host "Starting backend from $($jar.FullName)"
        Launch $Java @('-jar', "`"$($jar.FullName)`"", '--spring.profiles.active=druid,local', '--server.address=127.0.0.1') $app 'backend'
        Wait-For { Test-Http 'http://127.0.0.1:8080/captchaImage' } 'Backend' $TimeoutSeconds
    } elseif (!(Test-Http 'http://127.0.0.1:8080/captchaImage')) { throw 'Port 8080 is occupied but the backend health check failed.' }
    Write-Host 'Backend: ready at http://localhost:8080/'

    if (!(Test-Port 8081)) {
        $ui = Join-Path $app 'ruoyi-ui'
        $cli = Join-Path $ui 'node_modules\@vue\cli-service\bin\vue-cli-service.js'
        if (!(Test-Path -LiteralPath $cli)) { throw 'Frontend dependencies are missing. Run npm install in RuoYi-Vue-master\ruoyi-ui.' }
        $node = (Get-Command node.exe -ErrorAction Stop).Source
        Write-Host 'Starting frontend (first compilation can take a while)...'
        Launch $node @("`"$cli`"", 'serve') $ui 'frontend'
        Wait-For { Test-Http 'http://127.0.0.1:8081/' } 'Frontend' $TimeoutSeconds
    } elseif (!(Test-Http 'http://127.0.0.1:8081/')) { throw 'Port 8081 is occupied but the frontend health check failed.' }
    Write-Host 'Frontend: ready at http://localhost:8081/'
    Write-Host "Logs: $logs"
} catch {
    Write-Error $_
    exit 1
}
