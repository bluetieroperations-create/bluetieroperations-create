<#
.SYNOPSIS
  Turns adb commands into HTTP endpoints, so the phone can drive an Android TV
  without speaking ADB itself.

.DESCRIPTION
  The phone's dispatcher sends plain HTTP. Nothing on a home network answers
  adb over HTTP, so this bridges the two: it listens on a port and runs the adb
  command matching the path.

  Deliberately a stopgap. It means a PC has to be awake for a gesture to work,
  which is the wrong shape for a remote control. It exists so the gesture can
  be proved end to end before deciding whether that is worth an ADB client
  inside the app, or an implementation of the Android TV Remote v2 protocol,
  which needs neither a PC nor developer mode on the TV.

.EXAMPLE
  .\adb-relay.ps1 -TvAddress 192.168.0.12:5555 -Port 8080

  Then the phone's target action becomes:
    http://<this-pc-ip>:8080/wake
    http://<this-pc-ip>:8080/toggle   (on if off, off if on)
#>
param(
    [string]$TvAddress = "192.168.0.12:5555",
    [int]$Port = 8080,
    [string]$AdbPath = "C:\platform-tools\adb.exe"
)

# Path -> key event. Add rows here; the phone needs no changes to use them.
$Routes = @{
    "/wake"       = "KEYCODE_WAKEUP"
    "/sleep"      = "KEYCODE_SLEEP"
    "/home"       = "KEYCODE_HOME"
    "/back"       = "KEYCODE_BACK"
    "/play-pause" = "KEYCODE_MEDIA_PLAY_PAUSE"
    "/volume-up"  = "KEYCODE_VOLUME_UP"
    "/volume-down" = "KEYCODE_VOLUME_DOWN"
    "/mute"       = "KEYCODE_VOLUME_MUTE"
}

if (-not (Test-Path $AdbPath)) {
    Write-Error "adb not found at $AdbPath. Pass -AdbPath with the right location."
    exit 1
}

Write-Host "Connecting to $TvAddress ..."
& $AdbPath connect $TvAddress | Write-Host

$listener = New-Object System.Net.HttpListener
# '+' binds every interface, so the phone can reach it. Needs an elevated
# shell on Windows; run this as Administrator.
$listener.Prefixes.Add("http://+:$Port/")

try {
    $listener.Start()
} catch {
    Write-Error "Could not listen on port $Port. Run PowerShell as Administrator, or pick another port."
    exit 1
}

Write-Host ""
Write-Host "Relay listening on port $Port. Routes:" -ForegroundColor Green
$Routes.Keys | Sort-Object | ForEach-Object { Write-Host "  http://<this-pc-ip>:$Port$_  ->  $($Routes[$_])" }
Write-Host "  http://<this-pc-ip>:$Port/toggle  ->  SLEEP or WAKEUP, whichever the TV is not"
Write-Host ""
Write-Host "Find this PC's address with:  ipconfig" -ForegroundColor Yellow
Write-Host "Press Ctrl+C to stop." -ForegroundColor Yellow
Write-Host ""

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $path = $context.Request.Url.AbsolutePath.TrimEnd('/')
        if ($path -eq "") { $path = "/" }

        $keyCode = $Routes[$path]
        $stamp = Get-Date -Format "HH:mm:ss"

        # /toggle asks the television what it is doing before deciding. A snap
        # should mean "the TV", not "the TV, if you remember which way it is" -
        # and KEYCODE_POWER, the obvious toggle, is unreliable on Android TV:
        # some sets open a menu with it, some ignore it when asleep.
        #
        # Reading the state and sending WAKEUP or SLEEP keeps both halves
        # idempotent, so a lost or doubled request cannot leave the set
        # inverted the way a blind toggle can.
        if ($path -eq "/toggle") {
            & $AdbPath connect $TvAddress | Out-Null
            $power = (& $AdbPath -s $TvAddress shell dumpsys power) -join "`n"

            $awake = $null
            if ($power -match "mWakefulness=(\w+)") {
                $awake = ($matches[1] -eq "Awake")
            } else {
                # Older or trimmed-down builds may not report mWakefulness.
                $display = (& $AdbPath -s $TvAddress shell dumpsys display) -join "`n"
                if ($display -match "mScreenState=(\w+)") { $awake = ($matches[1] -eq "ON") }
            }

            if ($null -eq $awake) {
                Write-Host "$stamp  /toggle -> could not read power state" -ForegroundColor Red
                $body = [System.Text.Encoding]::UTF8.GetBytes("could not read power state`n")
                $context.Response.StatusCode = 503
            } else {
                $keyCode = if ($awake) { "KEYCODE_SLEEP" } else { "KEYCODE_WAKEUP" }
                Write-Host "$stamp  /toggle -> $keyCode (was $(if ($awake) { 'awake' } else { 'asleep' }))"
                & $AdbPath -s $TvAddress shell input keyevent $keyCode | Out-Null
                $body = [System.Text.Encoding]::UTF8.GetBytes("$keyCode sent`n")
                $context.Response.StatusCode = 200
            }
        } elseif ($keyCode) {
            Write-Host "$stamp  $path -> $keyCode"
            # Re-issued each time: the TV drops the connection when it sleeps,
            # and a reconnect is cheap next to a gesture that silently does
            # nothing.
            & $AdbPath connect $TvAddress | Out-Null
            & $AdbPath -s $TvAddress shell input keyevent $keyCode | Out-Null

            $body = [System.Text.Encoding]::UTF8.GetBytes("$keyCode sent`n")
            $context.Response.StatusCode = 200
        } else {
            Write-Host "$stamp  $path -> no such route" -ForegroundColor Red
            $body = [System.Text.Encoding]::UTF8.GetBytes("no route for $path`n")
            $context.Response.StatusCode = 404
        }

        $context.Response.ContentType = "text/plain"
        $context.Response.OutputStream.Write($body, 0, $body.Length)
        $context.Response.Close()
    }
} finally {
    $listener.Stop()
    Write-Host "Relay stopped."
}
