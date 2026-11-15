$ErrorActionPreference='Stop'
$script:RepoRoot=Split-Path -Parent $PSScriptRoot
function Resolve-Jdk([string]$Requested){
    $candidates=@($Requested,$env:JAVA_HOME)
    $command=Get-Command javac.exe -ErrorAction SilentlyContinue
    if($command){$candidates+=Split-Path -Parent (Split-Path -Parent $command.Source)}
    $standard=Join-Path $env:ProgramFiles 'Java'
    if(Test-Path -LiteralPath $standard){$candidates+=@(Get-ChildItem -LiteralPath $standard -Directory|Sort-Object Name -Descending|Select-Object -ExpandProperty FullName)}
    foreach($candidate in $candidates){if($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))){return (Resolve-Path -LiteralPath $candidate).Path}}
    throw 'JDK 17+ not found. Pass -JdkHome or set JAVA_HOME to a JDK.'
}
function Assert-Exit([string]$Operation){if($LASTEXITCODE -ne 0){throw ($Operation+' failed with exit code '+$LASTEXITCODE)}}
function Runtime-ClassPath([string]$Image){return (Join-Path $Image 'app\ttwr.dat')+';'+(Join-Path $Image 'app\lib\*')}
function Write-Checksums([string]$Directory){
    $manifest=@(Get-ChildItem -LiteralPath $Directory -File -Recurse|Where-Object{$_.Name -ne 'checksums.json'}|Sort-Object FullName|ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($Directory.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
    $manifest|ConvertTo-Json -Depth 3|Set-Content -LiteralPath (Join-Path $Directory 'checksums.json') -Encoding UTF8
}
