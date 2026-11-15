. (Join-Path $PSScriptRoot 'common.ps1')
$lock=Get-Content -LiteralPath (Join-Path $RepoRoot 'config\native-toolchain.json') -Raw|ConvertFrom-Json
$cache=Join-Path $RepoRoot '.cache\toolchain'
New-Item -ItemType Directory -Path $cache -Force|Out-Null
$zip=Join-Path $cache ('zig-'+$lock.Version+'.zip')
if(!(Test-Path -LiteralPath $zip)){
    Invoke-WebRequest -Uri $lock.URL -OutFile ($zip+'.part') -MaximumRetryCount 3 -RetryIntervalSec 5
    if((Get-FileHash -LiteralPath ($zip+'.part')).Hash.ToLowerInvariant() -ne $lock.SHA256){throw 'Zig download SHA256 mismatch'}
    Move-Item -LiteralPath ($zip+'.part') -Destination $zip
}
if((Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant() -ne $lock.SHA256){throw 'Zig cache SHA256 mismatch'}
$directory=Join-Path $cache ('zig-'+$lock.Version)
if(!(Test-Path -LiteralPath $directory)){Expand-Archive -LiteralPath $zip -DestinationPath $directory}
$compiler=@(Get-ChildItem -LiteralPath $directory -Filter zig.exe -Recurse -File)
if($compiler.Count -ne 1){throw 'Expected exactly one Zig compiler'}
Write-Output $compiler[0].FullName
