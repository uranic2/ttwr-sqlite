param([string]$JdkHome,[string]$JavaExe,[switch]$Gui,[string]$RestartProbe)
. (Join-Path $PSScriptRoot 'common.ps1')
$jdk=Resolve-Jdk $JdkHome
if(!$JavaExe){$JavaExe=Join-Path $jdk 'bin\java.exe'}
$build=Join-Path $RepoRoot 'build'
$image=Join-Path $build 'image\ttwr-sqlite'
if(!(Test-Path -LiteralPath (Join-Path $image 'app\ttwr.dat'))){throw 'Run scripts/build.ps1 first.'}
$runtimeCp=Runtime-ClassPath $image
$classes=Join-Path $build ('test-classes-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force|Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\test\java') -Filter '*.java' -File -Recurse|Select-Object -ExpandProperty FullName)
& (Join-Path $jdk 'bin\javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -cp $runtimeCp -d $classes $sources
Assert-Exit 'Test compilation'
$toolCp=(Join-Path $build 'tools')+';'+(Join-Path $RepoRoot '.cache\dependencies\*')
& (Join-Path $jdk 'bin\java.exe') -cp $toolCp PatchMutationScenario (Join-Path $classes 'ttr\MutationScenario.class') $classes (Join-Path $image 'app\ttwr.dat')
Assert-Exit 'Mutation fixture instrumentation'
$run=Join-Path $build ('tests\portable-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$cp=$classes+';'+$runtimeCp
$jvm=@(('-Dttwr.home='+$run))
# Native access is only available on modern Java; runtime tests also support Java 8.
$version=(& $JavaExe -version 2>&1|Out-String)
if($version -match 'version "(\d+)' -and [int]$Matches[1] -ge 24){$jvm+= '--enable-native-access=ALL-UNNAMED'}
& $JavaExe @jvm -cp $cp ttr.SyntheticCatalog (Join-Path $run 'fixture\Players.sqlite')
Assert-Exit 'Synthetic fixture generation'
& $JavaExe @jvm -cp $cp ttr.WeakCacheRollbackChecks
Assert-Exit 'Weak cache rollback regression'
& $JavaExe @jvm '-Xmx512m' -cp $cp ttr.SqliteIntegrationChecks (Join-Path $run 'fixture\Players.sqlite') (Join-Path $run 'integration') 300
Assert-Exit 'Catalog integration tests'
& $JavaExe @jvm -cp $cp ttr.SqliteShutdownChecks (Join-Path $run 'shutdown')
Assert-Exit 'Shutdown tests'
& $JavaExe @jvm ('-Dttwr.seed='+(Join-Path $image 'app\seed')) -cp $cp ttr.PortableStartChecks
Assert-Exit 'Portable profile tests'
& $JavaExe @jvm -cp $cp ttr.RemoteFlowChecks (Join-Path $run 'remote')
Assert-Exit 'Simulated remote update tests'
& $JavaExe @jvm -cp $cp ttr.SinglePassImportChecks (Join-Path $run 'single-pass-import')
Assert-Exit 'Single-pass XML import tests'
& $JavaExe @jvm -cp $cp ttr.PlayerProfileChecks (Join-Path $run 'profile-import')
Assert-Exit 'TTWR profile import, cancellation and recovery tests'
& $JavaExe @jvm -cp $cp ttr.DbPackageCrashChecks (Join-Path $run 'db-package-crash')
Assert-Exit 'Interrupted DB package replacement recovery'
& $JavaExe @jvm -cp $cp ttr.DbPackageRecoveryFailureChecks (Join-Path $run 'recovery-failure')
Assert-Exit 'Damaged DB backup blocks startup'
& $JavaExe @jvm -cp $cp ttr.DatabaseDateChecks (Join-Path $run 'database-date')
Assert-Exit 'Database publication date persistence'
& $JavaExe @jvm -cp $cp ttr.PlayerStreamChecks (Join-Path $run 'player-stream')
Assert-Exit 'Player stream network and cancellation tests'
& $JavaExe @jvm -cp $cp ttr.updates.PlayerDownloadStatsChecks
Assert-Exit 'Player stream measurements'
& $JavaExe @jvm -cp $cp ttr.updates.ProgramUpdateChecks (Join-Path $run 'program-update')
Assert-Exit 'Program release update tests'
& $JavaExe @jvm -cp $cp ttr.updates.DownloadChecks (Join-Path $run 'download')
Assert-Exit 'Download progress and cancellation tests'
& $JavaExe @jvm -cp $cp ttr.updates.RetiredFilesChecks (Join-Path $run 'retired-files')
Assert-Exit 'Retired document migration tests'
& $JavaExe @jvm -cp $cp ttr.updates.UpdateStorageChecks (Join-Path $run 'update-storage')
Assert-Exit 'Update retention tests'
if($Gui){
    & $JavaExe @jvm ('-Dttwr.home='+(Join-Path $run 'main-menu-ui')) ('-Dttwr.seed='+(Join-Path $image 'app\seed')) -cp $cp ttr.MainMenuUiChecks
    Assert-Exit 'Main menu and database information UI'
    $statusHome=Join-Path $run 'database-status-ui'
    foreach($mode in @('update','restart')){
        & $JavaExe @jvm ('-Dttwr.home='+$statusHome) ('-Dttwr.seed='+(Join-Path $image 'app\seed')) -cp $cp ttr.DatabaseStatusUiChecks $mode
        Assert-Exit ('Database status UI '+$mode)
    }
    & $JavaExe @jvm -cp $cp ttr.DbPackageRecoveryFailureChecks (Join-Path $run 'recovery-failure-ui') gui
    Assert-Exit 'Recovery failure UI'
    foreach($mode in @('button','escape','close','success','unknown','error','import')){
        $playerHome=Join-Path $run ('player-ui-'+$mode)
        & $JavaExe @jvm ('-Dttwr.home='+$playerHome) ('-Dttwr.seed='+(Join-Path $image 'app\seed')) -cp $cp ttr.PlayerUpdateUiChecks $mode
        Assert-Exit ('Player update UI '+$mode)
    }
    foreach($mode in @('accept','decline')){
        $firstHome=Join-Path $run ('first-import-ui-'+$mode)
        & $JavaExe @jvm ('-Dttwr.home='+$firstHome) ('-Dttwr.seed='+(Join-Path $image 'app\seed')) -cp $cp ttr.ProfileFirstRunUiChecks $mode
        Assert-Exit ('First-run profile import UI '+$mode)
    }
    foreach($mode in @('current','cancel','storage')){
        $guiHome=Join-Path $run ('gui-'+$mode)
        New-Item -ItemType Directory -Path $guiHome -Force|Out-Null
        & $JavaExe @jvm ('-Dttwr.home='+$guiHome) ('-Dttwr.seed='+(Join-Path $image 'app\seed')) -cp $cp ttr.updates.ProgramUpdateUiChecks $mode
        Assert-Exit ('Program update UI '+$mode)
    }
    foreach($mode in @('button','escape','close','success','error')){
        $downloadHome=Join-Path $run ('download-ui-'+$mode)
        New-Item -ItemType Directory -Path $downloadHome -Force|Out-Null
        & $JavaExe @jvm ('-Dttwr.home='+$downloadHome) ('-Dttwr.seed='+(Join-Path $image 'app\seed')) -cp $cp ttr.updates.DownloadUiChecks $mode
        Assert-Exit ('Download UI '+$mode)
    }
}
if($RestartProbe){
    & $JavaExe @jvm -cp $cp ttr.updates.UpdateHelperSmoke (Join-Path $run 'helper-process') ([IO.Path]::GetFullPath($RestartProbe))
    Assert-Exit 'Updater helper and native restart'
}
Write-Output ('All tests passed. Temporary files: '+$run)
