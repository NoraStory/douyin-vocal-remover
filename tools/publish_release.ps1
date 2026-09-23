# 一键发布脚本：构建 APK -> GitHub Release（APK + 模型资产）-> Gitee 镜像同步
# 用法：.\tools\publish_release.ps1 -Version "1.5.0" [-NotesFile "tools\_release_notes_150.md"]
# 前置：
#   - gh CLI 已登录 GitHub
#   - Gitee 同步需要环境变量 GITEE_TOKEN（私人令牌，scope: projects）
#   - local.properties 中 gitee.owner / gitee.repo（App 内匿名检测用，不含令牌；
#     令牌绝不写入 App——公开仓库的 Release 检测与附件下载均匿名可用）
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$NotesFile = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $root "android"
$apkPath = Join-Path $androidDir "app\build\outputs\apk\release\app-release.apk"
$modelDir = Join-Path $androidDir "app\src\main\assets.models\models"
$tag = "v$Version"

Write-Host "==> [1/5] 构建 Release APK ($tag)" -ForegroundColor Cyan
Push-Location $androidDir
try {
    ./gradlew.bat :app:assembleRelease -q
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
} finally { Pop-Location }
$apkSizeMB = [math]::Round((Get-Item $apkPath).Length / 1MB, 1)
Write-Host "    APK: $apkSizeMB MB"

Write-Host "==> [2/5] 生成模型 SHA-256 清单" -ForegroundColor Cyan
$shaLines = @()
Get-ChildItem $modelDir -Filter *.onnx | ForEach-Object {
    $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
    $shaLines += "$hash  $($_.Name)"
    Write-Host "    $($_.Name): $($hash.Substring(0,16))..."
}
$shaFile = Join-Path $env:TEMP "sha256_$Version.txt"
$shaLines | Out-File $shaFile -Encoding ascii

Write-Host "==> [3/5] GitHub Release" -ForegroundColor Cyan
$notesArgs = @()
if ($NotesFile -and (Test-Path $NotesFile)) { $notesArgs += @("--notes-file", (Resolve-Path $NotesFile)) }
else { $notesArgs += @("--notes", "Release $tag") }

# 模型资产只在首次发布时上传（后续版本复用既有资产，避免重复上传 350MB）
$existing = gh release view $tag --repo NoraStory/douyin-vocal-remover --json assets --jq '.assets[].name' 2>$null
$ghAssets = @($apkPath, $shaFile)
if (-not $existing -or -not ($existing -contains "htdemucs_fp32.onnx")) {
    $ghAssets += (Join-Path $modelDir "htdemucs_fp32.onnx")
}
if (-not $existing -or -not ($existing -contains "htdemucs_fp16.onnx")) {
    $ghAssets += (Join-Path $modelDir "htdemucs_fp16.onnx")
}
gh release create $tag --repo NoraStory/douyin-vocal-remover --title "$tag" @notesArgs @ghAssets
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
Write-Host "    GitHub release created: $tag"

Write-Host "==> [4/5] Gitee 镜像同步" -ForegroundColor Cyan
$giteeToken = $env:GITEE_TOKEN
$giteeOwner = ""
$giteeRepo = ""
$localProps = Join-Path $androidDir "local.properties"
if (Test-Path $localProps) {
    Get-Content $localProps | ForEach-Object {
        if ($_ -match "^gitee\.owner\s*=\s*(.+)$") { $giteeOwner = $Matches[1].Trim() }
        if ($_ -match "^gitee\.repo\s*=\s*(.+)$") { $giteeRepo = $Matches[1].Trim() }
    }
}
if ($giteeToken -and $giteeOwner -and $giteeRepo) {
    # 推送代码镜像
    git remote get-url gitee 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        git remote add gitee "https://gitee.com/$giteeOwner/$giteeRepo.git"
    }
    git push gitee master
    if ($LASTEXITCODE -ne 0) { Write-Warning "git push gitee failed（检查令牌/仓库）" }

    # 创建 Gitee Release（含 APK + sha256；模型资产已存在则跳过）
    $bodyText = if ($NotesFile -and (Test-Path $NotesFile)) { Get-Content (Resolve-Path $NotesFile) -Raw } else { "Release $tag" }
    $createBody = @{
        access_token = $giteeToken
        tag_name     = $tag
        name         = $tag
        body         = $bodyText
        files        = @($apkPath, $shaFile)
    } | ConvertTo-Json -Depth 3
    $resp = Invoke-RestMethod -Method Post -Uri "https://gitee.com/api/v5/repos/$giteeOwner/$giteeRepo/releases" `
        -ContentType "application/json" -Body $createBody -ErrorAction SilentlyContinue
    if ($resp) { Write-Host "    Gitee release created: $tag" }
    else { Write-Warning "Gitee release 创建失败（可能已存在，或令牌无 projects 权限）" }
} else {
    Write-Warning "跳过 Gitee 同步：未配置 GITEE_TOKEN 或 local.properties 缺 gitee.owner/gitee.repo"
}

Write-Host "==> [5/5] 根目录 APK 副本" -ForegroundColor Cyan
Copy-Item $apkPath (Join-Path $root "抖音去人声_$Version.apk") -Force
Copy-Item $apkPath (Join-Path $root "douyin-vocal-remover-v$Version.apk") -Force
Write-Host "完成 ✔  $tag"
