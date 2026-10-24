using System.Buffers.Binary;
using System.Net;
using System.Text;

namespace Manette.Network.Mdns;

public static class DnsType
{
    public const ushort A = 1;
    public const ushort PTR = 12;
    public const ushort TXT = 16;
    public const ushort SRV = 33;
    public const ushort ANY = 255;
}

public sealed record DnsQuestion(string Name, ushort Type, ushort Class);

/// <summary>A parsed resource record. Only the fields relevant to its type are filled.</summary>
public sealed record DnsRecord(
    string Name, ushort Type, ushort Class, uint Ttl,
    string? PtrTarget = null,
    string? SrvTarget = null, ushort SrvPort = 0,
    IPAddress? Address = null,
    IReadOnlyList<string>? Txt = null);

/// <summary>A deliberately small DNS message reader: just what mDNS service discovery needs. Never throws on bad input.</summary>
public sealed class DnsMessage
{
    public ushort Id { get; private set; }
    public ushort Flags { get; private set; }
    public bool IsResponse => (Flags & 0x8000) != 0;
    public List<DnsQuestion> Questions { get; } = new();
    public List<DnsRecord> Answers { get; } = new();
    public List<DnsRecord> Additionals { get; } = new();

    public static bool TryParse(ReadOnlySpan<byte> m, out DnsMessage? message)
    {
        message = null;
        if (m.Length < 12) return false;
        var msg = new DnsMessage { Id = BinaryPrimitives.ReadUInt16BigEndian(m), Flags = BinaryPrimitives.ReadUInt16BigEndian(m[2..]) };
        int qd = BinaryPrimitives.ReadUInt16BigEndian(m[4..]);
        int an = BinaryPrimitives.ReadUInt16BigEndian(m[6..]);
        int ns = BinaryPrimitives.ReadUInt16BigEndian(m[8..]);
        int ar = BinaryPrimitives.ReadUInt16BigEndian(m[10..]);
        if (qd + an + ns + ar > 256) return false;

        int pos = 12;
        for (int i = 0; i < qd; i++)
        {
            if (!TryReadName(m, ref pos, out var name) || pos + 4 > m.Length) return false;
            msg.Questions.Add(new DnsQuestion(
                name, BinaryPrimitives.ReadUInt16BigEndian(m[pos..]),
                (ushort)(BinaryPrimitives.ReadUInt16BigEndian(m[(pos + 2)..]) & 0x7FFF)));
            pos += 4;
        }
        for (int i = 0; i < an; i++)
            if (!TryReadRecord(m, ref pos, out var r)) return false; else msg.Answers.Add(r!);
        for (int i = 0; i < ns; i++)
            if (!TryReadRecord(m, ref pos, out _)) return false;
        for (int i = 0; i < ar; i++)
            if (!TryReadRecord(m, ref pos, out var r)) return false; else msg.Additionals.Add(r!);

        message = msg;
        return true;
    }

    private static bool TryReadRecord(ReadOnlySpan<byte> m, ref int pos, out DnsRecord? record)
    {
        record = null;
        if (!TryReadName(m, ref pos, out var name) || pos + 10 > m.Length) return false;
        ushort type = BinaryPrimitives.ReadUInt16BigEndian(m[pos..]);
        ushort cls = (ushort)(BinaryPrimitives.ReadUInt16BigEndian(m[(pos + 2)..]) & 0x7FFF);
        uint ttl = BinaryPrimitives.ReadUInt32BigEndian(m[(pos + 4)..]);
        int rdlen = BinaryPrimitives.ReadUInt16BigEndian(m[(pos + 8)..]);
        pos += 10;
        if (pos + rdlen > m.Length) return false;
        int end = pos + rdlen;
        int rp = pos;

        switch (type)
        {
            case DnsType.PTR:
                if (!TryReadName(m, ref rp, out var target)) return false;
                record = new DnsRecord(name, type, cls, ttl, PtrTarget: target);
                break;
            case DnsType.SRV:
                if (rdlen < 7) return false;
                ushort port = BinaryPrimitives.ReadUInt16BigEndian(m[(rp + 4)..]);
                rp += 6;
                if (!TryReadName(m, ref rp, out var srvTarget)) return false;
                record = new DnsRecord(name, type, cls, ttl, SrvTarget: srvTarget, SrvPort: port);
                break;
            case DnsType.A:
                if (rdlen != 4) return false;
                record = new DnsRecord(name, type, cls, ttl, Address: new IPAddress(m.Slice(rp, 4)));
                break;
            case DnsType.TXT:
                var strings = new List<string>();
                while (rp < end)
                {
                    int len = m[rp++];
                    if (rp + len > end) return false;
                    strings.Add(Encoding.UTF8.GetString(m.Slice(rp, len)));
                    rp += len;
                }
                record = new DnsRecord(name, type, cls, ttl, Txt: strings);
                break;
            default:
                record = new DnsRecord(name, type, cls, ttl);
                break;
        }
        pos = end;
        return true;
    }

