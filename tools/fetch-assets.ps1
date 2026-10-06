# 補齊 Minecraft 資源檔（ForgeGradle 的 slime-launcher 下載器遇錯即中止，且不重試，
# 導致 runClient 反覆失敗）。這裡改用 curl 逐檔重試，只補真正缺少的部分。
$ErrorActionPreference = 'Continue'
$assets = Join-Path $env:APPDATA '.minecraft\assets'
$baseUrl = 'https://resources.download.minecraft.net'

# 找出實際使用的 index（slime-launcher 用 --assetIndex {asset_index} 帶入）
$indexFile = Get-ChildItem (Join-Path $assets 'indexes') -Filter '*.json' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Output "使用 index: $($indexFile.Name)"
$index = Get-Content $indexFile.FullName -Raw | ConvertFrom-Json

$present = @{}
Get-ChildItem (Join-Path $assets 'objects') -Recurse -File -ErrorAction SilentlyContinue |
    ForEach-Object { $present[$_.Name] = $true }

$missing = @()
foreach ($p in $index.objects.PSObject.Properties) {
    if (-not $present.ContainsKey($p.Value.hash)) {
        $missing += [PSCustomObject]@{ Path = $p.Name; Hash = $p.Value.hash; Size = [int64]$p.Value.size }
    }
}

$totalMissing = $missing.Count
$totalBytes = ($missing | Measure-Object Size -Sum).Sum
Write-Output ("缺少 {0} 個檔案, {1} MB -- 開始補齊" -f $totalMissing, [math]::Round($totalBytes / 1MB, 1))

$done = 0
$failed = @()
$sw = [Diagnostics.Stopwatch]::StartNew()

foreach ($item in $missing) {
    $prefix = $item.Hash.Substring(0, 2)
    $dir = Join-Path $assets "objects\$prefix"
    $target = Join-Path $dir $item.Hash
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    if (Test-Path $target) { $done++; continue }

    $url = "$baseUrl/$prefix/$($item.Hash)"
    $ok = $false
    for ($attempt = 1; $attempt -le 4 -and -not $ok; $attempt++) {
        # --retry 處理連線中斷；-f 讓 404 之類明確失敗
        curl.exe -sS -f -L --retry 3 --retry-delay 1 --retry-all-errors `
            --connect-timeout 20 --max-time 120 -o $target $url 2>$null
        if ($LASTEXITCODE -eq 0 -and (Test-Path $target)) { $ok = $true }
        else { Start-Sleep -Milliseconds (300 * $attempt) }
    }
    if ($ok) { $done++ } else { $failed += $item.Path; Remove-Item $target -Force -ErrorAction SilentlyContinue }

    if ($done % 50 -eq 0 -and $done -gt 0) {
        Write-Output ("  進度 {0}/{1}  已用 {2} 分鐘" -f $done, $totalMissing, [math]::Round($sw.Elapsed.TotalMinutes, 1))
    }
}

$sw.Stop()
Write-Output ""
Write-Output ("完成: {0}/{1} 補齊, 失敗 {2} 個, 耗時 {3} 分鐘" -f $done, $totalMissing, $failed.Count, [math]::Round($sw.Elapsed.TotalMinutes, 1))
if ($failed.Count -gt 0) {
    Write-Output "失敗清單（前 20）:"
    $failed | Select-Object -First 20 | ForEach-Object { Write-Output "  $_" }
}

# 最後重新核對
$present2 = @{}
Get-ChildItem (Join-Path $assets 'objects') -Recurse -File -ErrorAction SilentlyContinue |
    ForEach-Object { $present2[$_.Name] = $true }
$stillMissing = 0
foreach ($p in $index.objects.PSObject.Properties) {
    if (-not $present2.ContainsKey($p.Value.hash)) { $stillMissing++ }
}
Write-Output "核對後仍缺: $stillMissing 個"
