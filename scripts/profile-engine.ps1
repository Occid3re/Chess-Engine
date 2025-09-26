param(
    [string]$JarPath,
    [string]$ProfileDir,
    [int]$MoveTimeMs = 2000,
    [int]$PlyCount = 80,
    [string]$JfrDuration = "180s",
    [int]$HandshakeTimeoutMs = 30000,
    [switch]$EchoEngine,   # show engine stdout live
    [switch]$DryRun        # just print the computed java command and exit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-FullPath {
    param([string]$PathValue)
    return [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PathValue).ProviderPath)
}

function Resolve-DefaultPaths {
    $projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
    $targetDir   = Join-Path $projectRoot 'target'
    $profilesDir = Join-Path $projectRoot 'profiles'

    return [PSCustomObject]@{
        ProjectRoot = $projectRoot
        TargetDir   = $targetDir
        ProfilesDir = $profilesDir
    }
}

function Get-LatestUciJar {
    param([string]$TargetDir)

    $regex = [regex]'chess-engine-(\d+(?:\.\d+)*)-uci\.jar$'
    $candidates = Get-ChildItem -LiteralPath $TargetDir -Filter 'chess-engine-*-uci.jar' -File -ErrorAction SilentlyContinue
    if (-not $candidates) { return $null }

    $sorted = $candidates | Sort-Object -Property @{ Expression = {
        $match = $regex.Match($_.Name)
        if ($match.Success) {
            try { return [version]$match.Groups[1].Value } catch { return [version]'0.0.0.0' }
        }
        return [version]'0.0.0.0'
    } }, @{ Expression = { $_.LastWriteTimeUtc } }

    return $sorted[-1].FullName
}

$defaults = Resolve-DefaultPaths

if (-not $JarPath -or [string]::IsNullOrWhiteSpace($JarPath)) {
    $latestJar = Get-LatestUciJar -TargetDir $defaults.TargetDir
    if (-not $latestJar) {
        throw "No chess-engine-*-uci.jar found in '$($defaults.TargetDir)'. Build the project before running the profiler."
    }
    $JarPath = $latestJar
}

if (-not $ProfileDir -or [string]::IsNullOrWhiteSpace($ProfileDir)) {
    $ProfileDir = $defaults.ProfilesDir
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "The UCI engine jar '$JarPath' was not found. Build the project before running the profiler."
}
if (-not (Test-Path -LiteralPath $ProfileDir)) {
    New-Item -ItemType Directory -Path $ProfileDir -Force | Out-Null
}

if (-not $PSBoundParameters.ContainsKey('HandshakeTimeoutMs') -and $env:CHESSENGINE_HANDSHAKE_TIMEOUT_MS) {
    if (-not [int]::TryParse($env:CHESSENGINE_HANDSHAKE_TIMEOUT_MS, [ref]$HandshakeTimeoutMs)) {
        throw "Environment variable CHESSENGINE_HANDSHAKE_TIMEOUT_MS must be a valid integer."
    }
}

$jarFullPath = Resolve-FullPath -PathValue $JarPath
$profileFullPath = Resolve-FullPath -PathValue $ProfileDir

if ($MoveTimeMs -le 0) { throw "MoveTimeMs must be greater than zero." }
if ($PlyCount   -le 0) { throw "PlyCount must be greater than zero." }
if ($HandshakeTimeoutMs -le 0) { throw "HandshakeTimeoutMs must be greater than zero." }

$timestamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$jfrFile     = Join-Path $profileFullPath "uci-$timestamp.jfr"
$logFile     = Join-Path $profileFullPath "uci-$timestamp.log"
$summaryFile = Join-Path $profileFullPath "uci-$timestamp-summary.json"

$logWriter = New-Object System.IO.StreamWriter($logFile, $false, [System.Text.Encoding]::UTF8)
$logWriter.AutoFlush = $true

function Write-Log {
    param([string]$Channel, [string]$Message)
    if ($null -eq $Channel) { $Channel = '' }
    if ($null -eq $Message) { $Message = '' }
    $logWriter.WriteLine(("[{0}] {1}" -f @($Channel, $Message)))
}

