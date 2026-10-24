using System.Security.Cryptography;
using System.Text;

namespace Manette.Core.Protocol;

/// <summary>Key derivation and message authentication (docs/protocol.md section 4).</summary>
public static class ManetteCrypto
{
    private static readonly byte[] PairSalt = Encoding.ASCII.GetBytes("manette-v1-pair");
    private static readonly byte[] SessionInfo = Encoding.ASCII.GetBytes("manette-v1-session");

    /// <summary>device_key = HKDF-SHA256(ikm = pairing_secret, salt = "manette-v1-pair", info = device_id).</summary>
    public static byte[] DeriveDeviceKey(ReadOnlySpan<byte> pairingSecret, ReadOnlySpan<byte> deviceId)
    {
        var key = new byte[ProtocolConstants.KeyLength];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, pairingSecret, key, PairSalt, deviceId);
        return key;
    }

    /// <summary>session_key = HKDF-SHA256(ikm = device_key, salt = client_nonce || server_nonce, info = "manette-v1-session").</summary>
    public static byte[] DeriveSessionKey(
        ReadOnlySpan<byte> deviceKey, ReadOnlySpan<byte> clientNonce, ReadOnlySpan<byte> serverNonce)
    {
        Span<byte> salt = stackalloc byte[clientNonce.Length + serverNonce.Length];
        clientNonce.CopyTo(salt);
        serverNonce.CopyTo(salt[clientNonce.Length..]);
        var key = new byte[ProtocolConstants.KeyLength];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, deviceKey, key, salt, SessionInfo);
        return key;
    }

    /// <summary>
    /// Writes the first <c>tag.Length</c> bytes of HMAC-SHA256(key, data || extra) into <paramref name="tag"/>.
    /// <paramref name="extra"/> is only used by WELCOME (it appends the HELLO's client_nonce).
    /// </summary>
    public static void ComputeTag(
        ReadOnlySpan<byte> key, ReadOnlySpan<byte> data, Span<byte> tag, ReadOnlySpan<byte> extra = default)
    {
        if (tag.Length is < 1 or > 32) throw new ArgumentOutOfRangeException(nameof(tag));
        Span<byte> hash = stackalloc byte[32];
        if (extra.IsEmpty)
        {
            HMACSHA256.HashData(key, data, hash);
        }
        else
        {
            int total = data.Length + extra.Length;
            if (total > 512) throw new ArgumentException("Message too large.");
            Span<byte> buf = stackalloc byte[total];
            data.CopyTo(buf);
            extra.CopyTo(buf[data.Length..]);
            HMACSHA256.HashData(key, buf, hash);
        }
        hash[..tag.Length].CopyTo(tag);
    }

    /// <summary>
    /// Verifies the trailing <paramref name="tagLength"/> bytes of <paramref name="packet"/>.
    /// Constant-time comparison. Returns false (never throws) for malformed input.
    /// </summary>
    public static bool VerifyTag(
        ReadOnlySpan<byte> key, ReadOnlySpan<byte> packet, int tagLength, ReadOnlySpan<byte> extra = default)
    {
        if (tagLength is < 1 or > 32 || packet.Length <= tagLength || packet.Length > 512) return false;
        Span<byte> expected = stackalloc byte[tagLength];
        ComputeTag(key, packet[..^tagLength], expected, extra);
        return CryptographicOperations.FixedTimeEquals(expected, packet[^tagLength..]);
    }
}
