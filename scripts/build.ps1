param([string]$JdkHome,[string]$Zig='zig',[switch]$SkipNative)
. (Join-Path $PSScriptRoot 'common.ps1')
$jdk=Resolve-Jdk $JdkHome
& (Join-Path $PSScriptRoot 'fetch-dependencies.ps1')
$lock=Get-Content -LiteralPath (Join-Path $RepoRoot 'config\dependencies.lock.json') -Raw|ConvertFrom-Json
$upstream=Join-Path $RepoRoot $lock.upstream.path
if(!(Test-Path -LiteralPath $upstream)){throw 'Place the original TTWR archive at vendor/upstream/ttwr.dat. See docs/Сборка.md.'}
if((Get-FileHash -LiteralPath $upstream).Hash.ToLowerInvariant() -ne $lock.upstream.sha256){throw 'Original TTWR archive SHA256 differs from config/dependencies.lock.json.'}
$build=Join-Path $RepoRoot 'build'
$image=Join-Path $build 'image\ttwr-sqlite'
if(Test-Path -LiteralPath (Join-Path $image 'data')){throw 'Build image contains user data. Keep it elsewhere before rebuilding.'}
foreach($folder in @('classes','tools','seed-tool','image\ttwr-sqlite\app\lib')){New-Item -ItemType Directory -Path (Join-Path $build $folder) -Force|Out-Null}
$deps=Join-Path $RepoRoot '.cache\dependencies'
$sources=@(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Filter '*.java' -File -Recurse|Select-Object -ExpandProperty FullName)
& (Join-Path $jdk 'bin\javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -cp ($upstream+';'+$deps+'\*') -d (Join-Path $build 'classes') $sources
Assert-Exit 'Application compilation'
$tools=@(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'tools\java') -Filter '*.java' -File|Select-Object -ExpandProperty FullName)
& (Join-Path $jdk 'bin\javac.exe') --release 17 -encoding UTF-8 -cp (Join-Path $deps 'asm-9.7.1.jar') -d (Join-Path $build 'tools') $tools
Assert-Exit 'Build-tool compilation'
$toolCp=(Join-Path $build 'tools')+';'+$deps+'\*'
& (Join-Path $jdk 'bin\java.exe') -cp $toolCp BuildSqliteTTWR $upstream (Join-Path $build 'classes') (Join-Path $image 'app\ttwr.dat') 'sqlite-jdbc-3.54.1.0.jar' (Join-Path $build 'patched-classes.txt') (Join-Path $RepoRoot 'VERSION') (Join-Path $RepoRoot 'assets\ttwr-sqlite.png')
Assert-Exit 'Archive assembly'
& (Join-Path $jdk 'bin\java.exe') -cp $toolCp VerifySqliteArchive (Join-Path $image 'app\ttwr.dat') (Join-Path $RepoRoot 'assets\ttwr-sqlite.png')
Assert-Exit 'Legacy software updater and icon audit'
foreach($item in $lock.artifacts|Where-Object{$_.group -eq 'runtime'}){Copy-Item -LiteralPath (Join-Path $deps $item.name) -Destination (Join-Path $image ('app\lib\'+$item.name)) -Force}
$runtimeCp=Runtime-ClassPath $image
& (Join-Path $jdk 'bin\javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -cp $runtimeCp -d (Join-Path $build 'seed-tool') (Join-Path $RepoRoot 'src\test\java\ttr\PrepareMinimalCatalog.java')
Assert-Exit 'Seed generator compilation'
$seed=Join-Path $image 'app\seed'
if(Test-Path -LiteralPath $seed){
    $check=& (Join-Path $jdk 'bin\java.exe') '--enable-native-access=ALL-UNNAMED' -cp $runtimeCp ttr.SqliteCatalogTool check (Join-Path $seed 'Players.sqlite')
    Assert-Exit 'Existing seed validation'
    if($check -notcontains 'SQLITE_CATALOG_OK players=0 integrity=ok'){throw 'Existing seed is not empty. It was not replaced.'}
}else{
    & (Join-Path $jdk 'bin\java.exe') '--enable-native-access=ALL-UNNAMED' -cp ((Join-Path $build 'seed-tool')+';'+$runtimeCp) ttr.PrepareMinimalCatalog $seed
    Assert-Exit 'Empty seed generation'
}
if(!$SkipNative){& (Join-Path $PSScriptRoot 'build-native.ps1') -Zig $Zig -Output (Join-Path $image 'ttwr-sqlite.exe')}
Write-Output ('Build image: '+$image)
