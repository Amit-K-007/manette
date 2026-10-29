using System.Diagnostics;
using System.Net.Sockets;
using Manette.Backend.ViGEm;
using Manette.Companion;
using Manette.Core.Sessions;
using Manette.Network;

namespace Manette.App;

/// <summary>The tray icon, its menu, and the engine behind it. Everything protocol-related lives in the tested libraries.</summary>
internal sealed class TrayContext : ApplicationContext
{
    private readonly NotifyIcon _tray;
    private readonly ManetteHost _host;
    private readonly ViGEmPadFactory _pads;
    private readonly ProtectedTrustedDeviceStore _store;
    private readonly SynchronizationContext _ui = SynchronizationContext.Current ?? new SynchronizationContext();
    private readonly string _dataDir = AppPaths.DataDirectory();
    private readonly ToolStripMenuItem _status = new() { Enabled = false };
    private readonly ToolStripMenuItem _phones = new("Connected phones");
    private readonly ToolStripMenuItem _trusted = new("Trusted phones");
    private readonly ToolStripMenuItem _autostart = new("Start with Windows") { CheckOnClick = true };
    private PairingForm? _pairing;

    public TrayContext(ViGEmPadFactory pads)
    {
        _pads = pads;
        var settings = new SettingsStore(Path.Combine(_dataDir, "settings.json")).Load();
        _store = new ProtectedTrustedDeviceStore(
            Path.Combine(_dataDir, "trusted-devices.v2.json"), new DpapiProtector(), Path.Combine(_dataDir, "trusted-devices.json"));

        try
        {
            _host = new ManetteHost(pads, new ManetteHostOptions
            {
                Port = settings.Port,
                PcName = settings.PcName ?? Environment.MachineName,
                EnableMdns = settings.EnableMdns,
                InstallId = InstallIdStore.GetOrCreate(Path.Combine(_dataDir, "install-id")),
            }, _store);
        }
        catch (SocketException)
        {
            MessageBox.Show($"UDP port {settings.Port} is already in use (is another copy of Manette running?).\nChange the port in {Path.Combine(_dataDir, "settings.json")}.", "Manette");
            throw;
        }

        _host.Log += Log;
        _host.Server.DevicePaired += d => _ui.Post(_ => { Balloon($"Paired: {d.Name}"); _pairing?.ShowPaired(d.Name); RefreshMenus(); }, null);
        _host.Sessions.SessionsChanged += (_, _) => _ui.Post(_ => RefreshMenus(), null);
        _host.Start();

        _autostart.Checked = Autostart.IsEnabled();
        _autostart.CheckedChanged += (_, _) => Autostart.Set(_autostart.Checked);

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("Manette") { Enabled = false, Font = new Font(SystemFonts.MenuFont ?? SystemFonts.DefaultFont, FontStyle.Bold) });
        menu.Items.Add(_status);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Pair a phone...", null, (_, _) => ShowPairing());
        menu.Items.Add(_phones);
        menu.Items.Add(_trusted);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_autostart);
        menu.Items.Add("Open data folder", null, (_, _) => Process.Start("explorer.exe", _dataDir));
        menu.Items.Add("Get the ViGEmBus driver", null, (_, _) => Process.Start(new ProcessStartInfo(Program.ViGEmReleases) { UseShellExecute = true }));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit", null, (_, _) => Quit());

        _tray = new NotifyIcon { Icon = TrayIcons.Idle, ContextMenuStrip = menu, Visible = true };
        _tray.DoubleClick += (_, _) => ShowPairing();
        RefreshMenus();
        Balloon($"Listening on UDP {_host.Port}. Double-click the icon to pair a phone.");
    }

    private void RefreshMenus()
    {
        var live = _host.Sessions.Snapshot();
        int active = live.Count(s => s.State == SessionState.Active);

        _status.Text = $"UDP {_host.Port}  |  {live.Count} phone(s)  |  discovery {(_host.MdnsRunning ? "on" : "off")}";
        _tray.Icon = active > 0 ? TrayIcons.Active : TrayIcons.Idle;
        string tip = active > 0 ? $"Manette: {active} phone(s) connected" : "Manette: waiting for a phone";
        _tray.Text = tip.Length > 63 ? tip[..63] : tip;

        _phones.DropDownItems.Clear();
        if (live.Count == 0) _phones.DropDownItems.Add(new ToolStripMenuItem("(none)") { Enabled = false });
        foreach (var s in live)
        {
            var id = s.DeviceId;
            _phones.DropDownItems.Add($"{s.Name}  ({s.State}) - click to disconnect", null, (_, _) => _host.Sessions.Disconnect(id));
        }

        _trusted.DropDownItems.Clear();
        var trusted = _store.List();
        if (trusted.Count == 0) _trusted.DropDownItems.Add(new ToolStripMenuItem("(none)") { Enabled = false });
        foreach (var t in trusted)
        {
            var id = t.DeviceId;
            string name = t.Name;
            _trusted.DropDownItems.Add($"{name}  (paired {t.PairedAt.LocalDateTime:g}) - click to forget", null, (_, _) =>
            {
                if (MessageBox.Show($"Forget '{name}'? It will need a new code to connect again.", "Manette", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
                _host.Sessions.Disconnect(id);
                _store.Remove(id);
                RefreshMenus();
            });
        }
    }

    private void ShowPairing()
    {
        if (_pairing is { IsDisposed: false }) { _pairing.Activate(); return; }
        _pairing = new PairingForm(_host);
        _pairing.FormClosed += (_, _) => _pairing = null;
        _pairing.Show();
    }

    private void Balloon(string text) => _tray.ShowBalloonTip(3000, "Manette", text, ToolTipIcon.Info);

    private void Log(string message)
    {
        try
        {
            string file = Path.Combine(_dataDir, "manette.log");
            if (File.Exists(file) && new FileInfo(file).Length > 1_000_000) File.Delete(file);
            File.AppendAllText(file, $"{DateTime.Now:s} {message}{Environment.NewLine}");
        }
        catch (IOException) { }
    }

    private void Quit()
    {
        _tray.Visible = false;
        _pairing?.Close();
        _host.Dispose();
        _pads.Dispose();
        ExitThread();
    }
}
