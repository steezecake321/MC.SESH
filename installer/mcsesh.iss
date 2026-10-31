; MC.SESH installer (Inno Setup 6). Build with ..\build-installer.ps1 (it builds dist\MC.SESH first, then runs ISCC).
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\MC.SESH"
#endif
#define AppName "MC.SESH"

[Setup]
AppId={{6F1D2C54-5E5A-4B0B-9C58-3D0E5C0F4A71}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Steeze Cake
VersionInfoVersion={#AppVersion}
VersionInfoDescription=MC.SESH - Minecraft Server Easy Self Hosting
DefaultDirName=C:\MC.SESH
UsePreviousAppDir=yes
DisableProgramGroupPage=yes
DisableWelcomePage=no
DisableDirPage=no
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; always dark; the images carry their own transparency (32-bit bitmaps)
WizardStyle=modern dark
WizardImageAlphaFormat=defined
WizardImageFile=..\brand\installer\welcome-164.bmp,..\brand\installer\welcome-328.bmp
WizardSmallImageFile=..\brand\installer\header-100.bmp,..\brand\installer\header-200.bmp
SetupIconFile=..\brand\mcsesh.ico
UninstallDisplayIcon={app}\mcsesh.ico
OutputDir=..\dist
OutputBaseFilename=MC.SESH-Setup-{#AppVersion}
Compression=lzma2/normal
SolidCompression=yes
CloseApplications=no
RestartApplications=no
UninstallDisplayName={#AppName} - Minecraft Server Easy Self Hosting

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel1=Minecraft Server Easy Self Hosting
WelcomeLabel2=MC.SESH runs and manages your Minecraft servers from one web page: create servers, add mods and packs, back them up, and let friends and family join on your home network.%n%nThis installer sets up MC.SESH on this PC. Your servers and settings are kept if you are updating.
WizardSelectDir=Where should MC.SESH be installed?
SelectDirDesc=MC.SESH itself is small (about 60 MB). Your servers and backups go on the next page.
WizardSelectTasks=Shortcuts and startup
SelectTasksDesc=Choose your shortcuts and whether MC.SESH starts with Windows.
SelectTasksLabel2=Tick what you want, then click Next.
FinishedHeadingLabel=MC.SESH is ready
FinishedLabel=Open it from the Start Menu or the desktop icon. The first time, open the page on this PC.

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"
Name: "startnone"; Description: "Don't start MC.SESH automatically"; GroupDescription: "Start MC.SESH with Windows:"; Flags: exclusive
Name: "startlogon"; Description: "When I sign in to Windows"; GroupDescription: "Start MC.SESH with Windows:"; Flags: exclusive unchecked
Name: "startboot"; Description: "When the PC starts, before anyone signs in"; GroupDescription: "Start MC.SESH with Windows:"; Flags: exclusive unchecked

[Files]
; the bundled Java: files that did not change keep their timestamp, so running servers' Java is never rewritten
Source: "{#SourceDir}\runtime\*"; DestDir: "{app}\runtime"; Flags: recursesubdirs createallsubdirs comparetimestamp
Source: "{#SourceDir}\*"; DestDir: "{app}"; Excludes: "runtime,Install.bat,Install-Text.bat,data,mcsesh.ico"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "..\brand\mcsesh.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\brand\installer\finish-164.bmp"; Flags: dontcopy
Source: "..\brand\installer\finish-328.bmp"; Flags: dontcopy

[Dirs]
Name: "{app}\data"

[Icons]
Name: "{autoprograms}\MC.SESH"; Filename: "{app}\Start.bat"; WorkingDir: "{app}"; IconFilename: "{app}\mcsesh.ico"; Comment: "Start MC.SESH and open it in your browser"; Flags: runminimized
Name: "{autodesktop}\MC.SESH"; Filename: "{app}\Start.bat"; WorkingDir: "{app}"; IconFilename: "{app}\mcsesh.ico"; Comment: "Start MC.SESH and open it in your browser"; Tasks: desktopicon; Flags: runminimized

[Run]
; settings, the owner account, the CurseForge key and the create-server defaults are written by CurStepChanged(ssPostInstall) below
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\tools\Set-Startup.ps1"" -Action firewall -InstallDir ""{app}"" -Port {code:GetPort} -FwRuleName ""MC.SESH"" -NoPause"; StatusMsg: "Opening the Windows Firewall for your home network..."; Check: WantFirewall; Flags: runhidden waituntilterminated
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\tools\Set-Startup.ps1"" -Action logon -InstallDir ""{app}"" -Port {code:GetPort} -TaskName ""MC.SESH"" -FwRuleName ""MC.SESH"" -NoPause"; StatusMsg: "Setting up start at sign-in..."; Tasks: startlogon; Flags: runhidden waituntilterminated
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\tools\Set-Startup.ps1"" -Action boot -AsSystem -InstallDir ""{app}"" -Port {code:GetPort} -TaskName ""MC.SESH"" -FwRuleName ""MC.SESH"" -NoPause"; StatusMsg: "Setting up start with the PC..."; Tasks: startboot; Flags: runhidden waituntilterminated
Filename: "{app}\Start.bat"; Description: "Start MC.SESH now and open it in my browser"; WorkingDir: "{app}"; Flags: postinstall nowait skipifsilent runasoriginaluser runminimized

[UninstallRun]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -Command ""& '{app}\tools\Stop-Manager.ps1' -InstallDir '{app}'; & '{app}\tools\Remove-Extras.ps1' -InstallDir '{app}'"""; Flags: runhidden waituntilterminated; RunOnceId: "RemoveMcseshExtras"

[Code]
const
  CF_URL = 'https://console.curseforge.com/';

function GetDriveTypeW(root: String): Cardinal; external 'GetDriveTypeW@kernel32.dll stdcall';
function SetEnvironmentVariableW(name, value: String): Integer; external 'SetEnvironmentVariableW@kernel32.dll stdcall';

var
  ServersPage: TInputDirWizardPage;
  PortPage: TInputQueryWizardPage;
  AccountPage: TInputQueryWizardPage;
  CfPage: TWizardPage;
  CfEdit: TNewEdit;
  DefaultsPage: TWizardPage;
  InfoPage: TOutputMsgMemoWizardPage;
  CbType, CbMem, CbDiff, CbMode: TNewComboBox;
  EdPlayers: TNewEdit;
  ChkBedrock, ChkJourney, ChkMods, ChkWhitelist, ChkAuto, ChkLan, ChkFirewall, ChkShowPw: TNewCheckBox;
  DriveLabel, PcLabel, StartNote: TNewStaticText;
  PhoneMemo: TNewMemo;
  RouterBtn: TNewButton;
  BackupsTouched, SettingFields, IsUpgrade, UpgradeKnown: Boolean;
  SuggestedServers, NetIp, NetGw, NetMac: String;
  RamGb: Integer;
  MemOpts: array of Integer;

{ ---------- small helpers ---------- }

function JsonEsc(s: String): String;
begin
  StringChangeEx(s, '\', '\\', True);
  StringChangeEx(s, '"', '\"', True);
  Result := s;
end;

function GbText(bytes: Int64): String;
begin
  Result := IntToStr(bytes div (1024 * 1024 * 1024)) + ' GB';
end;

function BoolText(b: Boolean): String;
begin
  if b then Result := 'true' else Result := 'false';
end;

function Field(s: String; index: Integer): String;
var
  i, p: Integer;
begin
  for i := 1 to index do
  begin
    p := Pos('|', s);
    if p = 0 then
    begin
      s := '';
      break;
    end;
    s := Copy(s, p + 1, Length(s));
  end;
  p := Pos('|', s);
  if p > 0 then s := Copy(s, 1, p - 1);
  Result := Trim(s);
end;

{ a full path such as D:\MC.Servers (or a network path) }
function IsFullPath(s: String): Boolean;
begin
  s := Trim(s);
  Result := ((Length(s) >= 3) and (s[2] = ':') and (s[3] = '\')) or ((Length(s) >= 3) and (Copy(s, 1, 2) = '\\'));
end;

function HasAccounts(dataDir: String): Boolean;
var
  r: TFindRec;
begin
  Result := False;
  if FindFirst(dataDir + '\accounts\*', r) then
  try
    repeat
      if (r.Name <> '.') and (r.Name <> '..') then Result := True;
    until Result or not FindNext(r);
  finally
    FindClose(r);
  end;
end;

procedure CheckUpgrade(dir: String);
begin
  IsUpgrade := FileExists(dir + '\data\config.json');
  UpgradeKnown := True;
end;

function ExistingPort: String;
var
  s: AnsiString;
  p: Integer;
begin
  Result := '';
  if FileExists(WizardDirValue + '\data\port.txt') and LoadStringFromFile(WizardDirValue + '\data\port.txt', s) then
  begin
    p := StrToIntDef(Trim(s), 0);
    if p > 0 then Result := IntToStr(p);
  end;
end;

{ this PC's address on the home network, the router's address, the adapter's MAC address, and the memory in GB }
procedure LoadNetInfo;
var
  script, outFile, line: String;
  rc: Integer;
  s: AnsiString;
begin
  NetIp := '';
  NetGw := '';
  NetMac := '';
  RamGb := 8;
  script := ExpandConstant('{tmp}\net.ps1');
  outFile := ExpandConstant('{tmp}\net.txt');
  SaveStringToFile(script,
    '$c = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" } | Select-Object -First 1' + #13#10 +
    '$r = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)' + #13#10 +
    '($c.IPv4Address.IPAddress + "|" + $c.IPv4DefaultGateway.NextHop + "|" + $c.NetAdapter.MacAddress + "|" + $r) | Set-Content -Encoding ascii -Path $args[0]', False);
  if Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -File "' + script + '" "' + outFile + '"', '', SW_HIDE, ewWaitUntilTerminated, rc) and
     LoadStringFromFile(outFile, s) then
  begin
    line := Trim(s);
    NetIp := Field(line, 0);
    NetGw := Field(line, 1);
    NetMac := Field(line, 2);
    RamGb := StrToIntDef(Field(line, 3), 8);
    if RamGb < 2 then RamGb := 8;
  end;
end;

{ the first port from 8585 up that nothing on this PC is listening on (an existing MC.SESH usually holds 8585) }
function FindFreePort: Integer;
var
  rc: Integer;
begin
  Result := 8585;
  if Exec('powershell.exe', '-NoProfile -Command "$p = 8585..8700 | Where-Object { -not (Get-NetTCPConnection -State Listen -LocalPort $_ -ErrorAction SilentlyContinue) } | Select-Object -First 1; exit ($p - 8500)"',
          '', SW_HIDE, ewWaitUntilTerminated, rc) and (rc >= 85) and (rc <= 200) then
    Result := 8500 + rc;
end;

function PortInUse(port: Integer): Boolean;
var
  rc: Integer;
begin
  Result := False;
  if Exec('powershell.exe', '-NoProfile -Command "if (Get-NetTCPConnection -State Listen -LocalPort ' + IntToStr(port) + ' -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }"',
          '', SW_HIDE, ewWaitUntilTerminated, rc) then
    Result := (rc = 1);
end;

function WantFirewall: Boolean;
begin
  Result := (not WizardSilent) and (not IsUpgrade) and ChkFirewall.Checked;
end;

{ a text label that grows to fit its words }
function Lbl(parent: TWinControl; text: String; top, left, width: Integer): TNewStaticText;
begin
  Result := TNewStaticText.Create(WizardForm);
  Result.Parent := parent;
  Result.Left := left;
  Result.Top := top;
  Result.Width := width;
  Result.AutoSize := False;
  Result.WordWrap := True;
  Result.Caption := text;
  Result.AutoSize := True;
end;

function Combo(parent: TWinControl; top, left, width: Integer; items: String): TNewComboBox;
var
  p: Integer;
  s: String;
begin
  Result := TNewComboBox.Create(WizardForm);
  Result.Parent := parent;
  Result.Left := left;
  Result.Top := top;
  Result.Width := width;
  Result.Style := csDropDownList;
  s := items;
  repeat
    p := Pos('|', s);
    if p = 0 then
    begin
      Result.Items.Add(s);
      s := '';
    end
    else
    begin
      Result.Items.Add(Copy(s, 1, p - 1));
      s := Copy(s, p + 1, Length(s));
    end;
  until s = '';
  Result.ItemIndex := 0;
end;

function Check(parent: TWinControl; text: String; top: Integer; on: Boolean): TNewCheckBox;
begin
  Result := TNewCheckBox.Create(WizardForm);
  Result.Parent := parent;
  Result.Caption := text;
  Result.Left := 0;
  Result.Top := top;
  Result.Width := parent.Width;
  Result.Checked := on;
end;

{ ---------- drives: free space and the suggestion for the servers folder ---------- }

procedure ScanDrives;
var
  c: Char;
  i, n: Integer;
  root, lines, only: String;
  free, total, best: Int64;
  bestRoot: String;
begin
  lines := '';
  only := '';
  n := 0;
  best := -1;
  bestRoot := 'C:\';
  for i := 0 to 22 do
  begin
    c := Chr(Ord('C') + i);
    root := c + ':\';
    if DirExists(root) and (GetDriveTypeW(root) = 3) and GetSpaceOnDisk64(root, free, total) then
    begin
      n := n + 1;
      only := c + ':';
      lines := lines + c + ':  ' + GbText(free) + ' free of ' + GbText(total) + '     ';
      if free > best then
      begin
        best := free;
        bestRoot := root;
      end;
    end;
  end;
  SuggestedServers := Copy(bestRoot, 1, 2) + '\MC.Servers';
  if n = 0 then
    DriveLabel.Caption := 'Free space could not be read.'
  else if n = 1 then
    DriveLabel.Caption := 'Free space: ' + lines + #13#10 + only + ' is your only drive. Worlds and backups can get big, so keep an eye on the space.'
  else
    DriveLabel.Caption := 'Free space: ' + lines + #13#10 + 'Suggested for servers: ' + Copy(bestRoot, 1, 2) + ' (most free space). Worlds and backups can get big, so a roomy drive is best.';
end;

procedure ServersChanged(Sender: TObject);
begin
  { backups follow the servers folder until the person picks their own place }
  if (not BackupsTouched) and (not SettingFields) then
  begin
    SettingFields := True;
    ServersPage.Values[1] := AddBackslash(Trim(ServersPage.Values[0])) + '_backups';
    SettingFields := False;
  end;
end;

procedure BackupsChanged(Sender: TObject);
begin
  if not SettingFields then BackupsTouched := True;
end;

{ ---------- port page: the phone note and the router help ---------- }

function PhoneAddress: String;
begin
  if NetIp <> '' then Result := NetIp else Result := GetComputerNameString;
end;

procedure UpdatePhoneNote;
var
  t: String;
begin
  t := 'MC.SESH can be used from your phone too! Once it''s running, open http://' + PhoneAddress + ':' + Trim(PortPage.Values[0]) +
    ' in your phone''s browser while on the same Wi-Fi.' + #13#10 + #13#10 +
    'Then, MC.SESH can even open like an app, with its own Home Screen icon.' + #13#10 +
    '   - iPhone (Safari): Share > Add to Home Screen.' + #13#10 +
    '   - Android (Chrome/Firefox): Menu > [on Firefox "...More"] > Add to Home screen.' + #13#10 + #13#10 +
    '*Add Firewall Rule must be ticked to use MC.SESH from other devices*';
  if NetIp <> '' then
    t := t + #13#10 + #13#10 +
      'If the address ever stops working, check the header in MC.SESH for the current one. To keep it from changing, enable DHCP Reservation or set a Static IP so your router will always give this PC the same address. (Recommended)';
  PhoneMemo.Lines.Text := t;
end;

procedure PortChanged(Sender: TObject);
begin
  UpdatePhoneNote;
end;

procedure OpenRouter(Sender: TObject);
var
  rc: Integer;
begin
  ShellExec('open', 'http://' + NetGw, '', '', SW_SHOWNORMAL, ewNoWait, rc);
end;

{ ---------- other pages ---------- }

procedure OpenCurseForge(Sender: TObject);
var
  rc: Integer;
begin
  ShellExec('open', CF_URL, '', '', SW_SHOWNORMAL, ewNoWait, rc);
end;

procedure CfLinkClicked(Sender: TObject);
begin
  OpenCurseForge(Sender);
end;

procedure SkipCurseForge(Sender: TObject);
begin
  CfEdit.Text := '';
  WizardForm.NextButton.OnClick(WizardForm.NextButton);
end;

procedure ShowPwClicked(Sender: TObject);
begin
  TPasswordEdit(AccountPage.Edits[1]).Password := not ChkShowPw.Checked;
  TPasswordEdit(AccountPage.Edits[2]).Password := not ChkShowPw.Checked;
end;

procedure TypeChanged(Sender: TObject);
begin
  if CbType.ItemIndex = 0 then
  begin
    ChkBedrock.Checked := False;
    ChkJourney.Checked := False;
    ChkMods.Checked := False;
    ChkMods.Enabled := False;
  end
  else
  begin
    { Geyser has no Forge build }
    if CbType.ItemIndex = 2 then ChkBedrock.Checked := False;
    ChkMods.Enabled := True;
    ChkMods.Checked := True;
  end;
end;

procedure JourneyClicked(Sender: TObject);
begin
  { JourneyMap is a mod, so a Vanilla server becomes Fabric }
  if ChkJourney.Checked and (CbType.ItemIndex = 0) then CbType.ItemIndex := 1;
  TypeChanged(Sender);
  if ChkJourney.Checked then ChkJourney.Checked := True;
end;

procedure BedrockClicked(Sender: TObject);
begin
  { Geyser and Floodgate are mods, so a Vanilla server becomes Fabric }
  if ChkBedrock.Checked and ((CbType.ItemIndex = 0) or (CbType.ItemIndex = 2)) then CbType.ItemIndex := 1;
  TypeChanged(Sender);
  if ChkBedrock.Checked then ChkBedrock.Checked := True;
end;

{ ---------- the wizard ---------- }

procedure InitializeWizard;
var
  w, y, i, mb: Integer;
  b: TNewButton;
  items, bul: String;
  t: TNewStaticText;
begin
  BackupsTouched := False;
  SettingFields := False;
  UpgradeKnown := False;
  IsUpgrade := False;
  LoadNetInfo;

  { welcome page: what MC.SESH does, as a short list }
  bul := #$2022 + '  ';
  WizardForm.WelcomeLabel2.Height := WizardForm.WelcomePage.ClientHeight - WizardForm.WelcomeLabel2.Top - ScaleY(6);
  WizardForm.WelcomeLabel2.Caption :=
    'MC.SESH runs and manages your Minecraft servers from one web page.' + #13#10 + #13#10 +
    bul + 'Turn your single-player worlds into custom servers!' + #13#10 +
    bul + 'Create and manage local servers: Vanilla, Fabric, Forge and NeoForge' + #13#10 +
    bul + 'Add mods and modpacks from Modrinth and CurseForge' + #13#10 +
    bul + 'Back up, schedule and restore your servers' + #13#10 +
    bul + 'Keep Fabric servers, mods and Minecraft up to date' + #13#10 +
    bul + 'Let friends and family join on your home network, each with their own login' + #13#10 +
    bul + 'Let Bedrock players join your Java servers' + #13#10 +
    bul + 'Show your servers under "LAN worlds" in Minecraft, so players on your network find them without typing an address' + #13#10 +
    bul + 'Use it from your phone or any browser on your network' + #13#10 +
    bul + 'Bring in servers you already have' + #13#10 + #13#10 +
    'Your servers and settings are kept if you are updating.';

  { the page header shows the MC.SESH logo (wide), not a square }
  w := ScaleX(100);
  WizardForm.WizardSmallBitmapImage.Width := w;
  WizardForm.WizardSmallBitmapImage.Left := WizardForm.MainPanel.Width - w - ScaleX(8);
  WizardForm.PageNameLabel.Width := WizardForm.WizardSmallBitmapImage.Left - WizardForm.PageNameLabel.Left - ScaleX(6);
  WizardForm.PageDescriptionLabel.Width := WizardForm.PageNameLabel.Width;

  { the last page says "brought to you by Steeze Cake" }
  ExtractTemporaryFile('finish-164.bmp');
  ExtractTemporaryFile('finish-328.bmp');
  if ScaleX(100) >= 150 then
    WizardForm.WizardBitmapImage2.Bitmap.LoadFromFile(ExpandConstant('{tmp}\finish-328.bmp'))
  else
    WizardForm.WizardBitmapImage2.Bitmap.LoadFromFile(ExpandConstant('{tmp}\finish-164.bmp'));

  { shortcuts and startup: what each startup choice does, and where to change it later }
  WizardForm.TasksList.Height := WizardForm.TasksList.Height - ScaleY(96);
  StartNote := Lbl(WizardForm.SelectTasksPage,
    'Not started automatically: you start MC.SESH yourself from the shortcut.' + #13#10 +
    'When I sign in: MC.SESH starts as soon as you sign in to Windows.' + #13#10 +
    'When the PC starts: servers come back after a reboot, even if nobody signs in. MC.SESH then runs as the Windows system account.' + #13#10 + #13#10 +
    'You can change this any time in MC.SESH under Settings > Start with Windows.',
    WizardForm.TasksList.Top + WizardForm.TasksList.Height + ScaleY(8), WizardForm.TasksList.Left, WizardForm.TasksList.Width);

  { servers + backups folders, with free space per drive }
  ServersPage := CreateInputDirPage(wpSelectDir, 'Where should your servers and backups go?',
    'New servers and their backups each get a folder.',
    'You can pick a different place each time you create a server. Backups go in "_backups" inside the servers folder unless you choose another place.', False, '');
  ServersPage.Add('Servers folder:');
  ServersPage.Add('Backups folder:');
  ServersPage.Edits[0].OnChange := @ServersChanged;
  ServersPage.Edits[1].OnChange := @BackupsChanged;
  DriveLabel := Lbl(ServersPage.Surface, 'x', ServersPage.Edits[1].Top + ServersPage.Edits[1].Height + ScaleY(14), 0, ServersPage.SurfaceWidth);
  ScanDrives;
  SettingFields := True;
  ServersPage.Values[0] := SuggestedServers;
  ServersPage.Values[1] := SuggestedServers + '\_backups';
  SettingFields := False;

  { port, firewall, phone and router help }
  PortPage := CreateInputQueryPage(ServersPage.ID, 'Web page port',
    'Which port should the MC.SESH web page use?',
    'The default is fine for most people. Use a number between 1024 and 65535.');
  PortPage.Add('Port:', False);
  PortPage.Values[0] := IntToStr(FindFreePort);
  y := PortPage.Edits[0].Top + PortPage.Edits[0].Height + ScaleY(10);
  ChkFirewall := Check(PortPage.Surface, 'Add Windows Firewall rule: allow other devices on my home network to open MC.SESH', y, True);
  ChkFirewall.Width := PortPage.SurfaceWidth;
  y := y + ScaleY(24);
  if NetIp <> '' then
  begin
    PcLabel := Lbl(PortPage.Surface, 'This PC:  ' + NetIp + '     MAC address: ' + NetMac, PortPage.SurfaceHeight - ScaleY(46), 0, PortPage.SurfaceWidth);
    RouterBtn := TNewButton.Create(WizardForm);
    RouterBtn.Parent := PortPage.Surface;
    RouterBtn.Caption := 'Open my router''s page (' + NetGw + ')';
    RouterBtn.Left := 0;
    RouterBtn.Top := PortPage.SurfaceHeight - ScaleY(26);
    RouterBtn.Width := ScaleX(230);
    RouterBtn.Height := ScaleY(23);
    RouterBtn.OnClick := @OpenRouter;
    t := Lbl(PortPage.Surface, 'If it does not open, check the sticker on the router.', RouterBtn.Top + ScaleY(5), ScaleX(240), PortPage.SurfaceWidth - ScaleX(240));
    PhoneMemo := TNewMemo.Create(WizardForm);
    PhoneMemo.Parent := PortPage.Surface;
    PhoneMemo.Top := y;
    PhoneMemo.Height := PcLabel.Top - y - ScaleY(6);
  end
  else
  begin
    PhoneMemo := TNewMemo.Create(WizardForm);
    PhoneMemo.Parent := PortPage.Surface;
    PhoneMemo.Top := y;
    PhoneMemo.Height := PortPage.SurfaceHeight - y;
  end;
  PhoneMemo.Left := 0;
  PhoneMemo.Width := PortPage.SurfaceWidth;
  PhoneMemo.ReadOnly := True;
  PhoneMemo.ScrollBars := ssVertical;
  PhoneMemo.WordWrap := True;
  PortPage.Edits[0].OnChange := @PortChanged;
  UpdatePhoneNote;

  { owner account }
  AccountPage := CreateInputQueryPage(PortPage.ID, 'Owner account',
    'Create the administrator account for MC.SESH.',
    'This is the account that controls MC.SESH itself; later you can invite other people with their own logins. Leave all three boxes empty to skip this and create it the first time you open MC.SESH.');
  AccountPage.Add('Username (3-32 letters, numbers, dot, dash, underscore):', False);
  AccountPage.Add('Password (8 or more characters):', True);
  AccountPage.Add('Repeat the password:', True);
  ChkShowPw := Check(AccountPage.Surface, 'Show password', AccountPage.Edits[2].Top + AccountPage.Edits[2].Height + ScaleY(10), False);
  ChkShowPw.OnClick := @ShowPwClicked;

  { CurseForge key }
  CfPage := CreateCustomPage(AccountPage.ID, 'CurseForge (optional)',
    'Lets MC.SESH search and install mods and modpacks from CurseForge as well as Modrinth.');
  t := Lbl(CfPage.Surface, 'CurseForge asks every app to use its own free API key. To get yours: sign in at console.curseforge.com, open "API keys", create a key, copy it and paste it below.' + #13#10 +
    'The key stays on this PC. You can skip this and add it later in MC.SESH under Settings > CurseForge.', 0, 0, CfPage.SurfaceWidth);
  y := t.Top + t.Height + ScaleY(8);
  { a link you can click: where to get the key }
  t := Lbl(CfPage.Surface, 'Get your free key here:  ' + CF_URL, y, 0, CfPage.SurfaceWidth);
  t.Font.Style := [fsUnderline];
  t.Font.Color := $FFB36F;
  t.Cursor := crHand;
  t.OnClick := @CfLinkClicked;
  y := t.Top + t.Height + ScaleY(12);
  t := Lbl(CfPage.Surface, 'Paste your CurseForge API key here:', y, 0, CfPage.SurfaceWidth);
  y := t.Top + t.Height + ScaleY(4);
  CfEdit := TNewEdit.Create(WizardForm);
  CfEdit.Parent := CfPage.Surface;
  CfEdit.Left := 0;
  CfEdit.Top := y;
  CfEdit.Width := CfPage.SurfaceWidth;
  y := y + CfEdit.Height + ScaleY(10);
  b := TNewButton.Create(WizardForm);
  b.Parent := CfPage.Surface;
  b.Caption := 'Open the CurseForge console';
  b.Left := 0;
  b.Top := y;
  b.Width := ScaleX(190);
  b.Height := ScaleY(23);
  b.OnClick := @OpenCurseForge;
  b := TNewButton.Create(WizardForm);
  b.Parent := CfPage.Surface;
  b.Caption := 'Skip for now';
  b.Left := ScaleX(200);
  b.Top := y;
  b.Width := ScaleX(110);
  b.Height := ScaleY(23);
  b.OnClick := @SkipCurseForge;
  y := y + ScaleY(34);
  Lbl(CfPage.Surface, 'MC.SESH checks the key during installation. If it does not work, you can enter it again later in Settings.', y, 0, CfPage.SurfaceWidth);

  { defaults that pre-fill the "create server" form }
  DefaultsPage := CreateCustomPage(CfPage.ID, 'Create-server defaults',
    'These only pre-fill the "create server" form. Global Defaults can be changed later in Settings.');
  y := 0;
  Lbl(DefaultsPage.Surface, 'Server type:', y + 3, 0, ScaleX(150));
  CbType := Combo(DefaultsPage.Surface, y, ScaleX(160), ScaleX(240), 'Vanilla (no mods)|Fabric (mods)|Forge (mods)|NeoForge (mods)');
  y := y + ScaleY(26);
  Lbl(DefaultsPage.Surface, 'Memory (max):', y + 3, 0, ScaleX(150));
  { only the memory sizes this PC can spare (all of its memory minus about 2 GB for Windows) }
  items := '';
  SetArrayLength(MemOpts, 0);
  mb := 2048;
  for i := 0 to 5 do
  begin
    case i of
      0: mb := 2048;
      1: mb := 4096;
      2: mb := 6144;
      3: mb := 8192;
      4: mb := 12288;
      5: mb := 16384;
    end;
    if (i = 0) or (mb div 1024 <= RamGb - 2) then
    begin
      SetArrayLength(MemOpts, GetArrayLength(MemOpts) + 1);
      MemOpts[GetArrayLength(MemOpts) - 1] := mb;
      if items <> '' then items := items + '|';
      items := items + IntToStr(mb div 1024) + ' GB';
    end;
  end;
  CbMem := Combo(DefaultsPage.Surface, y, ScaleX(160), ScaleX(110), items);
  CbMem.ItemIndex := 0;
  for i := 0 to GetArrayLength(MemOpts) - 1 do
    if MemOpts[i] = 4096 then CbMem.ItemIndex := i;
  Lbl(DefaultsPage.Surface, 'This PC has ' + IntToStr(RamGb) + ' GB of memory.', y + 3, ScaleX(280), ScaleX(140));
  y := y + ScaleY(26);
  Lbl(DefaultsPage.Surface, 'Difficulty:', y + 3, 0, ScaleX(150));
  CbDiff := Combo(DefaultsPage.Surface, y, ScaleX(160), ScaleX(240), 'Peaceful|Easy|Normal|Hard');
  CbDiff.ItemIndex := 1;
  y := y + ScaleY(26);
  Lbl(DefaultsPage.Surface, 'Game mode:', y + 3, 0, ScaleX(150));
  CbMode := Combo(DefaultsPage.Surface, y, ScaleX(160), ScaleX(240), 'Survival|Creative|Adventure|Spectator');
  y := y + ScaleY(26);
  Lbl(DefaultsPage.Surface, 'Max players:', y + 3, 0, ScaleX(150));
  EdPlayers := TNewEdit.Create(WizardForm);
  EdPlayers.Parent := DefaultsPage.Surface;
  EdPlayers.Left := ScaleX(160);
  EdPlayers.Top := y;
  EdPlayers.Width := ScaleX(60);
  EdPlayers.Text := '20';
  y := y + ScaleY(27);
  CbType.OnChange := @TypeChanged;
  ChkBedrock := Check(DefaultsPage.Surface, 'Let Bedrock players join (adds Geyser; Vanilla servers switch to Fabric)', y, False);
  ChkBedrock.OnClick := @BedrockClicked;
  y := y + ScaleY(21);
  ChkJourney := Check(DefaultsPage.Surface, 'Install JourneyMap (live map and waypoints; Vanilla servers switch to Fabric)', y, False);
  ChkJourney.OnClick := @JourneyClicked;
  y := y + ScaleY(21);
  ChkMods := Check(DefaultsPage.Surface, 'I usually add mods (open the mod picker right after creating a modded server)', y, False);
  ChkMods.Enabled := False;
  y := y + ScaleY(21);
  ChkWhitelist := Check(DefaultsPage.Surface, 'Turn the whitelist on', y, False);
  y := y + ScaleY(21);
  ChkAuto := Check(DefaultsPage.Surface, 'Start servers automatically when MC.SESH starts', y, False);
  y := y + ScaleY(21);
  ChkLan := Check(DefaultsPage.Surface, 'Show servers under LAN worlds in Minecraft (same network only; some Wi-Fi blocks it)', y, True);

  { what to expect the first time }
  InfoPage := CreateOutputMsgMemoPage(wpSelectTasks, 'What happens the first time you open MC.SESH',
    'Here is what to expect.',
    'Take your time. Nothing below happens during installation.',
    '1.  Sign in with your owner account. If you skipped creating it here, MC.SESH asks you to create it first.' + #13#10 + #13#10 +
    '2.  A welcome box appears. It shows the servers folder you chose here, which you can change. You will have the opportunity to search this PC for existing servers AND single-player worlds, and you choose which ones to bring in. You can press Skip on this box if you prefer.' + #13#10 + #13#10 +
    '3.  The Dashboard then asks you to create a new server, search this PC, or add an existing server. After your first server, you can always Create or Import more with the "+Add Server" button on the Dashboard.' + #13#10 + #13#10 +
    '4.  You will also have another opportunity to open the firewall so that the Dashboard can be accessed from phones and other PCs on your local network.');
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if UpgradeKnown and IsUpgrade then
    Result := (PageID = ServersPage.ID) or (PageID = PortPage.ID) or (PageID = AccountPage.ID) or (PageID = CfPage.ID) or
              (PageID = DefaultsPage.ID) or (PageID = InfoPage.ID)
  else if PageID = AccountPage.ID then
    Result := HasAccounts(WizardDirValue + '\data');
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = PortPage.ID then
  begin
    if ExistingPort <> '' then PortPage.Values[0] := ExistingPort;
  end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  p: Integer;
  u: String;
begin
  Result := True;
  if CurPageID = wpSelectDir then
    CheckUpgrade(WizardDirValue);
  if CurPageID = ServersPage.ID then
  begin
    if Trim(ServersPage.Values[1]) = '' then
      ServersPage.Values[1] := AddBackslash(Trim(ServersPage.Values[0])) + '_backups';
    if not IsFullPath(ServersPage.Values[0]) then
    begin
      MsgBox('Please enter a full folder path for your servers, starting with a drive letter, for example D:\MC.Servers', mbError, MB_OK);
      Result := False;
    end
    else if not IsFullPath(ServersPage.Values[1]) then
    begin
      MsgBox('Please enter a full folder path for your backups, starting with a drive letter, for example D:\MC.Servers\_backups', mbError, MB_OK);
      Result := False;
    end;
  end;
  if CurPageID = PortPage.ID then
  begin
    p := StrToIntDef(Trim(PortPage.Values[0]), 0);
    if (p < 1024) or (p > 65535) then
    begin
      MsgBox('Please enter a port number between 1024 and 65535.', mbError, MB_OK);
      Result := False;
    end
    else if PortInUse(p) then
      Result := MsgBox('Port ' + IntToStr(p) + ' is already in use on this PC (perhaps by another copy of MC.SESH). Use it anyway?', mbConfirmation, MB_YESNO) = IDYES;
  end;
  if CurPageID = AccountPage.ID then
  begin
    u := Trim(AccountPage.Values[0]);
    if (u <> '') or (AccountPage.Values[1] <> '') or (AccountPage.Values[2] <> '') then
    begin
      if (Length(u) < 3) or (Length(u) > 32) then
      begin
        MsgBox('The username must be 3 to 32 characters (letters, numbers, dot, dash or underscore). To create the account later instead, clear all three boxes.', mbError, MB_OK);
        Result := False;
      end
      else if Length(AccountPage.Values[1]) < 8 then
      begin
        MsgBox('The password must be at least 8 characters.', mbError, MB_OK);
        Result := False;
      end
      else if AccountPage.Values[1] <> AccountPage.Values[2] then
      begin
        MsgBox('The two passwords do not match.', mbError, MB_OK);
        Result := False;
      end;
    end;
  end;
  if CurPageID = DefaultsPage.ID then
  begin
    p := StrToIntDef(Trim(EdPlayers.Text), 0);
    if (p < 1) or (p > 500) then
    begin
      MsgBox('Max players must be a number from 1 to 500.', mbError, MB_OK);
      Result := False;
    end;
  end;
end;

{ ---------- values used by [Run] and the post-install step ---------- }

function GetPort(Param: String): String;
begin
  if IsUpgrade and (ExistingPort <> '') then Result := ExistingPort
  else Result := IntToStr(StrToIntDef(Trim(PortPage.Values[0]), 8585));
end;

function GetServersDir: String;
begin
  if IsUpgrade then Result := AddBackslash(ExpandConstant('{app}')) + 'servers'
  else Result := RemoveBackslashUnlessRoot(Trim(ServersPage.Values[0]));
end;

function TypeId: String;
begin
  case CbType.ItemIndex of
    1: Result := 'fabric';
    2: Result := 'forge';
    3: Result := 'neoforge';
  else Result := 'vanilla';
  end;
end;

function TypeIdFinal: String;
begin
  Result := TypeId;
  if (ChkBedrock.Checked or ChkJourney.Checked) and (Result = 'vanilla') then Result := 'fabric';
end;

function MemMb: Integer;
begin
  Result := 4096;
  if (CbMem.ItemIndex >= 0) and (CbMem.ItemIndex < GetArrayLength(MemOpts)) then Result := MemOpts[CbMem.ItemIndex];
end;

function ChoiceId(cb: TNewComboBox): String;
begin
  Result := Lowercase(cb.Text);
end;

function OptionsJson: String;
begin
  Result := '{"serversRoot":"' + JsonEsc(RemoveBackslashUnlessRoot(Trim(ServersPage.Values[0]))) + '",' +
    '"backupsRoot":"' + JsonEsc(RemoveBackslashUnlessRoot(Trim(ServersPage.Values[1]))) + '",' +
    '"createDefaults":{"type":"' + TypeIdFinal + '","bedrock":' + BoolText(ChkBedrock.Checked) + ',"journeymap":' + BoolText(ChkJourney.Checked) + ',"planMods":' + BoolText(ChkMods.Checked or (TypeIdFinal = 'vanilla')) +
    ',"difficulty":"' + ChoiceId(CbDiff) + '","gamemode":"' + ChoiceId(CbMode) + '","maxPlayers":' + IntToStr(StrToIntDef(Trim(EdPlayers.Text), 20)) +
    ',"whitelist":' + BoolText(ChkWhitelist.Checked) + ',"autostart":' + BoolText(ChkAuto.Checked) + ',"lanBroadcast":' + BoolText(ChkLan.Checked) + ',"maxMb":' + IntToStr(MemMb) + '}}';
end;

procedure WriteSettings;
var
  app, opt, java: String;
  rc: Integer;
  lines: TArrayOfString;
begin
  app := ExpandConstant('{app}');
  { 1. the settings file (port, bind, servers folder) }
  Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -File "' + app + '\Setup.ps1" -Silent -NoLaunch -InstallDir "' + app + '" -ServersDir "' +
       GetServersDir + '" -Port ' + GetPort(''), app, SW_HIDE, ewWaitUntilTerminated, rc);
  if IsUpgrade then Exit;

  { 2. folders, create-server defaults, CurseForge key and owner account (secrets travel as environment variables of this one process) }
  opt := app + '\data\install-options.json';
  SetArrayLength(lines, 1);
  lines[0] := OptionsJson;
  SaveStringsToUTF8FileWithoutBOM(opt, lines, False);
  SetEnvironmentVariableW('MCSESH_CF_KEY', Trim(CfEdit.Text));
  if Trim(AccountPage.Values[0]) <> '' then
  begin
    SetEnvironmentVariableW('MCSESH_OWNER_USER', Trim(AccountPage.Values[0]));
    SetEnvironmentVariableW('MCSESH_OWNER_PASS', AccountPage.Values[1]);
  end;
  java := app + '\runtime\bin\java.exe';
  rc := 0;
  if not Exec(java, '-Xmx96m -jar "' + app + '\app\mcmanager.jar" --provision "' + opt + '"', app, SW_HIDE, ewWaitUntilTerminated, rc) then rc := 1;
  SetEnvironmentVariableW('MCSESH_CF_KEY', '');
  SetEnvironmentVariableW('MCSESH_OWNER_USER', '');
  SetEnvironmentVariableW('MCSESH_OWNER_PASS', '');
  DeleteFile(opt);
  if rc <> 0 then
    SuppressibleMsgBox('MC.SESH is installed, but the owner account could not be created here. You can create it the first time you open MC.SESH.', mbInformation, MB_OK, IDOK);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then WriteSettings;
end;

{ the "Ready to Install" summary lists everything that was chosen (never the password) }
function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
var
  s, owner, cf, fw: String;
begin
  s := MemoDirInfo;
  if not IsUpgrade then
  begin
    s := s + NewLine + NewLine + 'Servers folder:' + NewLine + Space + Trim(ServersPage.Values[0]);
    s := s + NewLine + NewLine + 'Backups folder:' + NewLine + Space + Trim(ServersPage.Values[1]);
    if ChkFirewall.Checked then fw := 'Windows Firewall rule will be added' else fw := 'no Windows Firewall rule';
    s := s + NewLine + NewLine + 'Web page port:' + NewLine + Space + Trim(PortPage.Values[0]) + ' (' + fw + ')';
    if HasAccounts(WizardDirValue + '\data') then owner := 'An account already exists'
    else if Trim(AccountPage.Values[0]) <> '' then owner := Trim(AccountPage.Values[0]) + ' (administrator)'
    else owner := 'Will be created when you first open MC.SESH';
    s := s + NewLine + NewLine + 'Owner account:' + NewLine + Space + owner;
    if Trim(CfEdit.Text) <> '' then cf := 'Entered (checked during installation)' else cf := 'Skipped (add it later in Settings)';
    s := s + NewLine + NewLine + 'CurseForge key:' + NewLine + Space + cf;
    s := s + NewLine + NewLine + 'Create-server defaults:' + NewLine + Space + TypeIdFinal + ', ' + IntToStr(MemMb div 1024) + ' GB, ' + ChoiceId(CbDiff) + ', ' + ChoiceId(CbMode) + ', ' +
      Trim(EdPlayers.Text) + ' players' + NewLine + Space + 'Bedrock: ' + BoolText(ChkBedrock.Checked) + ', JourneyMap: ' + BoolText(ChkJourney.Checked) + ', whitelist: ' + BoolText(ChkWhitelist.Checked);
  end;
  if MemoTasksInfo <> '' then s := s + NewLine + NewLine + MemoTasksInfo;
  s := s + NewLine + NewLine + 'After installing, open MC.SESH in a browser on this PC to sign in and finish setting up.';
  Result := s;
end;

{ stop a running manager (never the Minecraft servers) before its files are replaced }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  stopper: String;
  code: Integer;
begin
  Result := '';
  if not UpgradeKnown then CheckUpgrade(WizardDirValue);
  stopper := WizardDirValue + '\tools\Stop-Manager.ps1';
  if FileExists(stopper) then
  begin
    if not Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -File "' + stopper + '" -InstallDir "' + WizardDirValue + '"',
                '', SW_HIDE, ewWaitUntilTerminated, code) or (code <> 0) then
      Result := 'The running MC.SESH could not be stopped, so its files cannot be replaced. Close it (or approve the Windows prompt) and run this installer again.';
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usDone then
    MsgBox('MC.SESH has been removed. Your Minecraft servers and the settings in the data folder were left in place; delete the folders yourself if you no longer want them.', mbInformation, MB_OK);
end;
