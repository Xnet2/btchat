# בונה את BTChat.exe עם המהדר של ‎.NET Framework שמגיע עם Windows.
param([string]$Out = (Join-Path $PSScriptRoot 'BTChat.exe'))
$ErrorActionPreference = 'Stop'
$fw = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319"
$csc = "$fw\csc.exe"
$src = Join-Path $PSScriptRoot 'BTChat.cs'
$ico = Join-Path $env:TEMP 'BTChat.ico'
$refs = @("/r:$fw\System.Web.Extensions.dll", '/r:System.Windows.Forms.dll', '/r:System.Drawing.dll', '/r:System.Core.dll', '/r:System.Management.dll',
  "/r:$fw\WPF\PresentationCore.dll", "/r:$fw\WPF\WindowsBase.dll", "/r:$fw\System.Xaml.dll")

# שלב 1: בנייה בלי אייקון, כדי שהתוכנה תצייר את האייקון של עצמה
$tmp = Join-Path $env:TEMP 'BTChat-noicon.exe'
& $csc /nologo /target:winexe /platform:anycpu /optimize+ /codepage:65001 "/out:$tmp" $refs $src
if ($LASTEXITCODE) { throw 'csc' }
Start-Process $tmp -ArgumentList '/icon', "`"$ico`"" -Wait

# שלב 2: הבנייה הסופית עם האייקון
& $csc /nologo /target:winexe /platform:anycpu /optimize+ /codepage:65001 "/win32icon:$ico" "/out:$Out" $refs $src
if ($LASTEXITCODE) { throw 'csc' }
Remove-Item $tmp -ErrorAction SilentlyContinue
(Get-Item $Out).Length
