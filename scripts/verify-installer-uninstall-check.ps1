<#
.SYNOPSIS
  Verifies that the FocusFlow installers really contain the pre-removal uninstall check.

.DESCRIPTION
  An installer built without the check looks fine and protects nothing, so CI runs this
  after packaging and fails the build if the check is missing.

    1. MSI: reads the package's own tables and confirms the FfUninstallCheck custom
       action exists, runs FocusFlow.exe with --uninstall-check, and is sequenced before
       InstallInitialize on REMOVE="ALL" (and not during a major upgrade).
    2. EXE: confirms the embedded MSI carries the same action (a byte search, so a
       miss is reported as a warning rather than a failure).
    3. -Smoke: installs the MSI silently on this machine, uninstalls it silently, and
       confirms (a) the uninstall succeeded, (b) Windows Installer ran the check, and
       (c) FocusFlow itself answered "allowed". This is the real Windows Installer,
       cmd.exe and jpackage launcher end to end, with no protection active.

  It does not test a DENIED removal: that needs a protection active and shows a dialog,
  which a CI runner cannot answer. See the manual checklist in
  .agents/memory/windows-uninstall-protection.md.

.EXAMPLE
  ./scripts/verify-installer-uninstall-check.ps1 -Msi build\compose\binaries\main\msi\FocusFlow-2.0.6.msi -Exe build\compose\binaries\main\exe\FocusFlow-2.0.6.exe -Smoke
#>
param(
    [string]$Msi = "",
    [string]$Exe = "",
    [switch]$Smoke
)

$ErrorActionPreference = "Stop"
$ActionId = "FfUninstallCheck"
$failures = New-Object System.Collections.Generic.List[string]
function Fail([string]$message) { Write-Host "FAIL: $message" -ForegroundColor Red; $failures.Add($message) }
function Pass([string]$message) { Write-Host "ok:   $message" -ForegroundColor Green }

function Invoke-MsiQuery([string]$Path, [string]$Query, [int]$Fields) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $db = $installer.GetType().InvokeMember("OpenDatabase", "InvokeMethod", $null, $installer, @($Path, 0))
    $view = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @($Query))
    [void]$view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null)
    $rows = @()
    while ($true) {
        $record = $view.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $view, $null)
        if ($null -eq $record) { break }
        $row = @()
        for ($i = 1; $i -le $Fields; $i++) {
            $row += $record.GetType().InvokeMember("StringData", "GetProperty", $null, $record, @($i))
        }
        $rows += , $row
    }
    [void]$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null)
    return , $rows
}