function Write-LogAndMaybeConsole {
    param([string]$Channel,[string]$Message)
    Write-Log -Channel $Channel -Message $Message
    # Always echo UCI traffic by default (less surprising)
    if ($Channel -eq 'stderr') { Write-Warning $Message; return }
    if ($Channel -eq 'stdout') { Write-Host    $Message; return }
    if ($Channel -eq 'stdin')  { Write-Host    $Message; return }
    if ($EchoEngine) { Write-Host $Message }
}

Write-Host "Launching engine for profiling..."
Write-Host "  Engine jar    : $jarFullPath"
Write-Host "  JFR duration  : $JfrDuration"
Write-Host "  Move time (ms): $MoveTimeMs"
Write-Host "  Ply count     : $PlyCount"
Write-Host "  Profile dir   : $profileFullPath"
Write-Host "  Log file      : $logFile"
Write-Host "  Handshake wait: $HandshakeTimeoutMs ms"

# JVM options (env overrides supported)
$javaXms              = if ($env:JAVA_XMS) { $env:JAVA_XMS } else { '8g' }
$javaXmx              = if ($env:JAVA_XMX) { $env:JAVA_XMX } else { '8g' }
$javaGc               = if ($env:JAVA_GC)  { $env:JAVA_GC }  else { 'g1' }
$javaActiveProcessors = if ($env:JAVA_ACTIVE_PROCESSORS) { $env:JAVA_ACTIVE_PROCESSORS } else { '24' }
$javaExtraOpts        = if ($env:JAVA_EXTRA_OPTS) { $env:JAVA_EXTRA_OPTS } else { '-XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -Dchessengine.tt.mb=256 -Dchessengine.uci.info.minIntervalMs=120 -Dchessengine.uci.info.maxPvLen=12' }

$chessThreads   = if ($env:CHESSENGINE_THREADS) { $env:CHESSENGINE_THREADS } else { '24' }
$lazyThreads    = if ($env:CHESSENGINE_LAZY_THREADS) { $env:CHESSENGINE_LAZY_THREADS } else { '8' }
$rootParLimit   = if ($env:CHESSENGINE_ROOT_PAR_LIMIT) { $env:CHESSENGINE_ROOT_PAR_LIMIT } else { '48' }

Write-Host "  Search threads: $chessThreads"
Write-Host "  Lazy threads  : $lazyThreads"
Write-Host "  Root fanout   : $rootParLimit"

function Split-OptionString {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return @() }
    return $Value -split '\s+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
}

function Convert-GcOption {
    param([string]$Value)
    switch -Regex ($Value) {
        '^g1$'         { return '-XX:+UseG1GC' }
        '^shenandoah$' { return '-XX:+UseShenandoahGC' }
        '^zgc$'        { return '-XX:+UseZGC' }
        default        { return $Value }
    }
}

function Join-Arguments {
    param([string[]]$Arguments)
    return ($Arguments | ForEach-Object {
        if ($_ -match '[\s\"]') { '"' + ($_ -replace '"', '""') + '"' } else { $_ }
    }) -join ' '
}

# JFR - do not prequote filename (Join-Arguments will)
$startFlightRecording = "-XX:StartFlightRecording=name=uci,settings=profile,duration=$JfrDuration,filename=$jfrFile"

$javaOpts = @()
$javaOpts += "-Xms$javaXms"
$javaOpts += "-Xmx$javaXmx"
if (-not [string]::IsNullOrWhiteSpace($javaGc)) {
    $javaOpts += (Convert-GcOption -Value $javaGc)
}
if (-not [string]::IsNullOrWhiteSpace($javaActiveProcessors)) {
    $javaOpts += "-XX:ActiveProcessorCount=$javaActiveProcessors"
}
$javaOpts += Split-OptionString -Value $javaExtraOpts
$javaOpts += "-Dchessengine.searchThreads=$chessThreads"
$javaOpts += "-Dchessengine.lazySmpThreads=$lazyThreads"
$javaOpts += "-Dchessengine.rootParallelLimit=$rootParLimit"
$javaOpts += '-Dlogging.level.root=INFO'

