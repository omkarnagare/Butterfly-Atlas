; The Windows installer of Butterfly Atlas, compiled by Inno Setup 6.7 or later. packaging/scripts/PackageApp.java runs
; it after the smoke test and passes every value that changes per build (/D...), so nothing is kept in two places; see
; the README, "Package it yourself". PackageApp's check-windows step checks the settings below that must never change.
; Plain ASCII only.

#if Ver < EncodeVer(6, 7, 0)
  #error Butterfly Atlas needs Inno Setup 6.7 or later. Update it (winget upgrade --id JRSoftware.InnoSetup -e), or put the newer ISCC.exe first on the PATH.
#endif
#ifndef AppName
  #error ButterflyAtlas.iss needs /DAppName=...; build it with the package command in the README.
#endif
#ifndef AppVersion
  #error ButterflyAtlas.iss needs /DAppVersion=...; build it with the package command in the README.
#endif
#ifndef AppPublisher
  #error ButterflyAtlas.iss needs /DAppPublisher=...; build it with the package command in the README.
#endif
#ifndef AppCopyright
  #error ButterflyAtlas.iss needs /DAppCopyright=...; build it with the package command in the README.
#endif
#ifndef ImageDir
  #error ButterflyAtlas.iss needs /DImageDir=...; build it with the package command in the README.
#endif
#ifndef IconFile
  #error ButterflyAtlas.iss needs /DIconFile=...; build it with the package command in the README.
#endif
#ifndef InfoFile
  #error ButterflyAtlas.iss needs /DInfoFile=...; build it with the package command in the README.
#endif
#ifndef NoticesFile
  #error ButterflyAtlas.iss needs /DNoticesFile=...; build it with the package command in the README.
#endif

[Setup]
; Never change the AppId: it is how Windows knows a newer installer replaces the installed one.
AppId={{1e9d8bc9-7c29-412c-8c72-a91e9f869f5a}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppCopyright={#AppCopyright}
; Per-user: no administrator rights, installed in %LOCALAPPDATA%\Programs\Butterfly_Atlas (no spaces), no folder page.
PrivilegesRequired=lowest
DefaultDirName={userpf}\Butterfly_Atlas
DisableDirPage=yes
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
WizardStyle=modern
; The first page is information only (the privacy statement, the licence, the notices): nothing to accept.
InfoBeforeFile={#InfoFile}
SetupIconFile={#IconFile}
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppName}.exe
; A running copy is named on the Preparing to Install page and closed only if the person agrees; the last page offers to
; start the new version instead of restarting the old one.
CloseApplications=yes
RestartApplications=no
Compression=lzma2
SolidCompression=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; The previous version's jars and runtime go first, so files of two versions never mix. The library, the config folder
; and the browser profile are elsewhere and never touched.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Packed into the installer only, for the first page's button (the app's folder gets its own copy from the image).
Source: "{#NoticesFile}"; Flags: dontcopy

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\{#AppName}.exe"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppName}.exe"; Tasks: desktopicon

[Run]
; Started with no arguments, like the Start Menu entry: the launch key never appears in a program's arguments.
Filename: "{app}\{#AppName}.exe"; Description: "Start {#AppName} now"; Flags: nowait postinstall skipifsilent

[Code]
// The first page names THIRD_PARTY_NOTICES.txt; this button shows it before anything is installed, from the copy
// packed into the installer, in the program that opens .txt files (Notepad unless the person chose another).
procedure NoticesButtonClick(Sender: TObject);
var
  ErrorCode: Integer;
begin
  ExtractTemporaryFile('THIRD_PARTY_NOTICES.txt');
  if not ShellExec('open', ExpandConstant('{tmp}\THIRD_PARTY_NOTICES.txt'), '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode) then
    MsgBox('The third-party notices could not be opened (' + SysErrorMessage(ErrorCode) + '). They are installed with '
      + 'Butterfly Atlas as THIRD_PARTY_NOTICES.txt in its folder.', mbError, MB_OK);
end;

procedure InitializeWizard;
var
  Button: TNewButton;
begin
  Button := TNewButton.Create(WizardForm);
  Button.Parent := WizardForm.InfoBeforePage;
  Button.Caption := 'Third-party notices...';
  Button.Width := ScaleX(150);
  Button.Height := ScaleY(23);
  // the text box gives up room at its foot, and the button sits there at the right, under the text
  WizardForm.InfoBeforeMemo.Height := WizardForm.InfoBeforeMemo.Height - Button.Height - ScaleY(8);
  Button.Left := WizardForm.InfoBeforeMemo.Left + WizardForm.InfoBeforeMemo.Width - Button.Width;
  Button.Top := WizardForm.InfoBeforeMemo.Top + WizardForm.InfoBeforeMemo.Height + ScaleY(8);
  Button.Anchors := [akRight, akBottom];
  Button.OnClick := @NoticesButtonClick;
end;
