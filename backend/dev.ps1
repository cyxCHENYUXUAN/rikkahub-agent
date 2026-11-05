$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot '..\scripts\dev-backend.mjs')
exit $LASTEXITCODE
