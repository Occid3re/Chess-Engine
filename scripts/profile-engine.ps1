param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath,

    [Parameter(Mandatory = $true)]
    [string]$ProfileDir,

    [int]$MoveTimeMs = 2000,
    [int]$PlyCount = 80,
    [string]$JfrDuration = "180s"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-FullPath {
    param([string]$PathValue)
    return [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PathValue).ProviderPath)
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "The UCI engine jar '$JarPath' was not found. Build the project before running the profiler."
}
if (-not (Test-Path -LiteralPath $ProfileDir)) {
    New-Item -ItemType Directory -Path $ProfileDir -Force | Out-Null
}

$jarFullPath = Resolve-FullPath -PathValue $JarPath
$profileFullPath = Resolve-FullPath -PathValue $ProfileDir

if ($MoveTimeMs -le 0) { throw "MoveTimeMs must be greater than zero." }
if ($PlyCount   -le 0) { throw "PlyCount must be greater than zero." }

$timestamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$jfrFile     = Join-Path $profileFullPath "uci-$timestamp.jfr"
$logFile     = Join-Path $profileFullPath "uci-$timestamp.log"
$summaryFile = Join-Path $profileFullPath "uci-$timestamp-summary.json"

$logWriter = New-Object System.IO.StreamWriter($logFile, $false, [System.Text.Encoding]::UTF8)
$logWriter.AutoFlush = $true

Write-Host "Launching engine for profiling..."
Write-Host "  Engine jar    : $jarFullPath"
Write-Host "  JFR duration  : $JfrDuration"
Write-Host "  Move time (ms): $MoveTimeMs"
Write-Host "  Ply count     : $PlyCount"

# Robustes JFR-Argument mit korrekt gequoteter Filename-Komponente
$startFlightRecording = ('-XX:StartFlightRecording=name=uci,settings=profile,duration={0},filename="{1}"' -f $JfrDuration, $jfrFile)

# ----> WICHTIG: In Windows PowerShell 5.1 .Arguments als String setzen (kein .ArgumentList vorhanden)
$processStartInfo = New-Object System.Diagnostics.ProcessStartInfo
$processStartInfo.FileName = 'java'
$processStartInfo.Arguments = "$startFlightRecording -jar `"$jarFullPath`""
$processStartInfo.RedirectStandardInput  = $true
$processStartInfo.RedirectStandardOutput = $true
$processStartInfo.RedirectStandardError  = $true
$processStartInfo.UseShellExecute        = $false
$processStartInfo.CreateNoWindow         = $true

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $processStartInfo
$null = $process.Start()

$stderrThread = [System.Threading.Thread]::new([System.Threading.ThreadStart]{
    while (-not $process.StandardError.EndOfStream) {
        $line = $process.StandardError.ReadLine()
        if ($null -ne $line) {
            $logWriter.WriteLine("[stderr] $line")
        }
    }
})
$stderrThread.IsBackground = $true
$stderrThread.Start()

$stdin = $process.StandardInput

function Write-Log {
    param([string]$Channel,[string]$Message)
    $logWriter.WriteLine("[{0}] {1}" -f $Channel, $Message)
}

function Send-UciCommand {
    param([string]$Command)
    Write-Log -Channel 'stdin' -Message $Command
    $stdin.WriteLine($Command)
    $stdin.Flush()
}

function Wait-ForLine {
    param([string]$Pattern,[string]$Context)
    while ($true) {
        $line = $process.StandardOutput.ReadLine()
        if ($null -eq $line) {
            if ($process.HasExited) { throw "Engine terminated unexpectedly while waiting for $Context ($Pattern)." }
            continue
        }
        Write-Log -Channel 'stdout' -Message $line
        if ([string]::IsNullOrWhiteSpace($Pattern)) { return [PSCustomObject]@{ Line = $line } }
        $match = [regex]::Match($line, $Pattern)
        if ($match.Success) { return [PSCustomObject]@{ Line = $line; Match = $match } }
    }
}

$moveHistory   = New-Object System.Collections.Generic.List[string]
$quitIssued    = $false
$bestMovePattern = '^bestmove\s+(\S+)'

try {
    Send-UciCommand -Command 'uci'
    [void](Wait-ForLine -Pattern '^uciok$' -Context 'uci handshake')

    Send-UciCommand -Command 'isready'
    [void](Wait-ForLine -Pattern '^readyok$' -Context 'engine readiness')

    Send-UciCommand -Command 'ucinewgame'
    Send-UciCommand -Command 'isready'
    [void](Wait-ForLine -Pattern '^readyok$' -Context 'new game readiness')

    for ($ply = 1; $ply -le $PlyCount; $ply++) {
        $positionCommand = 'position startpos'
        if ($moveHistory.Count -gt 0) { $positionCommand += ' moves ' + ($moveHistory -join ' ') }

        Send-UciCommand -Command $positionCommand
        Send-UciCommand -Command ("go movetime {0}" -f $MoveTimeMs)

        $bestMoveResult = Wait-ForLine -Pattern $bestMovePattern -Context ("bestmove response for ply $ply")
        $bestMove = $bestMoveResult.Match.Groups[1].Value

        if ([string]::IsNullOrWhiteSpace($bestMove) -or $bestMove -eq '(none)') {
            Write-Host "Engine reported no legal moves on ply $ply. Stopping self-play loop."
            break
        }

        Write-Host ("Ply {0,2}: {1}" -f $ply, $bestMove)
        $moveHistory.Add($bestMove)

        Send-UciCommand -Command 'isready'
        [void](Wait-ForLine -Pattern '^readyok$' -Context ("post-move readiness for ply $ply"))
    }

    Send-UciCommand -Command 'quit'
    $quitIssued = $true
    $stdin.Close()

    if (-not $process.WaitForExit(10000)) {
        Write-Warning 'Engine did not exit within 10 seconds after quit; terminating the process.'
        $process.Kill()
        $process.WaitForExit()
    }
}
catch {
    Write-Error $_
    if (-not $process.HasExited) {
        try { if (-not $quitIssued) { $stdin.WriteLine('quit'); $stdin.Flush() } } catch {}
        try { $stdin.Close() } catch {}
        try {
            if (-not $process.WaitForExit(2000)) { $process.Kill(); $process.WaitForExit() }
        } catch {}
    }
    throw
}
finally {
    try { if ($stderrThread -and $stderrThread.IsAlive) { $stderrThread.Join() } } catch {}
    $logWriter.Dispose()
}

$exitCode = $process.ExitCode

$summary = [ordered]@{
    timestampUtc       = (Get-Date).ToUniversalTime().ToString('u')
    engineJar          = $jarFullPath
    jfrDuration        = $JfrDuration
    moveTimeMs         = $MoveTimeMs
    requestedPlyCount  = $PlyCount
    completedPlyCount  = $moveHistory.Count
    bestMoves          = $moveHistory
    jfrFile            = $jfrFile
    logFile            = $logFile
    exitCode           = $exitCode
}

$summary | ConvertTo-Json -Depth 4 | Set-Content -Path $summaryFile -Encoding UTF8

Write-Host ''
Write-Host 'Profiling run complete.'
Write-Host "  Best moves played : $($moveHistory.Count) plies"
Write-Host "  Engine exit code  : $exitCode"
Write-Host "  JFR capture       : $jfrFile"
Write-Host "  Engine log        : $logFile"
Write-Host "  Run summary       : $summaryFile"
Write-Host ''
Write-Host 'Open the JFR file in Java Mission Control to inspect hotspots.'
