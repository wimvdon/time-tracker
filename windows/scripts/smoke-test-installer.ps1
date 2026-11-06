<#
.SYNOPSIS
  Installs the built installer silently, starts the installed app, checks that its window
  opens and stays open, then uninstalls it. Runs in CI on a Windows machine.

  This tests what the unit tests cannot: the real installer, the Start menu shortcut, and the
  packaged app with all its security switches ("fuses") turned on.

.EXAMPLE
  ./scripts/smoke-test-installer.ps1 -Installer '../release/Time Tracker Dev Setup 1.0.0.exe' `
    -ProductName 'Time Tracker Dev' -WindowTitle 'Time Tracker'
#>
param(
  [Parameter(Mandatory)] [string] $Installer,
  [Parameter(Mandatory)] [string] $ProductName,
  [Parameter(Mandatory)] [string] $WindowTitle
)

$ErrorActionPreference = 'Stop'

function Fail([string] $message) {
  Write-Output "::error title=Installer smoke test::$message"
  exit 1
}

Write-Output "Installing $Installer (silent, per user)..."
$setup = Start-Process -FilePath $Installer -ArgumentList '/S' -Wait -PassThru
if ($setup.ExitCode -ne 0) { Fail "The installer exited with code $($setup.ExitCode)." }

$exe = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Programs') -Recurse -Filter "$ProductName.exe" -ErrorAction SilentlyContinue |
  Select-Object -First 1
if (-not $exe) { Fail "'$ProductName.exe' was not installed under %LOCALAPPDATA%\Programs." }
Write-Output "Installed: $($exe.FullName)"

$shortcut = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\$ProductName.lnk"
if (-not (Test-Path $shortcut)) { Fail "No Start menu shortcut at $shortcut." }

Write-Output 'Starting the installed app...'
$app = Start-Process -FilePath $exe.FullName -PassThru
$deadline = (Get-Date).AddSeconds(60)
$title = ''
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 1
  $app.Refresh()
  if ($app.HasExited) { Fail "The app quit right after starting (exit code $($app.ExitCode))." }
  # The window title comes from the UI's index.html, so this proves the page loaded.
  $title = (Get-Process -Id $app.Id).MainWindowTitle
  if ($title -eq $WindowTitle) { break }
}
if ($title -ne $WindowTitle) { Fail "Expected a window titled '$WindowTitle', saw '$title'." }
Write-Output "Window opened: '$title'"

Start-Sleep -Seconds 5
$app.Refresh()
if ($app.HasExited) { Fail 'The app crashed a few seconds after opening.' }

Write-Output 'Closing the app...'
Stop-Process -Id $app.Id
Get-Process -Name $ProductName -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

Write-Output 'Uninstalling...'
$uninstaller = Join-Path $exe.DirectoryName "Uninstall $ProductName.exe"
if (-not (Test-Path $uninstaller)) { Fail "No uninstaller at $uninstaller." }
$removal = Start-Process -FilePath $uninstaller -ArgumentList '/S' -Wait -PassThru
if ($removal.ExitCode -ne 0) { Fail "The uninstaller exited with code $($removal.ExitCode)." }
# The NSIS uninstaller finishes in a copy of itself; give it a moment.
for ($i = 0; $i -lt 30 -and (Test-Path $exe.FullName); $i++) { Start-Sleep -Seconds 1 }
if (Test-Path $exe.FullName) { Fail 'The app was still there after uninstalling.' }

Write-Output 'Installer smoke test passed.'
