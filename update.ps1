# 一键更新：拉取GitHub最新代码 + 恢复Maven资源文件
# 用法：在项目根目录执行  .\update.ps1
Write-Host "===== 1/2 拉取最新代码 =====" -ForegroundColor Cyan
git pull
if ($LASTEXITCODE -ne 0) { Write-Host "git pull 失败，请先解决冲突" -ForegroundColor Red; exit 1 }

Write-Host "===== 2/2 恢复资源文件（IDE重建会丢失 application.yml 等）=====" -ForegroundColor Cyan
& "C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.10-bin\53h08a94dg6djh6umvruv7q564\apache-maven-3.9.10\bin\mvn.cmd" resources:resources -q
if ($LASTEXITCODE -ne 0) { Write-Host "资源恢复失败" -ForegroundColor Red; exit 1 }

Write-Host "更新完成，可以启动后端了（IDE点启动或 mvn spring-boot:run 均可）" -ForegroundColor Green
