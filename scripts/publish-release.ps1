param(
  [string]$Version = "1.0.0",
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$DistDir = Join-Path $RepoRoot "dist"
$Tag = "pc-v$Version"

$Assets = @(
  @{ Path = (Join-Path $DistDir "SaveGaze-Setup-win-x64.exe"); ContentType = "application/octet-stream" },
  @{ Path = (Join-Path $DistDir "SaveGaze-PC-win-x64.zip"); ContentType = "application/zip" },
  @{ Path = (Join-Path $DistDir "SaveGaze-Extension.zip"); ContentType = "application/zip" },
  @{ Path = (Join-Path $DistDir "pc-version.json"); ContentType = "application/json" }
)

foreach ($a in $Assets) {
  if (-not (Test-Path $a.Path)) {
    throw "Missing release asset: $($a.Path). Run scripts\build-release.ps1 first."
  }
}

# Retrieve GitHub token via git credential manager without echoing it
$credInput = "protocol=https`nhost=github.com`n"
$credLines = $credInput | git credential fill
$tokenLine = $credLines | Where-Object { $_ -match '^password=' } | Select-Object -First 1
if (-not $tokenLine) {
  throw "No GitHub token returned by git credential fill."
}
$token = $tokenLine.Substring("password=".Length).Trim()

$headers = @{
  Authorization = "Bearer $token"
  Accept        = "application/vnd.github+json"
  "User-Agent"  = "SaveGaze-PC-ReleaseScript"
}

$owner = "Mahbubulhuq123"
$repo = "SaveGaze"
$meta = Get-Content (Join-Path $DistDir "pc-version.json") -Raw | ConvertFrom-Json

if ($DryRun) {
  Write-Host "DryRun OK: tag=$Tag assets=$($Assets.Count)"
  exit 0
}

# 1. Create or fetch existing GitHub release (explicitly make_latest = "false" so Android v* stays Latest!)
$release = $null
try {
  $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases/tags/$Tag" -Headers $headers -Method Get
  Write-Host "Found existing GitHub Release for $Tag (id=$($release.id))"
} catch {
  $body = @{
    tag_name         = $Tag
    target_commitish = "pc-version"
    name             = "SaveGaze PC v$Version"
    body             = $meta.releaseNotes
    draft            = $false
    prerelease       = $false
    make_latest      = "false"
  } | ConvertTo-Json -Depth 4
  $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases" -Headers $headers -Method Post -Body $body -ContentType "application/json"
  Write-Host "Created GitHub Release id=$($release.id) for $Tag (make_latest=false)"
}

# 2. Upload or replace assets
$uploadBase = $release.upload_url -replace '\{\?name,label\}$', ''
$existingAssets = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases/$($release.id)/assets" -Headers $headers -Method Get

foreach ($a in $Assets) {
  $fileName = [System.IO.Path]::GetFileName($a.Path)
  $dup = $existingAssets | Where-Object { $_.name -eq $fileName }
  if ($dup) {
    Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases/assets/$($dup.id)" -Headers $headers -Method Delete | Out-Null
    Write-Host "Deleted previous asset: $fileName"
  }

  $uploadUri = "${uploadBase}?name=$([uri]::EscapeDataString($fileName))"
  Write-Host "Uploading $fileName..."
  $res = Invoke-RestMethod -Uri $uploadUri -Headers $headers -Method Post -InFile $a.Path -ContentType $a.ContentType
  Write-Host "Uploaded: $($res.browser_download_url) ($($res.size) bytes)"
}

Write-Host "Release $Tag published successfully without touching main or Android latest release!"
