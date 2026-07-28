$ErrorActionPreference = "Stop"

Write-Host "Creation de la cle de signature permanente Albugimed" -ForegroundColor Cyan
Write-Host "Cette operation ne doit etre executee qu'une seule fois."
Write-Host "Le mot de passe ne sera ni affiche ni envoye dans la conversation."
Write-Host ""

$keytoolCandidates = @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\keytool.exe" }),
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

$keytool = $keytoolCandidates | Select-Object -First 1
if (-not $keytool) {
    throw "keytool.exe introuvable. Android Studio/JDK 17 est requis."
}

$signingDirectory = Join-Path $PSScriptRoot "signing"
$keystorePath = Join-Path $signingDirectory "albugimed-release.p12"
$propertiesPath = Join-Path $signingDirectory "albugimed-signing.properties"

if ((Test-Path -LiteralPath $keystorePath) -or
    (Test-Path -LiteralPath $propertiesPath)) {
    throw "Une identite permanente existe deja. Aucun fichier n'a ete remplace."
}

$firstSecure = Read-Host "Choisis un mot de passe (16 caracteres minimum)" -AsSecureString
$secondSecure = Read-Host "Retape exactement le meme mot de passe" -AsSecureString
$firstPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($firstSecure)
$secondPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secondSecure)

try {
    $first = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($firstPointer)
    $second = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secondPointer)
    if ($first -cne $second) {
        throw "Les deux mots de passe ne correspondent pas."
    }
    if ($first.Length -lt 16) {
        throw "Le mot de passe doit contenir au moins 16 caracteres."
    }
    if ($first -notmatch '^[A-Za-z0-9!@#%_.-]+$') {
        throw "Utilise uniquement lettres, chiffres et les symboles ! @ # % _ - ."
    }

    $confirmation = Read-Host "Enregistre ce mot de passe dans un gestionnaire puis tape OUI"
    if ($confirmation -cne "OUI") {
        throw "Creation annulee : le mot de passe doit d'abord etre sauvegarde."
    }

    New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
    & $keytool -genkeypair -v `
        -storetype PKCS12 `
        -keystore $keystorePath `
        -alias "albugimed-app" `
        -keyalg RSA `
        -keysize 4096 `
        -sigalg SHA256withRSA `
        -validity 18263 `
        -dname "CN=Albugimed Android Release,O=Albugimed,C=FR" `
        -storepass $first `
        -keypass $first
    if ($LASTEXITCODE -ne 0) {
        throw "keytool a refuse la creation de la cle."
    }

    $temporaryCertificate = Join-Path $signingDirectory "albugimed-certificate.tmp"
    & $keytool -exportcert `
        -keystore $keystorePath `
        -alias "albugimed-app" `
        -storepass $first `
        -file $temporaryCertificate
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible d'exporter le certificat public."
    }
    $fingerprint = (Get-FileHash -LiteralPath $temporaryCertificate -Algorithm SHA256).Hash
    [IO.File]::Delete($temporaryCertificate)

    $properties = @(
        "storeFile=signing/albugimed-release.p12",
        "storePassword=$first",
        "keyAlias=albugimed-app",
        "keyPassword=$first",
        "certificateSha256=$fingerprint"
    )
    [IO.File]::WriteAllLines(
        $propertiesPath,
        $properties,
        [Text.UTF8Encoding]::new($false)
    )

    Write-Host ""
    Write-Host "Cle permanente creee avec succes." -ForegroundColor Green
    Write-Host "Empreinte SHA-256 publique : $fingerprint"
    Write-Host "Ne supprime jamais le fichier albugimed-release.p12."
} finally {
    if ($firstPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($firstPointer)
    }
    if ($secondPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secondPointer)
    }
    $first = $null
    $second = $null
}
