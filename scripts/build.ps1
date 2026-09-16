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
    if (Get-Command java -ErrorAction SilentlyContinue) {
        $javaSettings = & java -XshowSettings:properties -version 2>&1 | Out-String
        if ($javaSettings -match 'java.home\s*=\s*([^\r\n]+)') { $env:JAVA_HOME = $Matches[1].Trim() }
    }
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/jlink.exe")) {
    throw 'A full JDK 21 (including jlink) is required. Set JAVA_HOME or pass -JavaPath.'
}
if (-not $SdkPath) {
    if ($env:ANDROID_HOME) { $SdkPath = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $SdkPath = $env:ANDROID_SDK_ROOT }
    elseif (Test-Path -LiteralPath "$buildPath/.tools/android-sdk") { $SdkPath = "$buildPath/.tools/android-sdk" }
    else { $SdkPath = "$env:LOCALAPPDATA/Android/Sdk" }
}
if (-not (Test-Path -LiteralPath "$SdkPath/platforms/android-35/android.jar")) {
    throw 'Please install Android SDK platform 35 and build-tools 35.0.0, then pass -SdkPath.'
}
$sdkProperty = $SdkPath.Replace('\', '/')
$escaped = -join ($sdkProperty.ToCharArray() | ForEach-Object {
    if ([int]$_ -gt 127) { '\u{0:x4}' -f [int]$_ } else { [string]$_ }
})
Set-Content -LiteralPath 'local.properties' -Value "sdk.dir=$escaped" -Encoding ascii
& "$buildPath/gradlew.bat" -p $buildPath :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$metadata = Get-Content -LiteralPath 'app/build/outputs/apk/debug/output-metadata.json' -Raw | ConvertFrom-Json
$version = $metadata.elements[0].versionName
New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
$artifact = "dist/LjunTomatoShuffle-$version-debug.apk"
Copy-Item -LiteralPath 'app/build/outputs/apk/debug/app-debug.apk' -Destination $artifact
Get-FileHash -LiteralPath $artifact -Algorithm SHA256
