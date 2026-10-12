# Keep the failure's exact base and package versions for a repeatable reproduction.
FROM faulo/choco:windowsservercore-ltsc2022@sha256:a418802f2342dc0e23971b83c3279c0fb590061e0b0e1a98600cbc680dea8eba

COPY tests/reproductions/powershell-core/packages.nuspec C:/repro/packages.nuspec

RUN choco-install C:/repro/packages.nuspec; \
    if ($LASTEXITCODE -ne 0) { throw ('PowerShell reproduction failed with exit code {0}' -f $LASTEXITCODE) }
