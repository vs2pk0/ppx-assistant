param(
    [string]$SourceRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$TargetRoot = "D:\ppx-master-build"
)

$ErrorActionPreference = "Stop"

function Remove-PathIfExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LiteralPath
    )

    if (Test-Path -LiteralPath $LiteralPath) {
        Remove-Item -LiteralPath $LiteralPath -Recurse -Force
    }
}

$SourceRoot = [System.IO.Path]::GetFullPath($SourceRoot)
$TargetRoot = [System.IO.Path]::GetFullPath($TargetRoot)

if (-not (Test-Path -LiteralPath $SourceRoot)) {
    throw "SourceRoot not found: $SourceRoot"
}

if (-not (Test-Path -LiteralPath $TargetRoot)) {
    New-Item -ItemType Directory -Path $TargetRoot | Out-Null
}

Write-Host "Sync source : $SourceRoot"
Write-Host "Sync target : $TargetRoot"

$cleanupDirectories = @(
    ".gradle",
    "build",
    "buildSrc\.gradle",
    "buildSrc\build",
    "app\build",
    "xposed-modern-api102-entry\build",
    "c"
)

foreach ($relativePath in $cleanupDirectories) {
    Remove-PathIfExists -LiteralPath (Join-Path $TargetRoot $relativePath)
}

Get-ChildItem -LiteralPath $TargetRoot -Filter "java_pid*.hprof" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue

$robocopyArgs = @(
    $SourceRoot,
    $TargetRoot,
    "/MIR",
    "/R:1",
    "/W:1",
    "/NFL",
    "/NDL",
    "/NP",
    "/XD",
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    "captures",
    ".externalNativeBuild",
    ".cxx",
    "local-notes",
    "app\build",
    "buildSrc\.gradle",
    "buildSrc\build",
    "xposed-modern-api102-entry\build",
    "/XF",
    "local.properties",
    "*.iml",
    "*.hprof"
)

& robocopy @robocopyArgs | Out-Host
$robocopyExitCode = $LASTEXITCODE

if ($robocopyExitCode -ge 8) {
    throw "robocopy failed with exit code $robocopyExitCode"
}

Write-Host "Build mirror sync completed."
