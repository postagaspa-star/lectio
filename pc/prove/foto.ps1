<#
.SINOSSI
    Fa le foto delle pagine dell'interfaccia (pc/ui) in modalità prova (?prova=1: dati finti in memoria, nessuna
    chiamata al server) con Edge senza finestra, in chiaro e scuro, larghe 1280.

    - Le pagine le serve prove/foto/servi.js (Node) solo su 127.0.0.1, in sola lettura.
    - Edge parte con un profilo TEMPORANEO (--user-data-dir in una cartella nuova), mai quello di tutti i giorni;
      alla fine si chiudono solo i processi che hanno quel profilo nella riga di comando, e il profilo si cancella.

.ESEMPIO
    powershell -ExecutionPolicy Bypass -File pc\prove\foto.ps1
    powershell -ExecutionPolicy Bypass -File pc\prove\foto.ps1 -Uscita C:\Users\andre\lectio-build\pc\foto -Scene oggi,materia
#>
[CmdletBinding()]
param(
    [string]$Uscita = (Join-Path $env:USERPROFILE 'lectio-build\pc\foto'),
    [string[]]$Scene,
    [int]$Larghezza = 1280,
    [int]$Altezza = 860
)
$ErrorActionPreference = 'Stop'

$tutte = [ordered]@{
    '01_oggi' = 'oggi'
    '02_materia' = 'materia'
    '03_nuova_lezione' = 'nuova'
    '04_in_preparazione' = 'preparazione'
    '05_lettore_domanda' = 'lettore-domanda'
    '06_per_gemini' = 'gemini'
    '07_impostazioni' = 'impostazioni'
    '08_abbinamento' = 'abbinamento'
    '09_lettore' = 'lettore'
    '10_lettore_correzione' = 'lettore-correzione'
    '11_lettore_saltabili' = 'lettore-saltabili'
    '12_materie' = 'materie'
    '13_nuova_materia' = 'nuova-materia'
    '14_aggiungi' = 'aggiungi'
    '15_lezioni' = 'lezioni'
    '16_tetto' = 'tetto'
    '17_pronta' = 'pronta'
    '18_racconta' = 'racconta'
    '19_senza_rete' = 'senza-rete'
    '20_vuota' = 'vuota'
    '21_visita_passo1' = 'visita&passo=1'
    '22_visita_passo5' = 'visita&passo=5'
    '23_visita_passo8' = 'visita&passo=8'
}
if ($Scene) {
    # Con -File le scene arrivano come una stringa sola ("oggi,materia"): si dividono.
    $Scene = @($Scene | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $scelte = [ordered]@{}
    foreach ($k in $tutte.Keys) { if ($Scene -contains ($tutte[$k] -replace '&.*$', '') -or $Scene -contains $k) { $scelte[$k] = $tutte[$k] } }
    $tutte = $scelte
}

$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw 'Non trovo Microsoft Edge.' }
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { throw 'Serve Node per servire le pagine.' }

New-Item -ItemType Directory -Force $Uscita | Out-Null
$lavoro = Join-Path $env:TEMP ('lectio-foto-' + [guid]::NewGuid().ToString('N'))
$profilo = Join-Path $lavoro 'profilo-edge'
New-Item -ItemType Directory -Force $profilo | Out-Null
$portaFile = Join-Path $lavoro 'porta.txt'

$servi = Start-Process -FilePath $node -ArgumentList @("`"$(Join-Path $PSScriptRoot 'foto\servi.js')`"", '--porta', '0', '--per', '900') -RedirectStandardOutput $portaFile -PassThru -WindowStyle Hidden
try {
    $porta = $null
    for ($i = 0; $i -lt 50 -and -not $porta; $i++) {
        Start-Sleep -Milliseconds 100
        if (Test-Path $portaFile) { $porta = (Get-Content $portaFile -ErrorAction SilentlyContinue | Select-Object -First 1) }
    }
    if (-not $porta) { throw 'Il server delle pagine non è partito.' }

    foreach ($nome in $tutte.Keys) {
        foreach ($tema in @('chiaro', 'scuro')) {
            $file = Join-Path $Uscita ("${nome}_$tema.png")
            Remove-Item $file -ErrorAction SilentlyContinue
            $url = "http://127.0.0.1:$porta/index.html?prova=1&scena=$($tutte[$nome])&tema=$tema"
            $argomenti = @('--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run', '--no-default-browser-check', '--disable-extensions',
                '--mute-audio', "--user-data-dir=`"$profilo`"", "--window-size=$Larghezza,$Altezza", '--virtual-time-budget=6000', "--screenshot=`"$file`"", "`"$url`"")
            $p = Start-Process -FilePath $edge -ArgumentList $argomenti -PassThru -WindowStyle Hidden
            if (-not $p.WaitForExit(60000)) { Write-Warning "$nome ($tema): Edge non ha finito in tempo" }
            if (Test-Path $file) { Write-Host "  $([IO.Path]::GetFileName($file))" } else { Write-Warning "$nome ($tema): foto non fatta" }
        }
    }
}
finally {
    # Solo i processi avviati qui: il server delle pagine e gli Edge col profilo temporaneo.
    if ($servi -and -not $servi.HasExited) { Stop-Process -Id $servi.Id -Force -ErrorAction SilentlyContinue }
    $miei = Get-CimInstance Win32_Process -Filter "Name = 'msedge.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($profilo) }
    foreach ($m in $miei) { Stop-Process -Id $m.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
    Remove-Item -Recurse -Force $lavoro -ErrorAction SilentlyContinue
}
Write-Host "Foto in $Uscita" -ForegroundColor Green
