using System.Security.Cryptography;

namespace Manette.Core.Protocol;

/// <summary>Seam so tests can script "random" values and reproduce the byte-exact test vectors.</summary>
public interface IRandomSource
{
    void Fill(Span<byte> buffer);
}

public sealed class SecureRandomSource : IRandomSource
{
    public static readonly SecureRandomSource Instance = new();
    private SecureRandomSource() { }
    public void Fill(Span<byte> buffer) => RandomNumberGenerator.Fill(buffer);
}
