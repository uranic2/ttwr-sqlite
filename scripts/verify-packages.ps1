param([Parameter(Mandatory)][string]$Java32Home)
. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$version=(Get-Content -LiteralPath (Join-Path $RepoRoot 'VERSION') -Raw).Trim()
$checks=Join-Path $RepoRoot ('build\release-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $checks -Force|Out-Null
$oldJavaHome=$env:JAVA_HOME
try{
    foreach($flavor in @('system-java','windows-x64')){
        $zip=Join-Path $RepoRoot ('releases\ttwr-sqlite-'+$version+'-'+$flavor+'.zip')
        $folder=Join-Path $checks $flavor
        $archive=[IO.Compression.ZipFile]::OpenRead($zip)
        try{
            if(@($archive.Entries|Where-Object{$_.FullName -match '(^|/)data/'}).Count){throw 'User profile found in release'}
            $hasJava=@($archive.Entries|Where-Object{$_.FullName -like 'runtime/*'}).Count -gt 0
            if($hasJava -ne ($flavor -eq 'windows-x64')){throw 'Incorrect runtime variant'}
        }finally{$archive.Dispose()}
        [IO.Compression.ZipFile]::ExtractToDirectory($zip,$folder)
        foreach($file in (Get-Content -LiteralPath (Join-Path $folder 'checksums.json') -Raw|ConvertFrom-Json)){
            $path=[IO.Path]::GetFullPath((Join-Path $folder $file.path))
            if(!$path.StartsWith($folder+[IO.Path]::DirectorySeparatorChar)){throw 'Checksum path escapes package'}
            if((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -ne $file.sha256){throw ('Package checksum mismatch: '+$file.path)}
        }
        $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $folder 'app\ttwr.dat'))
        try{
            $reader=[IO.StreamReader]::new($archive.GetEntry('ttr/build-version.txt').Open())
            try{if($reader.ReadToEnd().Trim() -cne $version){throw 'Archive version does not match VERSION'}}finally{$reader.Dispose()}
        }finally{$archive.Dispose()}
        $env:JAVA_HOME=$Java32Home
        $process=Start-Process -FilePath (Join-Path $folder 'ttwr-sqlite.exe') -ArgumentList '--check-runtime' -WorkingDirectory $folder -WindowStyle Hidden -PassThru
        if(!$process.WaitForExit(60000)){throw 'Packaged launcher timed out'}
        $process.Refresh()
        if($process.ExitCode -ne 0){throw ('Packaged launcher failed: '+$flavor)}
        $log=Get-Content -LiteralPath (Join-Path $folder 'data\launcher-java.log')
        if($log -notcontains 'SQLITE_CATALOG_OK players=0 integrity=ok'){throw 'Seed database is not empty or is damaged'}
        $selection=Get-Content -LiteralPath (Join-Path $folder 'data\launcher.log') -Raw
        $expected=if($flavor -eq 'system-java'){'Selected source=JAVA_HOME major=8 bits=32'}else{'Selected source=bundled-runtime major=25 bits=64'}
        if(!$selection.Contains($expected)){throw ('Unexpected Java selection: '+$flavor)}
        Write-Output ('PACKAGE_VERIFIED '+$flavor+' SHA256='+(Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant())
    }
}finally{$env:JAVA_HOME=$oldJavaHome}
