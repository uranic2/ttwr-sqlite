param([string]$Zig='zig',[string]$Output)
. (Join-Path $PSScriptRoot 'common.ps1')
if(!$Output){$Output=Join-Path $RepoRoot 'build\native\ttwr-sqlite.exe'}
New-Item -ItemType Directory -Path (Split-Path -Parent $Output) -Force|Out-Null
$resource=Join-Path (Split-Path -Parent $Output) 'ttwr-sqlite.res'
& $Zig rc /i (Join-Path $RepoRoot 'assets') /fo $resource (Join-Path $RepoRoot 'src\main\native\ttwr-sqlite.rc')
Assert-Exit 'Native icon resource compilation'
& $Zig cc -target x86-windows-gnu -Os '-Wl,--subsystem,windows' -Wall -Wextra -o $Output (Join-Path $RepoRoot 'src\main\native\ttwr-sqlite.c') $resource -ladvapi32 -lshell32 -luser32
Assert-Exit 'Native launcher compilation'
