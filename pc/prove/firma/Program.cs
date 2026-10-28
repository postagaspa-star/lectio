using System.Security.Cryptography;

// Firma del pacchetto di Lectio per il computer: ECDSA P-256 con SHA-256, firma (r||s, 64 byte) in base64.
//   firma-lectio nuova-chiave <privata.pem>              crea la coppia; stampa SOLO la chiave pubblica (base64)
//   firma-lectio pubblica <privata.pem>                  stampa la chiave pubblica di una chiave già fatta
//   firma-lectio firma <file> <privata.pem> <uscita.sig> scrive la firma in base64
//   firma-lectio verifica <file> <file.sig> <pubblica-base64>
// La chiave privata non si stampa mai e non va nel repository.

if (args.Length < 2) return Uso();
try
{
    switch (args[0])
    {
        case "nuova-chiave":
        {
            var file = args[1];
            if (File.Exists(file)) { Console.Error.WriteLine("La chiave c'è già: non la sovrascrivo."); return 3; }
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(file))!);
            using var k = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            File.WriteAllText(file, k.ExportPkcs8PrivateKeyPem());
            Console.WriteLine(Convert.ToBase64String(k.ExportSubjectPublicKeyInfo()));
            return 0;
        }
        case "pubblica":
        {
            using var k = Carica(args[1]);
            Console.WriteLine(Convert.ToBase64String(k.ExportSubjectPublicKeyInfo()));
            return 0;
        }
        case "firma" when args.Length >= 4:
        {
            using var k = Carica(args[2]);
            using var dati = File.OpenRead(args[1]);
            var firma = k.SignData(dati, HashAlgorithmName.SHA256);
            File.WriteAllText(args[3], Convert.ToBase64String(firma));
            return 0;
        }
        case "verifica" when args.Length >= 4:
        {
            using var k = ECDsa.Create();
            k.ImportSubjectPublicKeyInfo(Convert.FromBase64String(args[3]), out _);
            using var dati = File.OpenRead(args[1]);
            var ok = k.VerifyData(dati, Convert.FromBase64String(File.ReadAllText(args[2]).Trim()), HashAlgorithmName.SHA256);
            Console.WriteLine(ok ? "firma valida" : "firma NON valida");
            return ok ? 0 : 1;
        }
        default:
            return Uso();
    }
}
catch (Exception e) when (e is IOException or CryptographicException or FormatException or UnauthorizedAccessException)
{
    Console.Error.WriteLine("Errore: " + e.Message);
    return 2;
}

static ECDsa Carica(string file)
{
    var k = ECDsa.Create();
    k.ImportFromPem(File.ReadAllText(file));
    return k;
}

static int Uso()
{
    Console.Error.WriteLine("uso: firma-lectio nuova-chiave|pubblica|firma|verifica ...");
    return 64;
}
