<#
.SINOSSI
    Disegna l'icona di Lectio (pc/Lectio/Risorse/lectio.ico): il simbolo di docs/aspetto.md, un quadrato
    arrotondato color accento (#3D4ED8) con tre barrette verticali bianche di altezza diversa (9, 16, 12 su 30).
    Un file .ico con dentro le immagini PNG da 16 a 256 pixel. Si rifa' solo se cambia il simbolo.

.ESEMPIO
    powershell -ExecutionPolicy Bypass -File pc\prove\crea-icona.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$radicePc = Split-Path -Parent $PSScriptRoot
$uscita = Join-Path $radicePc 'Lectio\Risorse\lectio.ico'
$dimensioni = @(16, 20, 24, 32, 40, 48, 64, 128, 256)

function Disegna([int]$lato) {
    $bmp = New-Object System.Drawing.Bitmap($lato, $lato, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    # Quadrato arrotondato: raggio 9 su 30, come nel prototipo.
    $r = [single]($lato * 9.0 / 30.0)
    $d = 2 * $r
    $m = [single]($lato - 0.5)
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc(0, 0, $d, $d, 180, 90)
    $p.AddArc($m - $d, 0, $d, $d, 270, 90)
    $p.AddArc($m - $d, $m - $d, $d, $d, 0, 90)
    $p.AddArc(0, $m - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    $accento = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0x3D, 0x4E, 0xD8))
    $g.FillPath($accento, $p)

    # Tre barrette bianche (altezze 9, 16, 12 su 30); sulle icone piccole un po' piu' larghe, per vederle.
    $larghezza = [Math]::Max(2.0, $lato * 3.4 / 30.0)
    $spazio = [Math]::Max(1.6, $lato * 3.0 / 30.0)
    $altezze = @(9.0, 16.0, 12.0)
    $totale = 3 * $larghezza + 2 * $spazio
    $x = ($lato - $totale) / 2.0
    $bianco = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    foreach ($h in $altezze) {
        $alt = $lato * $h / 30.0
        $y = ($lato - $alt) / 2.0
        $rb = [Math]::Min($larghezza / 2.0, $lato * 2.0 / 30.0)
        $b = New-Object System.Drawing.Drawing2D.GraphicsPath
        $db = [single](2 * $rb)
        $b.AddArc([single]$x, [single]$y, $db, $db, 180, 90)
        $b.AddArc([single]($x + $larghezza - $db), [single]$y, $db, $db, 270, 90)
        $b.AddArc([single]($x + $larghezza - $db), [single]($y + $alt - $db), $db, $db, 0, 90)
        $b.AddArc([single]$x, [single]($y + $alt - $db), $db, $db, 90, 90)
        $b.CloseFigure()
        $g.FillPath($bianco, $b)
        $b.Dispose()
        $x += $larghezza + $spazio
    }
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return ,$ms.ToArray()
}

$immagini = @()
foreach ($s in $dimensioni) { $immagini += ,(Disegna $s) }

$file = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($file)
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$dimensioni.Count)
$posizione = 6 + 16 * $dimensioni.Count
for ($i = 0; $i -lt $dimensioni.Count; $i++) {
    $s = $dimensioni[$i]
    $lato = if ($s -ge 256) { 0 } else { $s }
    $w.Write([byte]$lato); $w.Write([byte]$lato); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$immagini[$i].Length); $w.Write([uint32]$posizione)
    $posizione += $immagini[$i].Length
}
foreach ($img in $immagini) { $w.Write($img) }
$w.Flush()
New-Item -ItemType Directory -Force (Split-Path -Parent $uscita) | Out-Null
[System.IO.File]::WriteAllBytes($uscita, $file.ToArray())
Write-Host "Icona scritta: $uscita ($($file.Length) byte)"

# Anteprima a 256 per guardarla.
$anteprima = Join-Path $env:TEMP 'lectio-icona-256.png'
[System.IO.File]::WriteAllBytes($anteprima, $immagini[$immagini.Count - 1])
Write-Host "Anteprima: $anteprima"
