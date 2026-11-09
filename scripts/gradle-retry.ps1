<#
.SYNOPSIS
  Runs a Gradle command and runs it again, but only when it failed because a download was
  refused for a moment.

.DESCRIPTION
  The packaging steps download things from github.com while they run (the WiX toolset that
  Compose Desktop fetches is one). GitHub now and then answers those downloads with HTTP 502 or
  503 for a few minutes, which used to fail the whole job on the first try. Gradle keeps the tasks
  that already finished, so a second try only repeats the download.

  Anything else (a compile error, a failing test, a missing file) is NOT retried: it fails once,
  straight away, with Gradle's own exit code.

  The command is passed as a plain string so the workflow still shows it as written, which
  InstallerUninstallCheckWiringTest looks for in build-windows.yml. It is split on spaces and run
  directly, so arguments must not contain spaces. PowerShell must not parse the line itself: it
  splits an argument such as -Pfocusflow.guardedInstaller=true at the dot.

.EXAMPLE
  ./scripts/gradle-retry.ps1 -CommandLine 'gradle packageExe --no-daemon --stacktrace'
#>
param(
    [Parameter(Mandatory = $true)]
    [string] $CommandLine,

    [int] $MaxAttempts = 3,

    # Seconds to wait before attempt 2; attempt 3 waits twice as long. Tests set it to 0.
    [int] $DelaySeconds = $(if ($env:GRADLE_RETRY_DELAY_SECONDS) { [int]$env:GRADLE_RETRY_DELAY_SECONDS } else { 30 })
)

$ErrorActionPreference = 'Continue'

# What a temporary download problem looks like in Gradle's output.
$temporary = 'HTTP status code: 50[0-9]|Received status code 50[0-9]|Bad Gateway|Service Unavailable|Gateway Time-?out|Connection reset|Connection timed out|Read timed out|Premature end of|SSLHandshakeException|UnknownHostException|Temporary failure in name resolution'

$parts = @($CommandLine.Trim() -split '\s+')
$exe = $parts[0]
$exeArgs = @()
if ($parts.Count -gt 1) { $exeArgs = $parts[1..($parts.Count - 1)] }

$code = 1
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    $log = [System.IO.Path]::GetTempFileName()
    $hit = $null
    try {
        # A stale exit code from an earlier command must never be mistaken for this one's.
        $global:LASTEXITCODE = $null
        & $exe @exeArgs 2>&1 | Tee-Object -FilePath $log
        $code = $LASTEXITCODE
        if ($null -eq $code) { $code = 1 }
        if ($code -ne 0) {
            $hit = Select-String -Path $log -Pattern $temporary -List | Select-Object -First 1
        }
    }
    finally {
        Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    }

    if ($code -eq 0) { break }
    if ($null -eq $hit -or $attempt -eq $MaxAttempts) { break }

    $wait = $DelaySeconds * $attempt
    $what = $hit.Line.Trim()
    if ($what.Length -gt 200) { $what = $what.Substring(0, 200) }
    Write-Host "::warning::Gradle failed with what looks like a temporary download problem ($what). Trying again in $wait seconds (attempt $($attempt + 1) of $MaxAttempts)."
    Start-Sleep -Seconds $wait
}

exit $code
