using System.Runtime.InteropServices;

namespace Manette.Cli;

/// <summary>The QR code is drawn with ANSI colours. Windows Terminal supports them; the old console host needs them switched on.</summary>
internal static class TerminalAnsi
{
    public static bool Enable()
    {
        if (!OperatingSystem.IsWindows()) return true;
        try
        {
            var handle = GetStdHandle(-11);                          // STD_OUTPUT_HANDLE
            if (!GetConsoleMode(handle, out uint mode)) return false;
            return SetConsoleMode(handle, mode | 0x0004);            // ENABLE_VIRTUAL_TERMINAL_PROCESSING
        }
        catch { return false; }
    }

    [DllImport("kernel32.dll")] private static extern IntPtr GetStdHandle(int nStdHandle);
    [DllImport("kernel32.dll")] private static extern bool GetConsoleMode(IntPtr h, out uint mode);
    [DllImport("kernel32.dll")] private static extern bool SetConsoleMode(IntPtr h, uint mode);
}
