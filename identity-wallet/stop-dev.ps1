<#
  IdentityWallet - stop the backend (8080) and the website (5173).
  Usage:  .\stop-dev.ps1     (or double-click stop-dev.cmd)
#>
$stopped = 0
foreach ($port in 8080, 5173) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($procId in ($conns | Select-Object -ExpandProperty OwningProcess -Unique)) {
        try {
            $p = Get-Process -Id $procId -ErrorAction Stop
            Stop-Process -Id $procId -Force
            Write-Host "  Stopped $($p.ProcessName) on port $port" -ForegroundColor Green
            $stopped++
        } catch { }
    }
}
if ($stopped -eq 0) { Write-Host '  Nothing was running on ports 8080 / 5173.' -ForegroundColor Yellow }
