param([string]$RuntimeHome,[string]$OutputDirectory)
. (Join-Path $PSScriptRoot 'common.ps1')
$version=(Get-Content -LiteralPath (Join-Path $RepoRoot 'VERSION') -Raw).Trim()
$flavor=if($RuntimeHome){'windows-x64'}else{'system-java'}
$image=Join-Path $RepoRoot 'build\image\ttwr-sqlite'
if(!(Test-Path -LiteralPath (Join-Path $image 'ttwr-sqlite.exe'))){throw 'Build the Java archive and native launcher first.'}
if(!$OutputDirectory){$OutputDirectory=Join-Path $RepoRoot ('dist\ttwr-sqlite-'+$version+'-'+$flavor)}
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $OutputDirectory){throw 'Output directory already exists; nothing was overwritten.'}
New-Item -ItemType Directory -Path $OutputDirectory|Out-Null
Copy-Item -LiteralPath (Join-Path $image 'app') -Destination $OutputDirectory -Recurse
Copy-Item -LiteralPath (Join-Path $image 'ttwr-sqlite.exe') -Destination (Join-Path $OutputDirectory 'ttwr-sqlite.exe')
if($RuntimeHome){
    if(!(Test-Path -LiteralPath (Join-Path $RuntimeHome 'bin\java.exe'))){throw 'RuntimeHome must contain bin/java.exe.'}
    Copy-Item -LiteralPath $RuntimeHome -Destination (Join-Path $OutputDirectory 'runtime') -Recurse
}
# Source documents have Russian names; installed filenames remain compatible with the updater.
$packageDocuments=@{
    'Руководство-пользователя.md'='README.md'
    'Изменения.md'='changes.md'
    'Сборка.md'='building.md'
    'Тестирование.md'='testing.md'
    'Архитектура.md'='architecture.md'
    'Обновление-программы.md'='program-updates.md'
}
foreach($sourceName in $packageDocuments.Keys){
    $content=[IO.File]::ReadAllText((Join-Path $RepoRoot ('docs\'+$sourceName)))
    $content=[regex]::Replace($content,'(\]\()<?([^):#<>]+\.md)(#[^)>]*)?>?(\))',{
        param($match)
        $target=$match.Groups[2].Value
        if($packageDocuments.ContainsKey($target)){$target=$packageDocuments[$target]}
        else{$encoded=($target.Split('/')|ForEach-Object{[Uri]::EscapeDataString($_)}) -join '/';$target='https://github.com/uranic2/ttwr-sqlite/blob/main/docs/'+$encoded}
        $match.Groups[1].Value+$target+$match.Groups[3].Value+$match.Groups[4].Value
    })
    [IO.File]::WriteAllText((Join-Path $OutputDirectory $packageDocuments[$sourceName]),$content,(New-Object Text.UTF8Encoding($false)))
}
Copy-Item -LiteralPath (Join-Path $RepoRoot 'assets') -Destination (Join-Path $OutputDirectory 'assets') -Recurse
Copy-Item -LiteralPath (Join-Path $RepoRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $OutputDirectory 'THIRD_PARTY_NOTICES.md')
Write-Checksums $OutputDirectory
$releaseDir=Join-Path $RepoRoot 'releases'
New-Item -ItemType Directory -Path $releaseDir -Force|Out-Null
$zip=Join-Path $releaseDir ('ttwr-sqlite-'+$version+'-'+$flavor+'.zip')
if(Test-Path -LiteralPath $zip){throw 'Release archive already exists; nothing was overwritten.'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($OutputDirectory,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
Write-Output ('Release archive: '+$zip)
$hash=(Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant()
Add-Content -LiteralPath (Join-Path $releaseDir 'SHA256SUMS.txt') -Value ($hash+'  '+[IO.Path]::GetFileName($zip)) -Encoding ASCII
Write-Output ('SHA256: '+$hash)
