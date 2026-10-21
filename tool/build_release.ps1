param(
    [string]$JavaHome = 'D:\jdk17',
    [string]$FlutterCommand = 'D:\flutter_sdk\bin\flutter.bat'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$properties = @{}
Get-Content -LiteralPath (Join-Path $projectRoot 'android/key.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $properties[$matches[1].Trim()] = $matches[2].Trim() }
}
$sourceStore = $properties['storeFile']
if (!(Test-Path -LiteralPath $sourceStore -PathType Leaf)) { throw 'Configured BKS keystore is missing.' }
$provider = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on') -Filter '*.jar' -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1
if (!$provider) { throw 'A cached Bouncy Castle provider JAR is required to read BKS.' }
$temporaryStore = Join-Path $projectRoot '.signing-temp/rednote-release.p12'
$environmentNames = @('JAVA_HOME', 'JAVA_TOOL_OPTIONS', 'REDNOTE_STORE_FILE', 'REDNOTE_STORE_TYPE', 'REDNOTE_STORE_PASSWORD', 'REDNOTE_KEY_PASSWORD')
$previous = @{}
foreach ($name in $environmentNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    New-Item -ItemType Directory -Force (Split-Path $temporaryStore), (Join-Path $projectRoot 'tmp'), (Join-Path $projectRoot 'build') | Out-Null
    if (Test-Path -LiteralPath $temporaryStore) { Remove-Item -LiteralPath $temporaryStore -Force }
    $env:JAVA_HOME = $JavaHome
    $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$($projectRoot.Replace('\','/'))/tmp -Djava.io.tmpdir=$($projectRoot.Replace('\','/'))/tmp"
    $env:REDNOTE_STORE_PASSWORD = $properties['storePassword']
    $env:REDNOTE_KEY_PASSWORD = $properties['keyPassword']
    $env:REDNOTE_STORE_FILE = $temporaryStore
    $env:REDNOTE_STORE_TYPE = 'PKCS12'
    & (Join-Path $JavaHome 'bin/keytool.exe') -importkeystore -noprompt -srckeystore $sourceStore -srcstoretype BKS -srcalias $properties['keyAlias'] -srcstorepass:env REDNOTE_STORE_PASSWORD -srckeypass:env REDNOTE_KEY_PASSWORD -destkeystore $temporaryStore -deststoretype PKCS12 -deststorepass:env REDNOTE_STORE_PASSWORD -destkeypass:env REDNOTE_KEY_PASSWORD -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath $provider.FullName
    if ($LASTEXITCODE -ne 0) { throw 'BKS key import failed.' }
    & (Join-Path $JavaHome 'bin/keytool.exe') -exportcert -keystore $sourceStore -storetype BKS -alias $properties['keyAlias'] -storepass:env REDNOTE_STORE_PASSWORD -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath $provider.FullName -file (Join-Path $projectRoot 'build/bks-signer.der')
    if ($LASTEXITCODE -ne 0) { throw 'BKS certificate export failed.' }
    Push-Location $projectRoot
    try {
        $flutterRoot = Split-Path -Parent (Split-Path -Parent $FlutterCommand)
        $dart = Join-Path $flutterRoot 'bin/cache/dart-sdk/bin/dart.exe'
        $flutterTools = Join-Path $flutterRoot 'packages/flutter_tools'
        $snapshot = Join-Path $flutterRoot 'bin/cache/flutter_tools.snapshot'
        $packages = Join-Path $flutterTools '.dart_tool/package_config.json'
        if (!(Test-Path -LiteralPath $dart -PathType Leaf) -or !(Test-Path -LiteralPath $snapshot -PathType Leaf) -or !(Test-Path -LiteralPath $packages -PathType Leaf)) {
            throw 'The configured Flutter SDK is missing its cached Dart tool files.'
        }
        & $dart "--packages=$packages" $snapshot build apk --release --no-pub --target-platform android-arm64
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally { Pop-Location }
} finally {
    if (Test-Path -LiteralPath $temporaryStore) { Remove-Item -LiteralPath $temporaryStore -Force }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    $properties.Clear()
}
