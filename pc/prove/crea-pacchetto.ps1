<#
.SINOSSI
    Costruisce il pacchetto del programma per il computer: pc/dist/lectio-pc.zip.

    Fa `dotnet publish` self-contained per win-x64 A CARTELLA (non single-file), come Sol e Pactum: niente
    eseguibile che si scompatta da solo. Lo zip contiene una cartella `Lectio\` con `Lectio.exe`, le dll del
    runtime .NET, la cartella `ui` (con il carattere Figtree), `OFL.txt` (la licenza del carattere) e
    `versione.json` (codice e nome da Versione.cs: sta dentro lo zip, quindi lo copre la firma).
    Il programma gira da lì e non parte da solo con Windows. Le versioni dopo le installa da sé nella
    stessa cartella, quando Lectio si chiude.

    Lo zip va messo sul server con la sua firma lectio-pc.zip.sig (GET /scarica/lectio-pc.zip e
    GET /scarica/lectio-pc.zip.sig); in server/app/versioni.json vanno
    `pc.codice`, `pc.nome`, `pc.sha256` (lo stampa questo script) e la dimensione in byte.
    Usa lo SDK .NET 8 scompattato in %LOCALAPPDATA%\Microsoft\dotnet-sdk-8 (o quello nel PATH).

    Questo file è salvato in UTF-8 con BOM: PowerShell 5.1 legge i .ps1 senza BOM come ANSI.

.ESEMPIO
    powershell -ExecutionPolicy Bypass -File pc\prove\crea-pacchetto.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$radicePc = Split-Path -Parent $PSScriptRoot            # ...\pc
$progetto = Join-Path $radicePc 'Lectio\Lectio.csproj'
$dist = Join-Path $radicePc 'dist'
$staging = Join-Path $dist 'Lectio'                     # diventa la cartella dentro lo zip
$zip = Join-Path $dist 'lectio-pc.zip'

$dotnet = Join-Path $env:LOCALAPPDATA 'Microsoft\dotnet-sdk-8\dotnet.exe'
if (-not (Test-Path $dotnet)) {
    $c = Get-Command dotnet -ErrorAction SilentlyContinue
    if (-not $c) { throw "Non trovo dotnet. Scompatta lo SDK .NET 8 in $env:LOCALAPPDATA\Microsoft\dotnet-sdk-8." }
    $dotnet = $c.Source
}

if (-not (Test-Path (Join-Path $radicePc 'ui\index.html'))) { throw "Manca pc\ui\index.html: senza interfaccia il pacchetto non serve." }
if (-not (Test-Path (Join-Path $radicePc 'ui\font\Figtree.ttf'))) { throw "Manca pc\ui\font\Figtree.ttf (da C:\Users\andre\sol-studio\font-figtree)." }
if (-not (Test-Path (Join-Path $radicePc 'ui\font\OFL.txt'))) { throw "Manca pc\ui\font\OFL.txt: la licenza del carattere va nel pacchetto." }

Write-Host "Pulizia..." -ForegroundColor Cyan
Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dist | Out-Null

$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_NOLOGO = '1'

Write-Host "Pubblicazione (self-contained, a cartella)..." -ForegroundColor Cyan
& $dotnet publish $progetto -c Release -r win-x64 "-p:PubblicazioneLectio=true" -o $staging
if ($LASTEXITCODE -ne 0) { throw "publish fallito (codice $LASTEXITCODE)" }

$exe = Join-Path $staging 'Lectio.exe'
if (-not (Test-Path $exe)) { throw "Lectio.exe non prodotto." }
if (-not (Test-Path (Join-Path $staging 'ui\index.html'))) { throw "La cartella ui non è finita accanto all'exe." }
if (-not (Test-Path (Join-Path $staging 'ui\font\Figtree.ttf'))) { throw "Il carattere non è finito nel pacchetto." }
if (-not (Test-Path (Join-Path $staging 'OFL.txt'))) { throw "OFL.txt non è finito accanto all'exe." }
if (-not (Test-Path (Join-Path $staging 'coreclr.dll'))) { throw "Manca coreclr.dll: la pubblicazione non è self-contained a cartella." }
$versione = (Get-Item $exe).VersionInfo.FileVersion

# versione.json: il programma installato accetta solo un pacchetto con un codice più alto del suo.
$testoVersione = Get-Content -Raw -Encoding UTF8 (Join-Path $radicePc 'Lectio\Versione.cs')
$nomeV = [regex]::Match($testoVersione, 'Nome = "([^"]+)"').Groups[1].Value
$codiceV = [regex]::Match($testoVersione, 'Codice = (\d+);').Groups[1].Value
if (-not $nomeV -or -not $codiceV) { throw "Non trovo Nome e Codice in Versione.cs." }
[IO.File]::WriteAllText((Join-Path $staging 'versione.json'), "{`"codice`": $codiceV, `"nome`": `"$nomeV`"}", (New-Object Text.UTF8Encoding $false))

Write-Host "Creo lo zip..." -ForegroundColor Cyan
Remove-Item $zip -ErrorAction SilentlyContinue
# -Path sulla cartella (senza \*): lo zip contiene la cartella Lectio\ con tutto dentro.
Compress-Archive -Path $staging -DestinationPath $zip -CompressionLevel Optimal

# La firma (ECDSA P-256): la chiave privata sta fuori dal repository e non si stampa mai. Il programma installato
# accetta una versione nuova solo se la firma è valida con la chiave pubblica che ha dentro.
Write-Host "Firmo lo zip..." -ForegroundColor Cyan
$chiave = Join-Path $env:USERPROFILE 'lectio-keys\pc-firma-privata.pem'
if (-not (Test-Path $chiave)) { throw "Manca la chiave di firma ($chiave): senza, il programma installato rifiuterebbe l'aggiornamento." }
$firmaProg = Join-Path $PSScriptRoot 'firma\Firma.csproj'
$sig = "$zip.sig"
Remove-Item $sig -ErrorAction SilentlyContinue
& $dotnet run --project $firmaProg -c Release -- firma $zip $chiave $sig
if ($LASTEXITCODE -ne 0) { throw "firma non riuscita (codice $LASTEXITCODE)" }
$pub = (Select-String -Path (Join-Path $radicePc 'Lectio\Motore\Aggiornamento.cs') -Pattern '"(MFkw[^"]+)"').Matches[0].Groups[1].Value
& $dotnet run --project $firmaProg -c Release -- verifica $zip $sig $pub
if ($LASTEXITCODE -ne 0) { throw "La firma non si verifica con la chiave pubblica del programma." }

$info = Get-Item $zip
$sha = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
$nFile = (Get-ChildItem $staging -Recurse -File).Count
Write-Host ""
Write-Host "Fatto: Lectio $versione" -ForegroundColor Green
Write-Host "  zip:    $zip"
Write-Host "  byte:   $($info.Length)"
Write-Host "  sha256: $sha"
Write-Host "  firma:  $sig ($((Get-Item $sig).Length) byte, sha256 $((Get-FileHash $sig -Algorithm SHA256).Hash.ToLowerInvariant()))"
Write-Host "  file nella cartella Lectio\: $nFile"
Write-Host ""
Write-Host "Prova rapida senza server (cartella dati temporanea, si chiude dopo 15 secondi, esito nel diario):" -ForegroundColor DarkGray
Write-Host "  & '$exe' --dati `"`$env:TEMP\lectio-prova`" --diagnosi --esci-dopo 15"
