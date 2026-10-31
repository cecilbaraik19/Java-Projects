# Downloads the 30 product photos from Unsplash (free to use under the Unsplash License)
# into frontend\img\products, so the shop shows real photos - even with no internet later.
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$dir = Join-Path $PSScriptRoot "frontend\img\products"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$photos = [ordered]@{
  "elc-hp-01" = "photo-1553774661-0651f14b2da4"
  "elc-ph-02" = "photo-1758770478125-4850521fd941"
  "elc-wt-03" = "photo-1434494745656-1aea7daa8f6f"
  "elc-lp-04" = "photo-1587560699334-cc4ff634909a"
  "elc-kb-05" = "photo-1618384887929-16ec33fab9ef"
  "elc-sp-06" = "photo-1549400854-b4300f444934"
  "elc-cm-07" = "photo-1562878671-b3efe27953b9"
  "fsh-ts-08" = "photo-1581655353564-df123a1eb820"
  "fsh-sh-09" = "photo-1560769629-975ec94e6a86"
  "fsh-bp-10" = "photo-1622560480605-d83c853bc5c3"
  "fsh-sg-11" = "photo-1584036553516-bf83210aa16c"
  "fsh-cp-12" = "photo-1691256676359-20e5c6d4bc92"
  "fsh-sh-13" = "photo-1740711152088-88a009e877bb"
  "hom-mg-14" = "photo-1491720731493-223f97d92c21"
  "hom-lp-15" = "photo-1585597647877-6eaa01bf9a05"
  "hom-pl-16" = "photo-1609061801093-1b7a8a574c69"
  "hom-bt-17" = "photo-1486381950565-e3ada9f1a370"
  "hom-cw-18" = "photo-1580929753603-10519c6e480a"
  "hom-sp-19" = "photo-1623569559000-c3031217d717"
  "bok-bk-20" = "photo-1497633762265-9d179a990aa6"
  "bok-bk-21" = "photo-1550399105-c4db5fb85c18"
  "bok-bk-22" = "photo-1556566952-11eff3d06ed4"
  "bok-bk-23" = "photo-1543002588-bfa74002ed7e"
  "spt-ym-24" = "photo-1646239646963-b0b9be56d6b5"
  "spt-db-25" = "photo-1703668984128-b506579acdd2"
  "spt-bl-26" = "photo-1520804588595-ac2328f38c4a"
  "spt-bd-27" = "photo-1521537634581-0dced2fee2ef"
  "bty-pf-28" = "photo-1458538977777-0549b2370168"
  "bty-sc-29" = "photo-1509423350716-97f9360b4e09"
  "bty-sc-30" = "photo-1560769680-ba2f3767c785"
}
# sources.txt remembers which Unsplash photo each file came from, so a changed photo is downloaded again
$listFile = Join-Path $dir "sources.txt"
$have = @{}
if (Test-Path $listFile) { foreach ($line in Get-Content $listFile) { $p = $line.Split("="); if ($p.Length -eq 2) { $have[$p[0]] = $p[1] } } }
$ok = 0; $bad = 0
foreach ($sku in $photos.Keys) {
  $file = Join-Path $dir ($sku + ".jpg")
  if ((Test-Path $file) -and ((Get-Item $file).Length -gt 5000) -and ($have[$sku] -eq $photos[$sku])) { Write-Host "  have   $sku"; $ok++; continue }
  $url = "https://images.unsplash.com/" + $photos[$sku] + "?w=800&h=600&fit=crop&crop=entropy&q=75&fm=jpg"
  try { Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing -TimeoutSec 60; $have[$sku] = $photos[$sku]; Write-Host "  saved  $sku"; $ok++ }
  catch { Write-Host "  FAILED $sku - $($_.Exception.Message)"; $bad++ }
}
($have.Keys | Sort-Object | ForEach-Object { "$_=" + $have[$_] }) | Set-Content $listFile
Write-Host ""
Write-Host "$ok photos ready, $bad failed. Products without a photo show their drawn icon."
