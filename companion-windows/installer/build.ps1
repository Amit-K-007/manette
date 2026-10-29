# Builds the Windows installer:  powershell -File companion-windows\installer\build.ps1
# Needs the .NET 10 SDK and Inno Setup 6 (https://jrsoftware.org/isinfo.php).
#
#   -IsccPath "D:\Inno Setup 6\ISCC.exe"   use Inno Setup from a custom location
#   -SkipPublish                           reuse the existing companion-windows\publish folder
#
# Inno Setup is looked for in this order: -IsccPath, the ISCC_PATH environment variable, PATH, the registry entry the Inno
# installer writes (this finds custom install folders such as another drive), then the usual Program Files folders.
# NOT RUN end to end: the installer script itself has not been tested on a clean machine.
# Publishes as a folder, not a single file, because ViGEm's native DLL is embedded by its client library and
# single-file packaging of that has not been verified.
param(
    [string]$IsccPath,
    [switch]$SkipPublish
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$publish = Join-Path $root 'publish'

function Find-Iscc {
    if ($IsccPath) { return $IsccPath }
    if ($env:ISCC_PATH) { return $env:ISCC_PATH }

    $onPath = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $uninstallKeys = @(
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1'
    )
    foreach ($key in $uninstallKeys) {
        $location = (Get-ItemProperty $key -ErrorAction SilentlyContinue).InstallLocation
        if ($location) {
            $candidate = Join-Path $location 'ISCC.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }

    foreach ($base in @(${env:ProgramFiles(x86)}, $env:ProgramFiles, (Join-Path $env:LOCALAPPDATA 'Programs'))) {
        if ($base) {
            $candidate = Join-Path $base 'Inno Setup 6\ISCC.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    return $null
}

# Find Inno Setup first, so a missing install fails in a second rather than after the build.
$iscc = Find-Iscc
if (-not $iscc -or -not (Test-Path $iscc)) {
    throw "Inno Setup 6 (ISCC.exe) was not found. Re-run with:  -IsccPath ""D:\path\to\Inno Setup 6\ISCC.exe"""
}
Write-Host "Using Inno Setup: $iscc"

if (-not $SkipPublish) {
    if (Test-Path $publish) { Remove-Item $publish -Recurse -Force }
    dotnet publish (Join-Path $root 'src\Manette.App\Manette.App.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -o $publish
    if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed' }
} elseif (-not (Test-Path $publish)) {
    throw "-SkipPublish was given but $publish does not exist. Run once without it."
}

& $iscc (Join-Path $PSScriptRoot 'manette.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup failed' }
Write-Host "Installer written to $(Join-Path $PSScriptRoot 'output')"