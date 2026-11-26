param(
  [string]$Version = "1.0.0",
  [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

# Keep ALL SDK, NuGet, and Temp artifacts strictly on K:\SaveGaze-PC\.tools when present
$LocalDotnet = Join-Path $RepoRoot ".tools\dotnet\dotnet.exe"
$Dotnet = if (Test-Path $LocalDotnet) { $LocalDotnet } else { "dotnet" }

if (Test-Path (Join-Path $RepoRoot ".tools")) {
  $env:DOTNET_CLI_HOME = Join-Path $RepoRoot ".tools\dotnet-home"
  $env:NUGET_PACKAGES = Join-Path $RepoRoot ".tools\nuget"
  $env:NUGET_HTTP_CACHE_PATH = Join-Path $RepoRoot ".tools\nuget-http"
  $env:TEMP = Join-Path $RepoRoot ".tools\tmp"
  $env:TMP = Join-Path $RepoRoot ".tools\tmp"
  New-Item -ItemType Directory -Force $env:DOTNET_CLI_HOME, $env:NUGET_PACKAGES, $env:NUGET_HTTP_CACHE_PATH, $env:TEMP | Out-Null
}
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = "1"
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"

$DistDir = Join-Path $RepoRoot "dist"
$AppOut = Join-Path $DistDir "SaveGaze-PC-win-x64"
$SetupOut = Join-Path $DistDir "setup-publish"
if (Test-Path $DistDir) { Remove-Item -Recurse -Force $DistDir }
New-Item -ItemType Directory -Force $DistDir, $AppOut, $SetupOut | Out-Null

if (-not $SkipTests) {
  Write-Host "[1/5] Running unit + ONNX smoke tests..."
  & $Dotnet test (Join-Path $RepoRoot "src\SaveGaze.Tests\SaveGaze.Tests.csproj") -c Release --nologo
  if ($LASTEXITCODE -ne 0) { throw "Tests failed." }
}

Write-Host "[2/5] Publishing self-contained SaveGaze.Desktop (win-x64)..."
& $Dotnet publish (Join-Path $RepoRoot "src\SaveGaze.Desktop\SaveGaze.Desktop.csproj") `
  -c Release -r win-x64 --self-contained true `
  -p:Version=$Version -o $AppOut --nologo
if ($LASTEXITCODE -ne 0) { throw "Desktop publish failed." }

# Include extension folder inside portable bundle
Copy-Item -Recurse -Force (Join-Path $RepoRoot "extension") (Join-Path $AppOut "extension")

Write-Host "[3/5] Packaging SaveGaze-Extension.zip and SaveGaze-PC-win-x64.zip..."
$ExtZip = Join-Path $DistDir "SaveGaze-Extension.zip"
Compress-Archive -Path (Join-Path $RepoRoot "extension\*") -DestinationPath $ExtZip -CompressionLevel Optimal

$PortableZip = Join-Path $DistDir "SaveGaze-PC-win-x64.zip"
Compress-Archive -Path (Join-Path $AppOut "*") -DestinationPath $PortableZip -CompressionLevel Optimal

# Embedded payload for single-file Setup.exe
$PayloadZip = Join-Path $DistDir "payload.zip"
Copy-Item -Force $PortableZip $PayloadZip

Write-Host "[4/5] Building single-file SaveGaze-Setup-win-x64.exe (.NET Framework 4.8 launcher, zero duplicate runtime)..."
& $Dotnet build (Join-Path $RepoRoot "src\SaveGaze.Setup\SaveGaze.Setup.csproj") `
  -c Release -p:Version=$Version -o $SetupOut --nologo
if ($LASTEXITCODE -ne 0) { throw "Setup build failed." }

$SetupExe = Join-Path $DistDir "SaveGaze-Setup-win-x64.exe"
Copy-Item -Force (Join-Path $SetupOut "SaveGaze-Setup.exe") $SetupExe
Remove-Item -Force $PayloadZip
Remove-Item -Recurse -Force $SetupOut

Write-Host "[5/5] Writing pc-version.json..."
$SetupSize = (Get-Item $SetupExe).Length
$Tag = "pc-v$Version"
$Meta = @{
  versionCode = 10000
  versionName = $Version
  tagName = $Tag
  publishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
  releaseNotes = "SaveGaze PC v${Version}: System-wide on-device AI Screen Blur (best_320s + pordav4x3), WDA_EXCLUDEFROMCAPTURE layered overlay, Face Blur toggle with jawline neck protection, Intimate Scene/Caption & WASAPI Loopback Audio Guard (image_safety_xs + yamnet), and enhanced Browser Extension."
  downloadUrl = "https://github.com/Mahbubulhuq123/SaveGaze/releases/download/$Tag/SaveGaze-Setup-win-x64.exe"
  portableUrl = "https://github.com/Mahbubulhuq123/SaveGaze/releases/download/$Tag/SaveGaze-PC-win-x64.zip"
  extensionUrl = "https://github.com/Mahbubulhuq123/SaveGaze/releases/download/$Tag/SaveGaze-Extension.zip"
  installerSize = $SetupSize
}
$Json = $Meta | ConvertTo-Json -Depth 4
Set-Content -Path (Join-Path $DistDir "pc-version.json") -Value $Json -Encoding UTF8
Set-Content -Path (Join-Path $RepoRoot "pc-version.json") -Value $Json -Encoding UTF8

Write-Host "Release artifacts generated cleanly in $DistDir"
Get-ChildItem $DistDir | Select-Object Name, Length
