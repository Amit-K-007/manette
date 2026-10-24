using System.Text;
using Manette.Core.Protocol;

namespace Manette.Core.Pairing;

/// <summary>What a scanned QR code (or a parsed pairing link) tells the phone.</summary>
public sealed record PairingLink(
    byte Version, IReadOnlyList<string> Hosts, ushort Port, byte[] Secret, string Name);

/// <summary>Pairing secret presentation formats (docs/protocol.md section 4.3).</summary>
public static class PairingCode
{
    private const string Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /// <summary>RFC 4648 Base32, no padding. A 16-byte secret becomes 26 characters.</summary>
    public static string ToBase32(ReadOnlySpan<byte> data)
    {
        var sb = new StringBuilder((data.Length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        foreach (byte b in data)
        {
            buffer = (buffer << 8) | b;
            bits += 8;
            while (bits >= 5)
            {
                sb.Append(Alphabet[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) sb.Append(Alphabet[(buffer << (5 - bits)) & 31]);
        return sb.ToString();
    }

    /// <summary>Case-insensitive; dashes, spaces and '=' are ignored. Returns false on any other character.</summary>
    public static bool TryFromBase32(string text, out byte[] data)
    {
        var bytes = new List<byte>(ProtocolConstants.PairingSecretLength);
        int buffer = 0, bits = 0;
        foreach (char raw in text)
        {
            if (raw is '-' or ' ' or '=') continue;
            int v = Alphabet.IndexOf(char.ToUpperInvariant(raw));
            if (v < 0) { data = []; return false; }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8)
            {
                bytes.Add((byte)((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
            buffer &= (1 << bits) - 1;
        }
        data = bytes.ToArray();
        return true;
    }

    /// <summary>The typed fallback: Base32 in groups of four, e.g. AAAQ-EAYE-...-B4.</summary>
    public static string ToTypedCode(ReadOnlySpan<byte> secret)
    {
        string b32 = ToBase32(secret);
        var sb = new StringBuilder(b32.Length + b32.Length / 4);
        for (int i = 0; i < b32.Length; i += 4)
        {
            if (i > 0) sb.Append('-');
            sb.Append(b32, i, Math.Min(4, b32.Length - i));
        }
        return sb.ToString();
    }

    /// <summary>Parses a typed code into the 16-byte secret. Returns false unless exactly 16 bytes result.</summary>
    public static bool TryParseTypedCode(string text, out byte[] secret) =>
        TryFromBase32(text, out secret) && secret.Length == ProtocolConstants.PairingSecretLength;

    /// <summary>manette://pair?v=1&amp;h=a,b&amp;p=26760&amp;s=BASE32&amp;n=Name</summary>
    public static string BuildLink(
        IEnumerable<string> hosts, ushort port, ReadOnlySpan<byte> secret, string pcName) =>
        "manette://pair?v=" + ProtocolConstants.ProtocolVersion +
        "&h=" + string.Join(',', hosts.Select(Uri.EscapeDataString)) +
        "&p=" + port +
        "&s=" + ToBase32(secret) +
        "&n=" + Uri.EscapeDataString(pcName);

    public static bool TryParseLink(string text, out PairingLink? link)
    {
        link = null;
        const string prefix = "manette://pair?";
        if (!text.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;

        var query = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var part in text[prefix.Length..].Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int eq = part.IndexOf('=');
            if (eq <= 0) continue;
            query[part[..eq]] = Uri.UnescapeDataString(part[(eq + 1)..]);
        }

        if (!query.TryGetValue("v", out var v) || !byte.TryParse(v, out var version)) return false;
        if (!query.TryGetValue("p", out var p) || !ushort.TryParse(p, out var port)) return false;
        if (!query.TryGetValue("s", out var s) || !TryParseTypedCode(s, out var secret)) return false;
        if (!query.TryGetValue("h", out var h) || h.Length == 0) return false;
        query.TryGetValue("n", out var name);

        link = new PairingLink(
            version, h.Split(',', StringSplitOptions.RemoveEmptyEntries), port, secret, name ?? "");
        return true;
    }
}
