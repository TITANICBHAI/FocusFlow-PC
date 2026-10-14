$ErrorActionPreference = "Stop"

$versionLine = Get-Content "gradle.properties" |
    Select-String '^\s*appVersion\s*=\s*([^\s#]+)(?:\s*#.*)?$' |
    Select-Object -First 1
if (-not $versionLine) {
    throw "Could not find appVersion in gradle.properties"
}

$appVersion = $versionLine.Matches[0].Groups[1].Value
if ($appVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid appVersion '$appVersion'; expected MAJOR.MINOR.PATCH"
}

$runNumber = $env:GITHUB_RUN_NUMBER
if ($runNumber -notmatch '^\d+$' -or [int]$runNumber -lt 1 -or [int]$runNumber -gt 65535) {
    throw "GITHUB_RUN_NUMBER must be between 1 and 65535 to form the MSIX revision"
}

# Keep each debug workflow build upgradeable without matching the Store revision.
$packageVersion = "$appVersion.$runNumber"
$identityName = "TBTechs.FocusFlowDebug"
$publisher = "CN=TBTechs.FocusFlowDebug"
$displayName = "FocusFlow Debug"

$appDir = "build\compose\binaries\main\app\FocusFlow"
$stageDir = "build\debug-msix-stage"
$outputDir = "build\debug-msix"
$msixName = "FocusFlow-Debug-$packageVersion-x64.msix"
$certificateName = "FocusFlow-Debug-$packageVersion.cer"
$msixPath = Join-Path $outputDir $msixName
$certificatePath = Join-Path $outputDir $certificateName

if (-not (Test-Path $appDir)) {
    throw "Windows distributable not found at '$appDir'"
}

$logoSource = "src\main\resources\focusflow_256.png"
if (-not (Test-Path $logoSource)) {
    throw "Source logo not found at '$logoSource'"
}

if (Test-Path $stageDir) {
    Remove-Item -Recurse -Force $stageDir
}
if (Test-Path $outputDir) {
    Remove-Item -Recurse -Force $outputDir
}
New-Item -ItemType Directory -Force -Path "$stageDir\Assets" | Out-Null
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
Copy-Item -Path "$appDir\*" -Destination $stageDir -Recurse -Force

$manifestXml = @"
<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
         xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
         xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities">
  <Identity Name="$identityName"
            Version="$packageVersion"
            Publisher="$publisher"
            ProcessorArchitecture="x64" />
  <Properties>
    <DisplayName>$displayName</DisplayName>
    <PublisherDisplayName>TBTechs (Debug)</PublisherDisplayName>
    <Logo>Assets\Square150x150Logo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop"
                        MinVersion="10.0.17763.0"
                        MaxVersionTested="10.0.19041.0" />
  </Dependencies>
  <Resources>
    <Resource Language="en-us" />
  </Resources>
  <Applications>
    <Application Id="FocusFlowDebug"
                 Executable="FocusFlow.exe"
                 EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements
        DisplayName="$displayName"
        Description="FocusFlow debug build for local testing"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\Square150x150Logo.png"
        Square44x44Logo="Assets\Square44x44Logo.png">
      </uap:VisualElements>
    </Application>
  </Applications>
  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>
</Package>
"@

$manifestPath = Join-Path $stageDir "AppxManifest.xml"
[System.IO.File]::WriteAllText($manifestPath, $manifestXml, [System.Text.Encoding]::UTF8)

$writtenManifest = [System.IO.File]::ReadAllText($manifestPath)
if ($writtenManifest -notmatch 'Name="TBTechs\.FocusFlowDebug"') {
    throw "Debug package identity validation failed"
}
if ($writtenManifest -notmatch 'Publisher="CN=TBTechs\.FocusFlowDebug"') {
    throw "Debug publisher validation failed"
}
if ($writtenManifest -notmatch 'DisplayName>FocusFlow Debug<') {
    throw "Debug display name validation failed"
}

