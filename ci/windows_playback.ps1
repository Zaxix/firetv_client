# Windows playback check: signs in to the public Jellyfin demo server, seeds Aurora's config exactly as
# the app stores it (token encrypted with DPAPI), then launches the packaged app on Home, a detail page
# and the player (mpv video window + transparent Compose controls) and takes screenshots.
# Best effort: skipped if the demo server is unreachable.
param([string]$Exe, [string]$Out = "screenshots")
$ErrorActionPreference = "Stop"
$base = "https://demo.jellyfin.org/stable"
New-Item -ItemType Directory -Force $Out | Out-Null
try { $info = Invoke-RestMethod "$base/System/Info/Public" -TimeoutSec 20 } catch { Write-Host "Demo server unavailable; skipping ($_)"; exit 0 }
Write-Host "Demo server: $($info.ServerName) $($info.Version)"

$auth = 'MediaBrowser Client="Aurora CI", Device="CI-Windows", DeviceId="aurora-ci-windows", Version="1.0.0"'
$login = Invoke-RestMethod -Method Post "$base/Users/AuthenticateByName" -Headers @{ Authorization = $auth } -ContentType "application/json" -Body '{"Username":"demo","Pw":""}'
$token = $login.AccessToken; $uid = $login.User.Id; $sid = $login.ServerId
Write-Host "::add-mask::$token"
$h = @{ Authorization = "$auth, Token=`"$token`"" }
$movie = (Invoke-RestMethod "$base/Items?userId=$uid&recursive=true&includeItemTypes=Movie&limit=1&sortBy=SortName" -Headers $h).Items[0]
Write-Host "Using movie: $($movie.Name)"

$dir = Join-Path $env:APPDATA "AuroraTV"
New-Item -ItemType Directory -Force $dir | Out-Null
$servers = @{ deviceId = "aurora-ci-windows"; lastServerId = $sid; servers = @(@{ id = $sid; name = $info.ServerName; url = $base; version = $info.Version; users = @(@{ id = $uid; name = $login.User.Name }); lastUserId = $uid }) }
$servers | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8NoBOM (Join-Path $dir "servers.json")
Add-Type -AssemblyName System.Security
$enc = [System.Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($token), $null, "CurrentUser")
@{ "token:${sid}:${uid}" = [Convert]::ToBase64String($enc) } | ConvertTo-Json | Set-Content -Encoding utf8NoBOM (Join-Path $dir "vault.json")
'{"theme":"Dark","subtitleMode":"Always","preferredSubtitleLanguage":"eng"}' | Set-Content -Encoding utf8NoBOM (Join-Path $dir "settings.json")
'{"x":0.0,"y":0.0,"width":1024.0,"height":728.0}' | Set-Content -Encoding utf8NoBOM (Join-Path $dir "window.json")

Add-Type -AssemblyName System.Windows.Forms, System.Drawing
function Shot([string]$name) {
  $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
  [System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
  $bmp.Save((Join-Path $Out "$name.png")); Write-Host "captured $name"
}
function Run([string]$name, [string[]]$appArgs, [int]$wait, [string[]]$keys = @()) {
  $p = if ($appArgs.Count -gt 0) { Start-Process -FilePath $Exe -ArgumentList $appArgs -PassThru } else { Start-Process -FilePath $Exe -PassThru }
  Start-Sleep -Seconds $wait
  foreach ($k in $keys) { [System.Windows.Forms.SendKeys]::SendWait($k); Start-Sleep -Milliseconds 900 }
  Shot $name
  Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}

Run "windows-home-demo-server" @() 25
Run "windows-detail" @("--route", "detail:$($movie.Id)") 15
Run "windows-player" @("--route", "player:$($movie.Id)") 30
Run "windows-player-controls" @("--route", "player:$($movie.Id)") 30 @("{UP}")
Get-Content (Join-Path $dir "aurora.log") -Tail 40 -ErrorAction SilentlyContinue