$allArgs = @()
$allArgs += $javaOpts
$allArgs += $startFlightRecording
$allArgs += '-jar'
$allArgs += $jarFullPath

$computedArgs = Join-Arguments -Arguments $allArgs
Write-Log -Channel 'info' -Message "java $computedArgs"

if ($DryRun) {
    Write-Host ''
    Write-Host '--- Dry Run ---'
    Write-Host "Command line that would be executed:"
    Write-Host "java $computedArgs"
    Write-Host "Log file would be: $logFile"
    Write-Host '---------------'
    $logWriter.Dispose()
    return
}

# ---------- Globals for process + async queues ----------
$process = $null
$stdoutSubscription = $null
$stderrSubscription = $null
$script:stdoutQueue = $null

# ---------- Async wait helper ----------
function Wait-ForRegexWithTimeout {
    param([string]$Pattern,[string]$Context,[int]$TimeoutMs = 0)
    $regex = if ([string]::IsNullOrWhiteSpace($Pattern)) { $null } else { [regex]$Pattern }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $line = $null
        if ($script:stdoutQueue -and $script:stdoutQueue.TryDequeue([ref]$line)) {
            if ($null -eq $regex) { return [PSCustomObject]@{ Line = $line } }
            $m = $regex.Match($line)
            if ($m.Success) { return [PSCustomObject]@{ Line = $line; Match = $m } }
            continue
        }
        if ($TimeoutMs -gt 0 -and $sw.ElapsedMilliseconds -ge $TimeoutMs) { return $null }
        if ($process -and $process.HasExited) {
            throw "Engine terminated unexpectedly while waiting for $Context ($Pattern)."
        }
        Start-Sleep -Milliseconds 10
    }
}

function Send-UciCommand {
    param([string]$Command)
    Write-LogAndMaybeConsole -Channel 'stdin' -Message $Command
    $process.StandardInput.WriteLine($Command)
    $process.StandardInput.Flush()
}

$moveHistory   = New-Object System.Collections.Generic.List[string]
$quitIssued    = $false
$bestMovePattern = '^bestmove\s+(\S+)'

