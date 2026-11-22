<#
.SYNOPSIS
    Gradle-free APK build for a plain-Java Android project (Windows PowerShell 5.1).

.DESCRIPTION
    Pipeline: aapt2 compile -> aapt2 link -> javac -> d8 -> add classes*.dex
    -> zipalign -> apksigner sign -> apksigner verify -> aapt2 dump badging.

    Expected layout under -ProjectDir:
        AndroidManifest.xml
        res\...              (optional)
        src\**\*.java

    Output:        <ProjectDir>\out\<OutName>.apk
    Intermediates: <ProjectDir>\build\   (wiped at the start of every build)

    Never talks to a device; it only produces the APK.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File build.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File build.ps1 -ProjectDir C:\work\app -OutName app -VersionCode 2 -VersionName 1.1
#>
[CmdletBinding()]
param(
    [string]$ProjectDir = '',
    [string]$OutName = 'drivehome',
    [int]$VersionCode = 1,
    [string]$VersionName = '1.0',
    [int]$MinSdk = 29,
    [int]$TargetSdk = 29,
    [string]$JavaRelease = '11',
    [string]$JdkHome = 'C:\Program Files\Android\Android Studio1\jbr',
    [string]$SdkDir = '',
    [string]$BuildToolsVersion = '35.0.0',
    [string]$Platform = 'android-35',
    [string]$KeyStore = '',
    [string]$KeyAlias = 'androiddebugkey',
    [string]$StorePass = 'android',
    [string]$KeyPass = 'android'
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- helpers ---

$script:StepNo = 0
$script:StepTotal = 9

function Write-Step([string]$Title) {
    $script:StepNo++
    Write-Host ''
    Write-Host ("[{0}/{1}] {2}" -f $script:StepNo, $script:StepTotal, $Title) -ForegroundColor Cyan
}

function Assert-Exists([string]$Path, [string]$What) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$What not found: $Path"
    }
}