    /// <summary>Reads a possibly-compressed name. Advances <paramref name="pos"/> past the name as it appears in place.</summary>
    internal static bool TryReadName(ReadOnlySpan<byte> m, ref int pos, out string name)
    {
        name = "";
        var sb = new StringBuilder();
        int p = pos, jumps = 0, endPos = -1;
        while (true)
        {
            if (p >= m.Length) return false;
            int len = m[p];
            if (len == 0)
            {
                p++;
                if (endPos < 0) endPos = p;
                break;
            }
            if ((len & 0xC0) == 0xC0)
            {
                if (p + 1 >= m.Length) return false;
                if (endPos < 0) endPos = p + 2;
                if (++jumps > 16) return false;
                p = ((len & 0x3F) << 8) | m[p + 1];
                continue;
            }
            if ((len & 0xC0) != 0) return false;
            p++;
            if (p + len > m.Length) return false;
            sb.Append(Encoding.UTF8.GetString(m.Slice(p, len))).Append('.');
            p += len;
        }
        pos = endPos;
        name = sb.Length > 0 ? sb.ToString(0, sb.Length - 1) : "";
        return true;
    }
}

/// <summary>Builds DNS response messages (uncompressed names, which every resolver accepts).</summary>
public sealed class DnsResponseBuilder
{
    private readonly List<byte[]> _answers = new();
    private readonly List<byte[]> _additionals = new();

    public const ushort CacheFlush = 0x8000;
    public const ushort ClassIN = 1;

    public DnsResponseBuilder AddPtr(bool additional, string name, string target, uint ttl) =>
        Add(additional, name, DnsType.PTR, ClassIN, ttl, EncodeName(target));

    public DnsResponseBuilder AddSrv(bool additional, string name, string target, ushort port, uint ttl)
    {
        var tn = EncodeName(target);
        var rd = new byte[6 + tn.Length];       // priority 0, weight 0
        BinaryPrimitives.WriteUInt16BigEndian(rd.AsSpan(4), port);
        tn.CopyTo(rd, 6);
        return Add(additional, name, DnsType.SRV, (ushort)(ClassIN | CacheFlush), ttl, rd);
    }

    public DnsResponseBuilder AddTxt(bool additional, string name, IEnumerable<string> strings, uint ttl)
    {
        var rd = new List<byte>();
        foreach (var s in strings)
        {
            var b = Encoding.UTF8.GetBytes(s);
            if (b.Length > 255) throw new ArgumentException("TXT string too long.");
            rd.Add((byte)b.Length);
            rd.AddRange(b);
        }
        if (rd.Count == 0) rd.Add(0);            // an empty TXT record is a single zero-length string
        return Add(additional, name, DnsType.TXT, (ushort)(ClassIN | CacheFlush), ttl, rd.ToArray());
    }

    public DnsResponseBuilder AddA(bool additional, string name, IPAddress address, uint ttl) =>
        Add(additional, name, DnsType.A, (ushort)(ClassIN | CacheFlush), ttl, address.GetAddressBytes());

    public byte[] Build(ushort id = 0)
    {
        var ms = new List<byte>();
        var header = new byte[12];
        BinaryPrimitives.WriteUInt16BigEndian(header, id);                 // 0 for multicast; echoes the query id for legacy unicast
        BinaryPrimitives.WriteUInt16BigEndian(header.AsSpan(2), 0x8400);   // response + authoritative
        BinaryPrimitives.WriteUInt16BigEndian(header.AsSpan(6), (ushort)_answers.Count);
        BinaryPrimitives.WriteUInt16BigEndian(header.AsSpan(10), (ushort)_additionals.Count);
        ms.AddRange(header);
        foreach (var r in _answers) ms.AddRange(r);
        foreach (var r in _additionals) ms.AddRange(r);
        return ms.ToArray();
    }

    private DnsResponseBuilder Add(bool additional, string name, ushort type, ushort cls, uint ttl, byte[] rdata)
    {
        var n = EncodeName(name);
        var rec = new byte[n.Length + 10 + rdata.Length];
        n.CopyTo(rec, 0);
        BinaryPrimitives.WriteUInt16BigEndian(rec.AsSpan(n.Length), type);
        BinaryPrimitives.WriteUInt16BigEndian(rec.AsSpan(n.Length + 2), cls);
        BinaryPrimitives.WriteUInt32BigEndian(rec.AsSpan(n.Length + 4), ttl);
        BinaryPrimitives.WriteUInt16BigEndian(rec.AsSpan(n.Length + 8), (ushort)rdata.Length);
        rdata.CopyTo(rec, n.Length + 10);
        (additional ? _additionals : _answers).Add(rec);
        return this;
    }

    internal static byte[] EncodeName(string name)
    {
        var bytes = new List<byte>();
        foreach (var label in name.Split('.', StringSplitOptions.RemoveEmptyEntries))
        {
            var b = Encoding.UTF8.GetBytes(label);
            if (b.Length is 0 or > 63) throw new ArgumentException($"Invalid DNS label '{label}'.");
            bytes.Add((byte)b.Length);
            bytes.AddRange(b);
        }
        bytes.Add(0);
        return bytes.ToArray();
    }

    /// <summary>A standard query for one name and type (used by FakeClient's discover command).</summary>
    public static byte[] BuildQuery(string name, ushort type)
    {
        var n = EncodeName(name);
        var q = new byte[12 + n.Length + 4];
        BinaryPrimitives.WriteUInt16BigEndian(q.AsSpan(4), 1);              // one question
        n.CopyTo(q, 12);
        BinaryPrimitives.WriteUInt16BigEndian(q.AsSpan(12 + n.Length), type);
        BinaryPrimitives.WriteUInt16BigEndian(q.AsSpan(14 + n.Length), ClassIN);
        return q;
    }
}
