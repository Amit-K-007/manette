using Manette.Core.Pairing;
using Manette.Network;
using Manette.QrCode;

namespace Manette.App;

/// <summary>The window with the QR code. Opening it opens a pairing window on the PC; closing it closes that window again.</summary>
internal sealed class PairingForm : Form
{
    private readonly ManetteHost _host;
    private readonly PictureBox _qr = new() { Size = new Size(320, 320), SizeMode = PictureBoxSizeMode.Zoom, Location = new Point(30, 20) };
    private readonly Label _info = new() { AutoSize = false, Size = new Size(340, 40), Location = new Point(20, 350), TextAlign = ContentAlignment.MiddleCenter };
    private readonly Label _code = new() { AutoSize = false, Size = new Size(360, 32), Location = new Point(10, 392), TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Consolas", 13f, FontStyle.Bold) };
    private readonly Label _expiry = new() { AutoSize = false, Size = new Size(340, 24), Location = new Point(20, 428), TextAlign = ContentAlignment.MiddleCenter };
    private readonly System.Windows.Forms.Timer _clock = new() { Interval = 500 };
    private DateTimeOffset _expires;
    private string _link = "";
    private bool _paired;

    public PairingForm(ManetteHost host)
    {
        _host = host;
        Text = "Pair a phone";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(380, 520);

        _info.Text = "Open Manette on the phone and scan this code,\nor type the code below.";
        var copy = new Button { Text = "Copy link", Size = new Size(110, 32), Location = new Point(20, 468) };
        var renew = new Button { Text = "New code", Size = new Size(110, 32), Location = new Point(135, 468) };
        var close = new Button { Text = "Close", Size = new Size(110, 32), Location = new Point(250, 468) };
        copy.Click += (_, _) => Clipboard.SetText(_link);
        renew.Click += (_, _) => NewCode();
        close.Click += (_, _) => Close();
        Controls.AddRange([_qr, _info, _code, _expiry, copy, renew, close]);

        _clock.Tick += (_, _) => UpdateExpiry();
        _clock.Start();
        NewCode();
    }

    private void NewCode()
    {
        _paired = false;
        var (offer, link) = _host.OpenPairing();
        _link = link;
        _expires = offer.ExpiresAt;
        _qr.Image = QrBitmap.Render(link, scale: 8);
        _code.Text = offer.TypedCode;
        _info.Text = "Open Manette on the phone and scan this code,\nor type the code below.";
        UpdateExpiry();
    }

    private void UpdateExpiry()
    {
        if (_paired) return;
        var left = _expires - DateTimeOffset.UtcNow;
        _expiry.Text = left > TimeSpan.Zero ? $"Valid for {left:m\\:ss} (single use)" : "This code has expired. Click New code.";
    }

    /// <summary>Called when a phone finishes pairing: confirm it, then close after a moment.</summary>
    public void ShowPaired(string name)
    {
        _paired = true;
        _info.Text = $"Paired: {name}";
        _expiry.Text = "You can close this window.";
        var closer = new System.Windows.Forms.Timer { Interval = 1800 };
        closer.Tick += (_, _) => { closer.Stop(); if (!IsDisposed) Close(); };
        closer.Start();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _clock.Stop();
        _host.Pairing.Close();                  // no pairing window stays open once the dialog is gone
        base.OnFormClosing(e);
    }
}

internal static class QrBitmap
{
    /// <summary>Black modules on white with the 4-module quiet zone the QR standard asks for.</summary>
    public static Bitmap Render(string text, int scale)
    {
        var m = QrMatrix.Encode(text);
        int n = m.GetLength(0), quiet = 4, side = (n + quiet * 2) * scale;
        var bmp = new Bitmap(side, side);
        using var g = Graphics.FromImage(bmp);
        g.Clear(Color.White);
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (m[x, y]) g.FillRectangle(Brushes.Black, (x + quiet) * scale, (y + quiet) * scale, scale, scale);
        return bmp;
    }
}