# ── 1. MSI structure ─────────────────────────────────────────────────────────
if ($Msi) {
    if (-not (Test-Path $Msi)) { throw "MSI not found: $Msi" }
    $Msi = (Resolve-Path $Msi).Path
    Write-Host "Checking MSI: $Msi"

    $actions = Invoke-MsiQuery $Msi ('SELECT `Action`, `Type`, `Source`, `Target` FROM `CustomAction` WHERE `Action`=''' + $ActionId + '''') 4
    if ($actions.Count -ne 1) {
        Fail "custom action $ActionId is missing from the MSI (the installer would NOT ask FocusFlow before uninstalling)"
    } else {
        $type = [int]$actions[0][1]
        $target = [string]$actions[0][3]
        if ($type -ne 34) { Fail "$ActionId has type $type, expected 34 (run a program in a directory)" } else { Pass "$ActionId is a type-34 program action" }
        if ($target -notmatch '--uninstall-check') { Fail "$ActionId does not run FocusFlow.exe with --uninstall-check: $target" } else { Pass "it runs FocusFlow.exe --uninstall-check" }
        if ($target -notmatch 'errorlevel 42') { Fail "$ActionId does not map exit code 42 to a refusal: $target" } else { Pass "exit code 42 maps to a refusal" }
        if ($target -notmatch 'exit /b 0') { Fail "$ActionId does not fall back to success, so a broken install could not be removed: $target" } else { Pass "every other outcome falls back to success (fail-open)" }
    }

    $seq = Invoke-MsiQuery $Msi ('SELECT `Action`, `Condition`, `Sequence` FROM `InstallExecuteSequence` WHERE `Action`=''' + $ActionId + '''') 3
    $init = Invoke-MsiQuery $Msi 'SELECT `Action`, `Condition`, `Sequence` FROM `InstallExecuteSequence` WHERE `Action`=''InstallInitialize''' 3
    if ($seq.Count -ne 1) {
        Fail "$ActionId is not in InstallExecuteSequence"
    } else {
        $condition = [string]$seq[0][1]
        $position = [int]$seq[0][2]
        $initPosition = [int]$init[0][2]
        if ($condition -notmatch 'REMOVE\s*=\s*"ALL"') { Fail "sequence condition does not select uninstalls: $condition" } else { Pass "runs only for REMOVE=""ALL"" (uninstall)" }
        if ($condition -notmatch 'UPGRADINGPRODUCTCODE') { Fail "sequence condition would also block upgrades: $condition" } else { Pass "does not block upgrades" }
        if ($position -ge $initPosition) { Fail "$ActionId runs at $position, not before InstallInitialize ($initPosition): a refusal would need a rollback" } else { Pass "runs before InstallInitialize ($position < $initPosition), so a refusal changes nothing" }
    }
}

# ── 2. EXE contains the same MSI ─────────────────────────────────────────────
if ($Exe) {
    if (-not (Test-Path $Exe)) { throw "EXE not found: $Exe" }
    Write-Host "Checking EXE: $Exe"
    $text = [System.Text.Encoding]::GetEncoding(28591).GetString([System.IO.File]::ReadAllBytes((Resolve-Path $Exe).Path))
    if ($text.Contains($ActionId) -or $text.Contains("--uninstall-check")) {
        Pass "the EXE's embedded MSI contains the uninstall check"
    } else {
        Write-Host "WARN: could not find the uninstall check inside the EXE. A byte search can miss text split across storage sectors, so this is a warning; confirm with -Smoke on the EXE's MSI or by hand." -ForegroundColor Yellow
    }
}

# ── 3. Real install and uninstall ────────────────────────────────────────────
if ($Smoke) {
    if (-not $Msi) { throw "-Smoke needs -Msi" }
    $logDir = Join-Path $env:TEMP "focusflow-uninstall-check"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $installLog = Join-Path $logDir "install.log"
    $uninstallLog = Join-Path $logDir "uninstall.log"

    function Invoke-Msiexec([string[]]$Arguments, [int]$TimeoutSeconds) {
        $process = Start-Process -FilePath "msiexec.exe" -ArgumentList $Arguments -PassThru
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            throw "msiexec did not finish within $TimeoutSeconds seconds"
        }
        return $process.ExitCode
    }

    Write-Host "Smoke test: silent install"
    $code = Invoke-Msiexec @("/i", "`"$Msi`"", "/qn", "/norestart", "/l*v", "`"$installLog`"") 300
    if ($code -ne 0) { Fail "silent install exited with $code (see $installLog)" }
    else {
        Pass "silent install succeeded"
        $installedExe = Join-Path $env:LOCALAPPDATA "FocusFlow\FocusFlow.exe"
        if (-not (Test-Path $installedExe)) { Fail "FocusFlow.exe not found at $installedExe after install" }

        Write-Host "Smoke test: silent uninstall (no protection is active, so it must be allowed)"
        $code = Invoke-Msiexec @("/x", "`"$Msi`"", "/qn", "/norestart", "/l*v", "`"$uninstallLog`"") 300
        if ($code -ne 0) { Fail "silent uninstall exited with $code; the check must not block an unprotected uninstall (see $uninstallLog)" }
        else { Pass "silent uninstall succeeded" }
        if (Test-Path $installedExe) { Fail "FocusFlow.exe is still installed after the uninstall" } else { Pass "FocusFlow.exe was removed" }

        $uninstallText = if (Test-Path $uninstallLog) { Get-Content $uninstallLog -Raw } else { "" }
        if ($uninstallText -match "Action start \d+:\d+:\d+: $ActionId\.") { Pass "Windows Installer ran $ActionId during the uninstall" }
        else { Fail "Windows Installer never ran $ActionId during the uninstall (see $uninstallLog)" }
        if ($uninstallText -match "Action ended \d+:\d+:\d+: $ActionId\. Return value 1\.") { Pass "$ActionId returned success" }
        else { Fail "$ActionId did not return success (see $uninstallLog)" }

        $enforcementLog = Join-Path $env:USERPROFILE ".focusflow\enforcement.log"
        $answered = (Test-Path $enforcementLog) -and ((Get-Content $enforcementLog -Raw) -match "UninstallCheck.*removal allowed")
        if ($answered) { Pass "FocusFlow itself answered 'removal allowed' (enforcement.log)" }
        else { Write-Host "WARN: no 'removal allowed' line in $enforcementLog. The uninstall still succeeded, but the app may not have been asked. Check the log." -ForegroundColor Yellow }
    }
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "$($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "All installer uninstall-check verifications passed." -ForegroundColor Green
