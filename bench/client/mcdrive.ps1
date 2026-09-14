# Drive the running Minecraft client: focus it, send real keystrokes, capture the window.
#
# SendInput rather than PostMessage/SendKeys. GLFW reads the keyboard through the window message
# queue, and synthetic messages posted to a window can be dispatched in an order the library does not
# expect; SendInput injects at the same layer as the physical keyboard, so the game cannot tell the
# difference. It does require the window to be focused, which is why this raises it first.
#
# Scan codes (KEYEVENTF_SCANCODE) rather than virtual keys: Minecraft binds keys by scan code, so a
# virtual-key-only event can land on the wrong binding under a non-US layout.

param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [string]$Action = "screenshot",
    [string]$Text = "",
    [string]$OutFile = "shot.png",
    [int]$Key = 0,
    [int]$ClickX = 0,
    [int]$ClickY = 0
)

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class Win {
    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags;
                               public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit, Size = 40)]
    public struct INPUT { [FieldOffset(0)] public uint type; [FieldOffset(8)] public KEYBDINPUT ki; }

    public const uint INPUT_KEYBOARD = 1;
    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const uint KEYEVENTF_SCANCODE = 0x0008;
    public const uint KEYEVENTF_UNICODE = 0x0004;

    [DllImport("user32.dll", SetLastError = true)]
    public static extern uint SendInput(uint n, INPUT[] pInputs, int cbSize);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    // Without this, PowerShell is DPI-virtualised while the game is not: GetClientRect and
    // ClientToScreen come back in logical units while CopyFromScreen works in physical pixels, so
    // the capture lands offset from the window and every click misses by the scale factor.
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y,
                                                                   uint d, IntPtr extra);
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }

    // Both the virtual key AND the scan code. Scan-code-only events (KEYEVENTF_SCANCODE) were
    // accepted by Windows -- SendInput returned 1 with no error -- and the game ignored them:
    // measured here, T did not open chat. Sending wVk with the scan code alongside works, so the
    // event carries the virtual key the game's key handling actually reads.
    public static void Scan(ushort vk, bool up) {
        INPUT[] i = new INPUT[1];
        i[0].type = INPUT_KEYBOARD;
        i[0].ki.wVk = vk;
        i[0].ki.wScan = (ushort)MapVirtualKey(vk, 0);
        i[0].ki.dwFlags = up ? KEYEVENTF_KEYUP : 0;
        SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
    }

    // Text goes in as UNICODE events so the layout never matters -- chat is a text field, and this
    // is the one place where the character, not the physical key, is what we mean.
    public static void Unicode(char c, bool up) {
        INPUT[] i = new INPUT[1];
        i[0].type = INPUT_KEYBOARD;
        i[0].ki.wScan = (ushort)c;
        i[0].ki.dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0);
        SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
    }
}
'@

[Win]::SetProcessDPIAware() | Out-Null

$proc = Get-Process -Id $ProcessId -ErrorAction Stop
$hwnd = $proc.MainWindowHandle
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "NO_WINDOW"; exit 1 }

function Focus-Game {
    [Win]::ShowWindow($hwnd, 9) | Out-Null   # SW_RESTORE
    [Win]::SetForegroundWindow($hwnd) | Out-Null
    Start-Sleep -Milliseconds 400
    if ([Win]::GetForegroundWindow() -ne $hwnd) { Write-Output "WARN_NOT_FOREGROUND" }
}

function Press([uint16]$vk, [int]$holdMs = 40) {
    [Win]::Scan($vk, $false); Start-Sleep -Milliseconds $holdMs; [Win]::Scan($vk, $true)
    Start-Sleep -Milliseconds 60
}

function Type-Text([string]$s) {
    foreach ($ch in $s.ToCharArray()) {
        [Win]::Unicode($ch, $false); [Win]::Unicode($ch, $true)
        Start-Sleep -Milliseconds 18
    }
}

function Capture([string]$path) {
    Add-Type -AssemblyName System.Drawing
    $r = New-Object Win+RECT
    [Win]::GetClientRect($hwnd, [ref]$r) | Out-Null
    $tl = New-Object Win+POINT; $tl.X = 0; $tl.Y = 0
    [Win]::ClientToScreen($hwnd, [ref]$tl) | Out-Null
    $w = $r.R - $r.L; $h = $r.B - $r.T
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($tl.X, $tl.Y, 0, 0, (New-Object System.Drawing.Size $w, $h))
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Output "SHOT $path ${w}x${h}"
}

function Click([int]$cx, [int]$cy) {
    # Client-relative in, screen-absolute out: the screenshots this drives are of the client area,
    # so a coordinate read off a screenshot is usable here without arithmetic at the call site.
    $tl = New-Object Win+POINT; $tl.X = 0; $tl.Y = 0
    [Win]::ClientToScreen($hwnd, [ref]$tl) | Out-Null
    [Win]::SetCursorPos($tl.X + $cx, $tl.Y + $cy) | Out-Null
    Start-Sleep -Milliseconds 150
    [Win]::mouse_event([Win]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [Win]::mouse_event([Win]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 400
}

switch ($Action) {
    "click" { Focus-Game; Click $ClickX $ClickY; Start-Sleep -Milliseconds 600; Capture $OutFile }
    "screenshot" { Focus-Game; Capture $OutFile }
    "chat" {
        Focus-Game
        Press 0x54 40           # T -- open chat
        Start-Sleep -Milliseconds 500
        Type-Text $Text
        Start-Sleep -Milliseconds 250
        Press 0x0D 40           # Enter -- send
        Start-Sleep -Milliseconds 900
        Capture $OutFile
    }
    "key"    { Focus-Game; Press ([uint16]$Key); Start-Sleep -Milliseconds 500; Capture $OutFile }
    "type"   { Focus-Game; if ($ClickX -gt 0) { Click $ClickX $ClickY }; Type-Text $Text
               Start-Sleep -Milliseconds 700; Capture $OutFile }
    "escape" { Focus-Game; Press 0x1B; Start-Sleep -Milliseconds 500; Capture $OutFile }
}
