param(
    [string]$Out = (Join-Path $PSScriptRoot "www")
)

$ErrorActionPreference = "Stop"

$ff = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source
if (-not $ff) { throw "需要 ffmpeg（PATH 中找不到）" }

if (-not (Test-Path $Out)) { New-Item -ItemType Directory -Path $Out | Out-Null }

Get-ChildItem $Out -Filter "seg*.ts" -ErrorAction SilentlyContinue | Remove-Item -Force
Remove-Item (Join-Path $Out "local.m3u8") -ErrorAction SilentlyContinue

& $ff -y -loglevel error `
    -f lavfi -i "testsrc=size=640x360:rate=25" -t 40 `
    -c:v libx264 -profile:v main -level 3.1 -pix_fmt yuv420p -preset veryfast `
    -g 50 -keyint_min 25 -sc_threshold 0 `
    -f hls -hls_time 2 -hls_list_size 0 -hls_segment_type mpegts `
    -hls_segment_filename (Join-Path $Out "seg%03d.ts") `
    (Join-Path $Out "local.m3u8")

if ($LASTEXITCODE -ne 0) { throw "ffmpeg 生成失败" }

$segments = (Get-ChildItem $Out -Filter "seg*.ts").Count
Write-Host "生成完成：$Out（$segments 个分片，local.m3u8）"