Add-Type -AssemblyName System.Drawing
function Save-Logo([string]$path, [int]$width, [int]$height) {
    $source = [System.Drawing.Image]::FromFile((Resolve-Path $logoSource).Path)
    try {
        $bitmap = New-Object System.Drawing.Bitmap(
            $width,
            $height,
            [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
        )
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.DrawImage($source, 0, 0, $width, $height)
            } finally {
                $graphics.Dispose()
            }
            $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $bitmap.Dispose()
        }
    } finally {
        $source.Dispose()
    }
}

Save-Logo "$stageDir\Assets\Square150x150Logo.png" 150 150
Save-Logo "$stageDir\Assets\Square44x44Logo.png" 44 44
Save-Logo "$stageDir\Assets\Square30x30Logo.png" 30 30
Save-Logo "$stageDir\Assets\Square71x71Logo.png" 71 71
Save-Logo "$stageDir\Assets\Square310x310Logo.png" 310 310
Save-Logo "$stageDir\Assets\Wide310x150Logo.png" 310 150
Save-Logo "$stageDir\Assets\StoreLogo.png" 50 50

$sdkRoot = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
$makeAppx = Get-ChildItem "$sdkRoot\*\x64\makeappx.exe" -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Directory.Parent.Name } -Descending |
    Select-Object -First 1
$signTool = Get-ChildItem "$sdkRoot\*\x64\signtool.exe" -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Directory.Parent.Name } -Descending |
    Select-Object -First 1

if ($null -eq $makeAppx) {
    throw "makeappx.exe was not found in the Windows SDK"
}
if ($null -eq $signTool) {
    throw "signtool.exe was not found in the Windows SDK"
}

& $makeAppx.FullName pack /d $stageDir /p $msixPath /nv /o
if ($LASTEXITCODE -ne 0) {
    throw "makeappx failed with exit code $LASTEXITCODE"
}

$certificate = $null
try {
    $certificate = New-SelfSignedCertificate `
        -Subject $publisher `
        -Type CodeSigningCert `
        -CertStoreLocation "Cert:\CurrentUser\My" `
        -KeyAlgorithm RSA `
        -KeyLength 2048 `
        -HashAlgorithm SHA256 `
        -KeyExportPolicy NonExportable `
        -NotAfter (Get-Date).AddYears(1)

    Export-Certificate -Cert $certificate -FilePath $certificatePath -Type CERT | Out-Null

    & $signTool.FullName sign /fd SHA256 /sha1 $certificate.Thumbprint /v $msixPath
    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed with exit code $LASTEXITCODE"
    }
} finally {
    if ($null -ne $certificate) {
        Remove-Item "Cert:\CurrentUser\My\$($certificate.Thumbprint)" `
            -DeleteKey -ErrorAction SilentlyContinue
    }
}

$installGuide = @"
FocusFlow Debug MSIX — version $packageVersion

This is a self-signed local test package. It is separate from the Microsoft Store
package and is not for Store submission. The signing certificate is generated
for this workflow run; only its public certificate is included here.

Install:
1. Extract this artifact.
2. Open PowerShell as Administrator and run:
   Import-Certificate -FilePath ".\$certificateName" -CertStoreLocation "Cert:\LocalMachine\TrustedPeople"
3. Close the elevated shell. In PowerShell as your normal Windows user, run:
   Add-AppxPackage -Path ".\$msixName"

If Windows policy blocks sideloading, enable sideloading or Developer Mode first.
To remove the app, run:
   Get-AppxPackage -Name "$identityName" | Remove-AppxPackage

Important: the debug package uses a separate Windows package identity, but the app
currently shares the same %USERPROFILE%\.focusflow database and single-instance
lock as the regular app. Close the regular app before testing and back up its data.
Remove the test certificate from Local Machine\TrustedPeople after uninstalling
when you no longer need this debug build.
"@
[System.IO.File]::WriteAllText(
    (Join-Path $outputDir "INSTALL.txt"),
    $installGuide,
    [System.Text.Encoding]::UTF8
)

Write-Host "Created self-signed debug package: $msixPath"
Write-Host "Created public test certificate: $certificatePath"
