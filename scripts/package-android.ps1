param(
    [string]$BuildOutputDirectory = (Join-Path $PSScriptRoot '..\android\app\build\outputs\apk\debug'),
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\artifacts')
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$buildDirectory = (Resolve-Path -LiteralPath $BuildOutputDirectory).Path
$metadata = Get-Content -LiteralPath (Join-Path $buildDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.variantName -ne 'debug' -or @($metadata.elements).Count -ne 1) {
    throw 'Expected one Android debug APK in output-metadata.json.'
}

$element = $metadata.elements[0]
$version = [string]$element.versionName
if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
    throw 'APK versionName contains characters that are unsafe for a filename.'
}

$gradleFile = Join-Path $repoRoot 'android\app\build.gradle.kts'
$gradleVersion = [regex]::Match(
    (Get-Content -LiteralPath $gradleFile -Raw),
    'versionName\s*=\s*"([^"]+)"'
).Groups[1].Value
if ($gradleVersion -ne $version) {
    throw "Build version $version differs from project version $gradleVersion. Rebuild the APK first."
}

$source = (Resolve-Path -LiteralPath (Join-Path $buildDirectory $element.outputFile)).Path
$destinationDirectory = New-Item -ItemType Directory -Path $ArtifactDirectory -Force
$destination = Join-Path $destinationDirectory.FullName "RideMesh-Android-v$version-debug.apk"
Copy-Item -LiteralPath $source -Destination $destination -Force
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
Write-Output "APK: $destination"
Write-Output "SHA-256: $hash"
