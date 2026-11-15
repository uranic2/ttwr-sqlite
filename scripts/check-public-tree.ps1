. (Join-Path $PSScriptRoot 'common.ps1')
$files=@(& git -C $RepoRoot ls-files --cached --others --exclude-standard)
Assert-Exit 'Git file inventory'
foreach($relative in $files){
    if($relative -eq 'vendor/upstream/ttwr.dat'){
        $lock=Get-Content -LiteralPath (Join-Path $RepoRoot 'config\dependencies.lock.json') -Raw|ConvertFrom-Json
        if((Get-FileHash -LiteralPath (Join-Path $RepoRoot $relative)).Hash.ToLowerInvariant() -ne $lock.upstream.sha256){throw 'Committed upstream archive differs from the pinned original.'}
        continue
    }
    if($relative -match '^(vendor|build|dist|releases|\.cache|\.local)/' -or $relative -match '(?i)\.(exe|dll|class|jar|dat|zip|sqlite)(-|$|\.)' -or $relative -match '(^|/)data/'){throw ('Private/generated file in Git candidate set: '+$relative)}
    $file=Join-Path $RepoRoot $relative
    if(!(Test-Path -LiteralPath $file -PathType Leaf)){continue}
    if($relative -match '^assets/[^/]+\.(png|ico)$'){continue}
    $text=Get-Content -LiteralPath $file -Raw -Encoding UTF8
    # These patterns are assembled to avoid matching the checker itself.
    $privateKey='-----BEGIN '+ '(?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    $token='gh'+'[pousr]_[A-Za-z0-9_]{20,}'
    $localPath='C:'+'[\\/](?:Users[\\/](?!Public|Default)[^\\/]+|USR[\\/])'
    if($text -match $privateKey -or $text -match $token){throw ('Possible credential in '+$relative)}
    if($text -match $localPath){throw ('Machine-specific private path in '+$relative)}
}
Write-Output ('Public file inventory passed: '+$files.Count+' source/documentation/asset files; generated binaries and profiles excluded.')
