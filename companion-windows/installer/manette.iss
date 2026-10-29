; Inno Setup 6 script for Manette. Build with installer\build.ps1 (publishes the app first).
; NOT RUN: written without Windows or Inno Setup available. Test an install and an uninstall on a clean machine.
#define AppName "Manette"
#define AppVersion "0.1.0"

[Setup]
AppId={{1541F684-77E6-441C-84CB-71E12FCA293D}
AppName={#AppName}
AppVersion={#AppVersion}
DefaultDirName={autopf}\Manette
DefaultGroupName=Manette
PrivilegesRequired=admin
OutputDir=output
OutputBaseFilename=Manette-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\Manette.exe

[Tasks]
Name: "startup"; Description: "Start Manette when I sign in to Windows"; Flags: unchecked

[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Manette"; Filename: "{app}\Manette.exe"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Manette"; ValueData: """{app}\Manette.exe"""; Tasks: startup; Flags: uninsdeletevalue

[Run]
; ONE inbound rule for the app itself: UDP only, every profile. That covers the control port and mDNS, and it stops
; Windows showing its "Allow access" prompt, which needs an administrator to answer and can be switched off by policy
; (the app would then be blocked silently). "profile=any" matters: a laptop moves between Private and Public networks,
; and a USB-tether adapter is often classed as Public.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Manette (program)"" dir=in action=allow program=""{app}\Manette.exe"" protocol=UDP profile=any enable=yes"; Flags: runhidden
Filename: "{app}\Manette.exe"; Description: "Start Manette now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Manette (program)"""; Flags: runhidden; RunOnceId: "DelFirewall1"
; Also remove the rules Windows itself created from its "Allow access" prompt for this program.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Manette"" program=""{app}\Manette.exe"""; Flags: runhidden; RunOnceId: "DelFirewall2"

[Code]
function ViGEmInstalled: Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\ViGEmBus');
end;

// ViGEmBus is retired and its bundled updater points at a domain the maintainers no longer control. Repoint it.
procedure FixViGEmUpdaterUrl;
var
  Ini: String;
  Raw: AnsiString;
  Text: String;
begin
  Ini := ExpandConstant('{commonpf64}\Nefarius Software Solutions\ViGEm Bus Driver\ViGEmBus_Updater.ini');
  if FileExists(Ini) and LoadStringFromFile(Ini, Raw) then
  begin
    Text := String(Raw);
    if StringChangeEx(Text, 'https://updates.vigem.org/', 'https://aiu.api.nefarius.systems/', True) > 0 then
      SaveStringToFile(Ini, AnsiString(Text), False);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ErrorCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    if ViGEmInstalled then
      FixViGEmUpdaterUrl
    else if MsgBox('Manette needs the ViGEmBus driver to create virtual Xbox controllers, and it is not installed.' + #13#10 +
                   'Open its download page now? Install it, then start Manette.', mbInformation, MB_YESNO) = IDYES then
      ShellExec('open', 'https://github.com/nefarius/ViGEmBus/releases', '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode);
  end;
end;