# Runs a native program, streams its stdout+stderr, and throws on a non-zero
# exit code. stderr is merged with EAP=Continue so that warnings written to
# stderr (javac/d8/apksigner do this) are not turned into terminating errors
# by PowerShell 5.1. With -Capture the output is returned instead of echoed.
function Invoke-Tool {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Exe,
        [string[]]$ArgList = @(),
        [switch]$Capture
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = -1
    try {
        & $Exe @ArgList 2>&1 | ForEach-Object {
            # stderr lines arrive as ErrorRecords; the raw text is TargetObject
            # (ToString() of an empty one prints "...RemoteException").
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $line = [string]$_.TargetObject }
            else { $line = "$_" }
            $lines.Add($line)
            if (-not $Capture) { Write-Host "    $line" }
        }
        $code = $global:LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($code -ne 0) {
        if ($Capture) { foreach ($l in $lines) { Write-Host "    $l" } }
        $shown = ($ArgList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
        throw ("{0} failed with exit code {1}.`n    Command: `"{2}`" {3}" -f $Name, $code, $Exe, $shown)
    }
    if ($Capture) { return , $lines.ToArray() }
}

# ------------------------------------------------------------ resolve paths ---

$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:PATH
$sw = [System.Diagnostics.Stopwatch]::StartNew()

try {
    if (-not $ProjectDir) { $ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
    Assert-Exists $ProjectDir 'Project directory'
    $ProjectDir = (Resolve-Path -LiteralPath $ProjectDir).ProviderPath.TrimEnd('\')

    if (-not $SdkDir) {
        if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) { $SdkDir = $env:ANDROID_HOME }
        else { $SdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    }
    # Signing key resolution (first match wins):
    #   1. -KeyStore parameter
    #   2. env DRIVEHOME_KEYSTORE (+ DRIVEHOME_STORE_PASS / DRIVEHOME_KEY_ALIAS / DRIVEHOME_KEY_PASS)  -> CI
    #   3. %USERPROFILE%\.android\drivehome-release.jks + drivehome-release.properties (outside the repo)
    #   4. the Android debug keystore
    # Every build installed on the car must use the SAME key, or Android refuses the update.
    if (-not $KeyStore) {
        $relJks   = Join-Path $env:USERPROFILE '.android\drivehome-release.jks'
        $relProps = Join-Path $env:USERPROFILE '.android\drivehome-release.properties'
        if ($env:DRIVEHOME_KEYSTORE -and (Test-Path -LiteralPath $env:DRIVEHOME_KEYSTORE)) {
            $KeyStore = $env:DRIVEHOME_KEYSTORE
            if ($env:DRIVEHOME_STORE_PASS) { $StorePass = $env:DRIVEHOME_STORE_PASS; $KeyPass = $env:DRIVEHOME_STORE_PASS }
            if ($env:DRIVEHOME_KEY_PASS)   { $KeyPass = $env:DRIVEHOME_KEY_PASS }
            if ($env:DRIVEHOME_KEY_ALIAS)  { $KeyAlias = $env:DRIVEHOME_KEY_ALIAS } else { $KeyAlias = 'drivehome' }
        } elseif ((Test-Path -LiteralPath $relJks) -and (Test-Path -LiteralPath $relProps)) {
            $KeyStore = $relJks; $KeyAlias = 'drivehome'
            foreach ($line in Get-Content -LiteralPath $relProps) {
                if ($line -match '^\s*storePassword\s*=\s*(.+)$') { $StorePass = $Matches[1].Trim(); $KeyPass = $StorePass }
                if ($line -match '^\s*keyPassword\s*=\s*(.+)$')   { $KeyPass = $Matches[1].Trim() }
                if ($line -match '^\s*keyAlias\s*=\s*(.+)$')      { $KeyAlias = $Matches[1].Trim() }
            }
        } else {
            $KeyStore = Join-Path $env:USERPROFILE '.android\debug.keystore'
        }
    }
    Write-Host "Signing with: $KeyStore (alias $KeyAlias)"

    if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
        if ((-not $PSBoundParameters.ContainsKey('JdkHome')) -and $env:JAVA_HOME -and
            (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
            $JdkHome = $env:JAVA_HOME
        } else {
            throw "JDK not found at '$JdkHome' (need bin\javac.exe). Pass -JdkHome."
        }
    }
    $JdkHome = $JdkHome.TrimEnd('\')

    # d8.bat / apksigner.bat look for JAVA_HOME or java on PATH. We call their
    # jars with java.exe directly (avoids cmd.exe re-parsing arguments that
    # contain & % ^ ! or parentheses), but set both anyway for consistency.
    $env:JAVA_HOME = $JdkHome
    $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH

    # JDK 22+ warns when apksigner's bundled conscrypt calls System.loadLibrary
    # (and will block it in future); grant native access up front.
    $jdkMajor = 0
    $releaseFile = Join-Path $JdkHome 'release'
    if (Test-Path -LiteralPath $releaseFile) {
        $m = Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="(1\.)?(\d+)' | Select-Object -First 1
        if ($m) { $jdkMajor = [int]$m.Matches[0].Groups[2].Value }
    }
    $javaToolOpts = @('-Xmx1024M', '-Xss1m')
    if ($jdkMajor -ge 22) { $javaToolOpts += '--enable-native-access=ALL-UNNAMED' }

    $javac   = Join-Path $JdkHome 'bin\javac.exe'
    $java    = Join-Path $JdkHome 'bin\java.exe'
    $jarExe  = Join-Path $JdkHome 'bin\jar.exe'
    $bt      = Join-Path $SdkDir "build-tools\$BuildToolsVersion"
    $aapt2   = Join-Path $bt 'aapt2.exe'
    $zipalign = Join-Path $bt 'zipalign.exe'
    $d8Jar   = Join-Path $bt 'lib\d8.jar'
    $signJar = Join-Path $bt 'lib\apksigner.jar'
    $androidJar = Join-Path $SdkDir "platforms\$Platform\android.jar"

    $manifest = Join-Path $ProjectDir 'AndroidManifest.xml'
    $resDir   = Join-Path $ProjectDir 'res'
    $srcDir   = Join-Path $ProjectDir 'src'
    $buildDir = Join-Path $ProjectDir 'build'
    $outDir   = Join-Path $ProjectDir 'out'

    foreach ($p in @(
            @($javac, 'javac'), @($java, 'java'), @($jarExe, 'jar'),
            @($aapt2, 'aapt2'), @($zipalign, 'zipalign'),
            @($d8Jar, 'd8.jar'), @($signJar, 'apksigner.jar'),
            @($androidJar, 'android.jar'), @($KeyStore, 'Signing keystore'),
            @($manifest, 'AndroidManifest.xml'), @($srcDir, 'src directory'))) {
        Assert-Exists $p[0] $p[1]
    }

    $genDir     = Join-Path $buildDir 'gen'
    $classesDir = Join-Path $buildDir 'classes'
    $dexDir     = Join-Path $buildDir 'dex'
    $resZip     = Join-Path $buildDir 'res.zip'
    $baseApk    = Join-Path $buildDir 'base.apk'
    $unaligned  = Join-Path $buildDir 'unaligned.apk'
    $aligned    = Join-Path $buildDir 'aligned.apk'
    $classesJar = Join-Path $buildDir 'classes.jar'
    $srcList    = Join-Path $buildDir 'sources.txt'
    $finalApk   = Join-Path $outDir "$OutName.apk"

    Write-Host "Project : $ProjectDir"
    Write-Host "JDK     : $JdkHome"
    Write-Host "SDK     : $SdkDir (build-tools $BuildToolsVersion, $Platform)"
    Write-Host "Version : code=$VersionCode name=$VersionName minSdk=$MinSdk targetSdk=$TargetSdk"

    if (Test-Path -LiteralPath $buildDir) { Remove-Item -LiteralPath $buildDir -Recurse -Force }
    foreach ($d in @($buildDir, $genDir, $classesDir, $dexDir, $outDir)) {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
    foreach ($f in @($finalApk, "$finalApk.idsig")) {
        if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force }
    }

    # ------------------------------------------------------------ 1. compile ---
    Write-Step 'aapt2 compile (resources)'
    $hasRes = (Test-Path -LiteralPath $resDir) -and
              (@(Get-ChildItem -LiteralPath $resDir -Recurse -File).Count -gt 0)
    if ($hasRes) {
        Invoke-Tool 'aapt2 compile' $aapt2 @('compile', '--dir', $resDir, '-o', $resZip)
        Assert-Exists $resZip 'Compiled resources'
        Write-Host "    -> $resZip"
    } else {
        Write-Host '    (no res\ files; skipping)'
    }

    # --------------------------------------------------------------- 2. link ---
    Write-Step 'aapt2 link (manifest + resources -> base.apk, R.java)'
    $linkArgs = @('link', '-o', $baseApk,
        '-I', $androidJar,
        '--manifest', $manifest,
        '--java', $genDir,
        '--min-sdk-version', "$MinSdk",
        '--target-sdk-version', "$TargetSdk",
        '--version-code', "$VersionCode",
        '--version-name', $VersionName,
        '--auto-add-overlay')
    # Version values are only *defaults* for aapt2; force them when they were
    # given explicitly so the command line wins over the manifest.
    if ($PSBoundParameters.ContainsKey('VersionCode') -or $PSBoundParameters.ContainsKey('VersionName')) {
        $linkArgs += '--replace-version'
    }
    if ($hasRes) { $linkArgs += $resZip }
    Invoke-Tool 'aapt2 link' $aapt2 $linkArgs
    Assert-Exists $baseApk 'Linked base APK'

    # -------------------------------------------------------------- 3. javac ---
    Write-Step "javac --release $JavaRelease"
    $javaFiles = @(Get-ChildItem -LiteralPath $srcDir -Recurse -File -Filter '*.java') +
                 @(Get-ChildItem -LiteralPath $genDir -Recurse -File -Filter '*.java')
    $userSrcCount = @(Get-ChildItem -LiteralPath $srcDir -Recurse -File -Filter '*.java').Count
    if ($userSrcCount -eq 0) { throw "No .java files under $srcDir" }
    # Argument file: avoids command-line length limits and any quoting of
    # odd characters. Forward slashes because backslash is an escape inside
    # quoted tokens of javac @files. UTF-8 without BOM.
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $lines = [string[]]($javaFiles | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' })
    [System.IO.File]::WriteAllLines($srcList, $lines, $utf8NoBom)
    Write-Host ("    {0} source file(s) ({1} from src, {2} generated)" -f $javaFiles.Count, $userSrcCount, ($javaFiles.Count - $userSrcCount))
    Invoke-Tool 'javac' $javac @('--release', $JavaRelease,
        '-encoding', 'UTF-8',
        '-Xlint:-options',
        '-cp', $androidJar,
        '-d', $classesDir,
        "@$srcList")
    $classFiles = @(Get-ChildItem -LiteralPath $classesDir -Recurse -File -Filter '*.class')
    if ($classFiles.Count -eq 0) { throw 'javac produced no .class files' }
    Write-Host "    $($classFiles.Count) class file(s)"

    # ----------------------------------------------------------------- 4. d8 ---
    Write-Step 'd8 (classes -> dex)'
    # Hand d8 a single jar instead of individual Foo$1.class paths.
    Invoke-Tool 'jar' $jarExe @('--create', '--no-manifest', '--file', $classesJar, '-C', $classesDir, '.')
    Invoke-Tool 'd8' $java ($javaToolOpts + @('-cp', $d8Jar, 'com.android.tools.r8.D8',
        '--release',
        '--min-api', "$MinSdk",
        '--lib', $androidJar,
        '--output', $dexDir,
        $classesJar))
    $dexFiles = @(Get-ChildItem -LiteralPath $dexDir -File -Filter 'classes*.dex' | Sort-Object Name)
    if ($dexFiles.Count -eq 0) { throw "d8 produced no classes*.dex in $dexDir" }
    foreach ($dx in $dexFiles) { Write-Host ("    {0} ({1:N0} bytes)" -f $dx.Name, $dx.Length) }

    # ---------------------------------------------------------- 5. add dex ---
    Write-Step 'Add dex to APK'
    # ZipArchive in Update mode copies untouched entries byte-for-byte (same
    # compression method), so resources.arsc etc. keep aapt2's storage.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Copy-Item -LiteralPath $baseApk -Destination $unaligned -Force
    $zip = [System.IO.Compression.ZipFile]::Open($unaligned, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($dx in $dexFiles) {
            $old = $zip.GetEntry($dx.Name)
            if ($old) { $old.Delete() }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $dx.FullName, $dx.Name, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally {
        $zip.Dispose()
    }
    $check = [System.IO.Compression.ZipFile]::OpenRead($unaligned)
    try {
        $names = @($check.Entries | ForEach-Object { $_.FullName })
    } finally {
        $check.Dispose()
    }
    foreach ($dx in $dexFiles) {
        if ($names -notcontains $dx.Name) { throw "$($dx.Name) missing from $unaligned after adding" }
    }
    Write-Host "    $($names.Count) entries: $($names -join ', ')"

    # ---------------------------------------------------------- 6. zipalign ---
    Write-Step 'zipalign -p -f 4'
    Invoke-Tool 'zipalign' $zipalign @('-p', '-f', '4', $unaligned, $aligned)
    Invoke-Tool 'zipalign check' $zipalign @('-c', '-p', '4', $aligned)

    # -------------------------------------------------------------- 7. sign ---
    Write-Step 'apksigner sign (debug keystore)'
    Invoke-Tool 'apksigner sign' $java ($javaToolOpts + @('-jar', $signJar, 'sign',
        '--ks', $KeyStore,
        '--ks-key-alias', $KeyAlias,
        '--ks-pass', "pass:$StorePass",
        '--key-pass', "pass:$KeyPass",
        '--v4-signing-enabled', 'false',
        '--out', $finalApk,
        $aligned))
    Assert-Exists $finalApk 'Signed APK'

    # ------------------------------------------------------------ 8. verify ---
    Write-Step 'apksigner verify --print-certs'
    # (For minSdk >= 28 verify only reports the v3 check as "true"; the v2
    #  block is still present - apksigner verify --min-sdk-version 24 shows it.)
    Invoke-Tool 'apksigner verify' $java ($javaToolOpts + @('-jar', $signJar, 'verify',
        '--verbose', '--print-certs', $finalApk))

    # ----------------------------------------------------------- 9. badging ---
    Write-Step 'aapt2 dump badging'
    $badging = Invoke-Tool 'aapt2 dump badging' $aapt2 @('dump', 'badging', $finalApk) -Capture
    $wanted = @($badging | Where-Object { $_ -match '^(package:|sdkVersion:|targetSdkVersion:|minSdkVersion:|application-label:|launchable-activity:)' })
    foreach ($l in $wanted) { Write-Host "    $l" }
    if (-not ($wanted | Where-Object { $_ -like 'launchable-activity:*' })) {
        Write-Host '    WARNING: no launchable-activity (no MAIN/LAUNCHER activity in the manifest?)' -ForegroundColor Yellow
    }

    $sw.Stop()
    $size = (Get-Item -LiteralPath $finalApk).Length
    Write-Host ''
    Write-Host ("BUILD OK in {0:N1}s" -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green
    Write-Host ("APK : {0}" -f $finalApk) -ForegroundColor Green
    Write-Host ("Size: {0:N0} bytes ({1:N1} KB)" -f $size, ($size / 1KB)) -ForegroundColor Green
    $exitCode = 0
} catch {
    Write-Host ''
    Write-Host "BUILD FAILED: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:PATH = $savedPath
}
exit $exitCode
