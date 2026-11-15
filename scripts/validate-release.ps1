param([string]$Tag)
. (Join-Path $PSScriptRoot 'common.ps1')
$version=(Get-Content -LiteralPath (Join-Path $RepoRoot 'VERSION') -Raw).Trim()
if($version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'){throw 'VERSION must be a stable X.Y.Z version'}
if($Tag -and $Tag -cne ('v'+$version)){throw ('Tag must match VERSION exactly: v'+$version)}
$readme=Get-Content -LiteralPath (Join-Path $RepoRoot 'README.md') -Raw
if(!$readme.Contains('**'+$version+'**')){throw 'README must show the release version'}
foreach($flavor in @('system-java','windows-x64')){
    if(!$readme.Contains('/releases/download/v'+$version+'/ttwr-sqlite-'+$version+'-'+$flavor+'.zip')){throw ('README download link is missing for '+$flavor)}
}
$changelog=Get-Content -LiteralPath (Join-Path $RepoRoot 'CHANGELOG.md') -Raw
$pattern='(?ms)^## '+[regex]::Escape($version)+'[^\r\n]*\r?\n(.*?)(?=^## |\z)'
$match=[regex]::Match($changelog,$pattern)
if(!$match.Success){throw 'CHANGELOG must contain a section for VERSION'}
$directory=Join-Path $RepoRoot 'releases'
New-Item -ItemType Directory -Path $directory -Force|Out-Null
$notes=$match.Groups[1].Value.Trim()+"`n`n"+@'
## Комплекты

- **windows-x64** — со встроенной Java 25 x64.
- **system-java** — без Java; нужна Java 8 или новее, x32 либо x64.

Распакуйте ZIP целиком и запустите ttwr-sqlite.exe. Стартовая база пуста; список игроков загружается через «БД игроков → Обновить БД игроков из интернет → Обновить».

При ручном переходе закройте программу и сохраните свою папку data. Начиная с 0.1.2, последующие обновления можно устанавливать из программы; data и runtime сохраняются.
'@
[IO.File]::WriteAllText((Join-Path $directory 'release-notes.md'),$notes,[Text.UTF8Encoding]::new($false))
Write-Output ('Release version validated: '+$version)