try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'java'
    $psi.Arguments = $computedArgs
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi

    if (-not $process.Start()) { throw "Failed to start 'java' process. Is Java on PATH?" }

    # Wire up async handlers for stdout/stderr
    $script:stdoutQueue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()

    $stdoutSubscription = Register-ObjectEvent -InputObject $process -EventName OutputDataReceived -Action {
        param($sender,$eventArgs)
        $line = $eventArgs.Data
        if ($null -eq $line) { return }
        $script:stdoutQueue.Enqueue($line) | Out-Null
        Write-LogAndMaybeConsole -Channel 'stdout' -Message $line
    }

    $stderrSubscription = Register-ObjectEvent -InputObject $process -EventName ErrorDataReceived -Action {
        param($sender,$eventArgs)
        $line = $eventArgs.Data
        if ($null -eq $line) { return }
        Write-LogAndMaybeConsole -Channel 'stderr' -Message $line
    }

    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()

    # ---- Handshake using async queue ----
    Send-UciCommand -Command 'uci'
    if (-not (Wait-ForRegexWithTimeout -Pattern '^uciok$' -Context 'uci handshake' -TimeoutMs $HandshakeTimeoutMs)) {
        throw "Timeout waiting for uciok."
    }

    Send-UciCommand -Command 'isready'
    if (-not (Wait-ForRegexWithTimeout -Pattern '^readyok$' -Context 'engine readiness' -TimeoutMs $HandshakeTimeoutMs)) {
        throw "Timeout waiting for readyok."
    }

    Send-UciCommand -Command 'ucinewgame'
    Send-UciCommand -Command 'isready'
    if (-not (Wait-ForRegexWithTimeout -Pattern '^readyok$' -Context 'new game readiness' -TimeoutMs $HandshakeTimeoutMs)) {
        throw "Timeout waiting for readyok after ucinewgame."
    }

    # ---- Self-play loop ----
    for ($ply = 1; $ply -le $PlyCount; $ply++) {
        $positionCommand = 'position startpos'
        if ($moveHistory.Count -gt 0) { $positionCommand += ' moves ' + ($moveHistory -join ' ') }

        Send-UciCommand -Command $positionCommand
        Send-UciCommand -Command ("go movetime {0}" -f $MoveTimeMs)

        $bestMoveResult = Wait-ForRegexWithTimeout -Pattern $bestMovePattern `
            -Context ("bestmove response for ply $ply") `
            -TimeoutMs ([Math]::Max($MoveTimeMs * 5, 5000))

        if ($null -eq $bestMoveResult) {
            Send-UciCommand -Command 'stop'
            $bestMoveResult = Wait-ForRegexWithTimeout -Pattern $bestMovePattern `
                -Context ("bestmove (post-stop) for ply $ply") `
                -TimeoutMs 3000
        }

        if ($null -eq $bestMoveResult) {
            Write-Warning "No 'bestmove' received on ply $ply; aborting self-play."
            break
        }

        $bestMove = $bestMoveResult.Match.Groups[1].Value

        if ([string]::IsNullOrWhiteSpace($bestMove) -or $bestMove -eq '(none)') {
            Write-Host "Engine reported no legal moves on ply $ply. Stopping self-play loop."
            break
        }

        Write-Host ("Ply {0,2}: {1}" -f $ply, $bestMove)
        $moveHistory.Add($bestMove)

        Send-UciCommand -Command 'isready'
        if (-not (Wait-ForRegexWithTimeout -Pattern '^readyok$' -Context ("post-move readiness for ply $ply") -TimeoutMs $HandshakeTimeoutMs)) {
            throw "Timeout waiting for readyok after ply $ply."
        }
    }

    Send-UciCommand -Command 'quit'
    $quitIssued = $true
    $process.StandardInput.Close()

    if (-not $process.WaitForExit(10000)) {
        Write-Warning 'Engine did not exit within 10 seconds after quit; terminating the process.'
        $process.Kill()
        $process.WaitForExit()
    }
}
catch {
    Write-Error $_
    if ($process) {
        try {
            $stderrTail = ""
            $stdoutTail = ""
            try { $stderrTail = $process.StandardError.ReadToEnd() } catch {}
            try { $stdoutTail = $process.StandardOutput.ReadToEnd() } catch {}
            if ($stderrTail) { Write-Host "`n--- STDERR (tail) ---`n$stderrTail" }
            if ($stdoutTail) { Write-Host "`n--- STDOUT (tail) ---`n$stdoutTail" }
        } catch {}
        if (-not $process.HasExited) {
            try { if (-not $quitIssued) { $process.StandardInput.WriteLine('quit'); $process.StandardInput.Flush() } } catch {}
            try { $process.StandardInput.Close() } catch {}
            try { if (-not $process.WaitForExit(2000)) { $process.Kill(); $process.WaitForExit() } } catch {}
        }
        Write-Host "Process exit code: $($process.ExitCode)"
    }
    throw
}
finally {
    try { if ($process) { $process.CancelOutputRead() } } catch {}
    try { if ($process) { $process.CancelErrorRead() } } catch {}
    try {
        if ($stdoutSubscription) {
            Unregister-Event -SubscriptionId $stdoutSubscription.Id -ErrorAction SilentlyContinue
            Remove-Event -SourceIdentifier $stdoutSubscription.SourceIdentifier -ErrorAction SilentlyContinue
        }
    } catch {}
    try {
        if ($stderrSubscription) {
            Unregister-Event -SubscriptionId $stderrSubscription.Id -ErrorAction SilentlyContinue
            Remove-Event -SourceIdentifier $stderrSubscription.SourceIdentifier -ErrorAction SilentlyContinue
        }
    } catch {}
    $logWriter.Dispose()
}

$exitCode = if ($process) { $process.ExitCode } else { -1 }

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
