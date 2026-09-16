param([string]$SdkPath = '', [string]$JavaPath = '')
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectPath
$buildPath = $projectPath
if ($projectPath -match '[^\x00-\x7F]') {
    $buildPath = Join-Path (Split-Path $projectPath -Parent) 'tomato-shuffle-build'
    if (Test-Path -LiteralPath $buildPath) {
        $aliasItem = Get-Item -LiteralPath $buildPath
        if ($aliasItem.LinkType -ne 'Junction' -or $aliasItem.Target -ne $projectPath) {
            throw "Build alias already exists with a different target: $buildPath"
        }
    } else { New-Item -ItemType Junction -Path $buildPath -Target $projectPath | Out-Null }
    if ($buildPath -match '[^\x00-\x7F]') { throw 'Use an ASCII parent directory for the Windows build entry.' }
}
if ($JavaPath) { $env:JAVA_HOME = $JavaPath }
if (-not $env:JAVA_HOME) {
    $candidates = @('C:\Program Files\Android\Android Studio\jbr', 'D:\IntelliJ IDEA 2024.3.5\jbr')
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath "$candidate\bin\java.exe") { $env:JAVA_HOME = $candidate; break }
    }
}
if (-not $SdkPath) {
    if ($env:ANDROID_HOME) { $SdkPath = $env:ANDROID_HOME }
    elseif (Test-Path -LiteralPath "$buildPath\.tools\android-sdk") { $SdkPath = "$buildPath\.tools\android-sdk" }
    else { $SdkPath = "$env:LOCALAPPDATA\Android\Sdk" }
}
if (-not (Test-Path -LiteralPath "$SdkPath\platforms\android-35\android.jar")) {
    throw 'Please install Android SDK platform 35 and build-tools 35.0.0, then pass -SdkPath.'
}
$sdkProperty = $SdkPath.Replace('\', '/')
$escaped = -join ($sdkProperty.ToCharArray() | ForEach-Object {
    if ([int]$_ -gt 127) { '\u{0:x4}' -f [int]$_ } else { [string]$_ }
})
Set-Content -LiteralPath 'local.properties' -Value "sdk.dir=$escaped" -Encoding ascii
$gradleCommand = "$buildPath\.tools\gradle-8.11.1\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradleCommand)) { $gradleCommand = "$buildPath\gradlew.bat" }
& $gradleCommand -p $buildPath :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination 'dist\TomatoShuffle-0.1.0-debug.apk'
Get-FileHash -LiteralPath 'dist\TomatoShuffle-0.1.0-debug.apk' -Algorithm SHA256
