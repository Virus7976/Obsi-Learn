# One-time setup: make the release key (if there is none) and copy it to the clipboard for GitHub secrets.
# The key and password stay in %USERPROFILE%\.obsilearn, outside the repo. Back that folder up.
$ErrorActionPreference = 'Stop'
$dir = "$env:USERPROFILE\.obsilearn"
$jks = "$dir\release.jks"

if (-not (Test-Path $jks)) {
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if (-not $keytool) { $keytool = "$env:USERPROFILE\.jdks\temurin-21\bin\keytool.exe" }
    if (-not (Test-Path $keytool)) { throw "keytool not found. Set JAVA_HOME to a JDK." }
    New-Item -ItemType Directory -Force $dir | Out-Null
    $pw = -join ((48..57 + 97..122) | Get-Random -Count 28 | ForEach-Object { [char]$_ })
    & $keytool -genkeypair -keystore $jks -alias obsilearn -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $pw -keypass $pw -dname "CN=ObsiLearn, O=Personal" | Out-Null
    Set-Content "$dir\password.txt" $pw -Encoding ascii -NoNewline
}
$pw = (Get-Content "$dir\password.txt" -Raw).Trim()

Set-Clipboard ([Convert]::ToBase64String([IO.File]::ReadAllBytes($jks)))
Write-Host ""
Write-Host "STEP 1. The key is on your clipboard."
Write-Host "  Open https://github.com/Virus7976/Obsi-Learn/settings/secrets/actions/new"
Write-Host "  Name:   ANDROID_KEYSTORE_B64"
Write-Host "  Secret: paste (Ctrl+V), then Add secret"
Read-Host "Press Enter when saved"

Set-Clipboard $pw
Write-Host ""
Write-Host "STEP 2. The password is on your clipboard."
Write-Host "  Open the same page again"
Write-Host "  Name:   ANDROID_KEYSTORE_PASSWORD"
Write-Host "  Secret: paste (Ctrl+V), then Add secret"
Read-Host "Press Enter when saved"

Set-Clipboard " "
Write-Host ""
Write-Host "Done. Clipboard cleared. Back up $dir (a copy on a USB stick is enough)."
