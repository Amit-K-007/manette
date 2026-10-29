using System.Text;
using System.Text.Json;
using Manette.Core.Protocol;

namespace Manette.Companion;

public sealed record AppSettings
{
    public int Port { get; init; } = ProtocolConstants.DefaultPort;
    /// <summary>Name shown on phones. Null means the Windows machine name.</summary>
    public string? PcName { get; init; }
    public bool EnableMdns { get; init; } = true;

    /// <summary>Repairs anything out of range instead of failing: a hand-edited settings file must never stop the app starting.</summary>
    public AppSettings Sanitized()
    {
        string? name = PcName?.Trim();
        if (string.IsNullOrEmpty(name)) name = null;
        else while (Encoding.UTF8.GetByteCount(name) > 63) name = name[..^1];
        return this with { Port = Port is >= 1 and <= 65535 ? Port : ProtocolConstants.DefaultPort, PcName = name };
    }
}

public static class AppPaths
{
    /// <summary>%APPDATA%\Manette (or the given override, which tests and --data-dir use).</summary>
    public static string DataDirectory(string? overrideDir = null) =>
        overrideDir ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Manette");
}

/// <summary>settings.json. Missing, empty or corrupt files give the defaults.</summary>
public sealed class SettingsStore(string path)
{
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true, PropertyNameCaseInsensitive = true };

    public AppSettings Load()
    {
        try
        {
            if (!File.Exists(path)) return new AppSettings();
            return (JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(path), Options) ?? new AppSettings()).Sanitized();
        }
        catch (Exception)
        {
            return new AppSettings();
        }
    }

    public void Save(AppSettings settings)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        AtomicFile.Write(path, JsonSerializer.Serialize(settings.Sanitized(), Options));
    }
}

/// <summary>The 8-hex id that identifies this PC installation (in the pairing link and the mDNS record). Created once, then stable.</summary>
public static class InstallIdStore
{
    public static string GetOrCreate(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                string id = File.ReadAllText(path).Trim();
                if (id.Length == 8 && id.All(Uri.IsHexDigit)) return id.ToLowerInvariant();
            }
        }
        catch (IOException) { }
        string created = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(4)).ToLowerInvariant();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        AtomicFile.Write(path, created);
        return created;
    }
}

internal static class AtomicFile
{
    /// <summary>Write to a temp file, then replace: a crash mid-write never leaves a half-written file behind.</summary>
    public static void Write(string path, string text)
    {
        string tmp = path + ".tmp";
        File.WriteAllText(tmp, text, new UTF8Encoding(false));
        File.Move(tmp, path, overwrite: true);
    }
}
