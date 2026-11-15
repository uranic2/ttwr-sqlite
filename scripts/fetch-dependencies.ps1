. (Join-Path $PSScriptRoot 'common.ps1')
$lock=Get-Content -LiteralPath (Join-Path $RepoRoot 'config\dependencies.lock.json') -Raw|ConvertFrom-Json
$directory=Join-Path $RepoRoot '.cache\dependencies'
New-Item -ItemType Directory -Path $directory -Force|Out-Null
foreach($item in $lock.artifacts){
    $target=Join-Path $directory $item.name
    if(!(Test-Path -LiteralPath $target)){
        $stage=$target+'.part'
        try{Invoke-WebRequest -Uri $item.url -OutFile $stage;if((Get-FileHash -LiteralPath $stage).Hash.ToLowerInvariant() -ne $item.sha256){throw ('SHA256 mismatch: '+$item.name)};Move-Item -LiteralPath $stage -Destination $target}finally{if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage}}
    }
    if((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $item.sha256){throw ('Cached dependency changed: '+$item.name)}
    Write-Output ('Verified '+$item.name)
}
