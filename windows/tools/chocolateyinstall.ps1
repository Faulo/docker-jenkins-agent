$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$releasesPage = Join-Path $env:TEMP 'plastic-releases.html'
$installer = Join-Path $env:TEMP 'plastic-installer.exe'

try {
    $java = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Filter java.exe -File -Recurse |
        Where-Object FullName -Match '\\bin\\java\.exe$' |
        Select-Object -First 1
    if (-not $java) {
        throw 'Temurin Java runtime was not found'
    }
    $javaHome = Split-Path -Parent (Split-Path -Parent $java.FullName)
    Install-ChocolateyEnvironmentVariable -VariableName JAVA_HOME -VariableValue $javaHome -VariableType Machine
    $env:JAVA_HOME = $javaHome

    Get-ChocolateyWebFile `
        -PackageName $env:ChocolateyPackageName `
        -FileFullPath $releasesPage `
        -Url 'https://www.plasticscm.com/download'

    # The current download page advertises Windows installers; the Debian index can reject Windows requests.
    $versions = @([regex]::Matches(
        (Get-Content $releasesPage -Raw),
        'href="/download/(\d+\.\d+\.\d+\.\d+)/plasticscm/windows/'
    ) | ForEach-Object { [version]$_.Groups[1].Value } | Sort-Object -Unique)
    if ($versions.Count -ne 1) {
        throw 'Expected exactly one current Unity Version Control Windows release on the download page'
    }
    $plasticVersion = $versions[0]

    $installerUrl = 'https://www.plasticscm.com/download/downloadinstaller/{0}/plasticscm/windows/client?flags=None' -f $plasticVersion
    Get-ChocolateyWebFile `
        -PackageName $env:ChocolateyPackageName `
        -FileFullPath $installer `
        -Url $installerUrl

    $signature = Get-AuthenticodeSignature $installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Unity Technologies') {
        throw 'Unity Version Control signature verification failed'
    }

    $process = Start-Process `
        -FilePath $installer `
        -ArgumentList '--mode unattended --unattendedmodeui none' `
        -Wait -NoNewWindow -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Unity Version Control installer failed with exit code $($process.ExitCode)"
    }

    $plasticPath = 'C:\Program Files\PlasticSCM5\client'
    Install-ChocolateyPath -PathToInstall $plasticPath -PathType Machine
    $env:Path = $plasticPath + ';' + $env:Path

    $installedVersion = cm.exe version
    if ($LASTEXITCODE -ne 0 -or $installedVersion -notmatch ('^' + [regex]::Escape($plasticVersion.ToString()) + '(?:\s|$)')) {
        throw "Unexpected Unity Version Control version: $installedVersion"
    }
} finally {
    Remove-Item -LiteralPath $releasesPage, $installer -Force -ErrorAction SilentlyContinue
}
