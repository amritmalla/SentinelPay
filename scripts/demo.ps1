# SentinelPay narrated demo (PowerShell twin of scripts/demo.sh).
# Prereq: docker compose up --build
$ErrorActionPreference = "Stop"
$BaseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" }
$MerchantId = if ($env:MERCHANT_ID) { $env:MERCHANT_ID } else { [guid]::NewGuid().ToString() }
$DemoEmail = "demo-risk-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())@example.com"

function Invoke-Json($Method, $Uri, $Body, $Headers) {
    $params = @{ Method = $Method; Uri = $Uri; ContentType = "application/json" }
    if ($Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    if ($Headers) { $params.Headers = $Headers }
    return Invoke-RestMethod @params
}

Write-Host "▶ SentinelPay demo — gateway at $BaseUrl`n"

Write-Host "▶ Setup: minting tokens…"
$tokenResp = Invoke-Json POST "$BaseUrl/dev/token" @{ role = "MERCHANT"; merchant_id = $MerchantId } $null
$opsResp = Invoke-Json POST "$BaseUrl/dev/token" @{ role = "OPS"; merchant_id = "00000000-0000-0000-0000-000000000001" } $null
$Token = $tokenResp.token
$OpsToken = $opsResp.token
Write-Host "   merchant_id=$MerchantId`n"

Write-Host "▶ Act 1 — Happy path: charge `$25 via MockPay…"
$happy = Invoke-Json POST "$BaseUrl/api/v1/payments/charge" @{
    amount_cents = 2500; currency = "USD"; customer_email = "buyer@example.com"
} @{
    Authorization = "Bearer $Token"
    "Idempotency-Key" = "demo-happy-1"
    "X-Correlation-Id" = "demo-corr-happy"
}
$happy | ConvertTo-Json
if ($happy.status -ne "COMPLETED" -or $happy.provider -ne "MOCKPAY") { throw "Expected COMPLETED/MOCKPAY" }
$trail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/payments/$($happy.payment_id)/trail" -Headers @{ Authorization = "Bearer $OpsToken" }
Write-Host "   Decision trail:"; $trail | ConvertTo-Json -Depth 6
Write-Host "   MailHog: http://localhost:8025`n"

Write-Host "▶ Act 2 — Failover: MockPay → HARD_FAIL…"
Invoke-Json POST "$BaseUrl/dev/providers/mockpay/program" @{ outcomes = @("HARD_FAIL") } $null | Out-Null
$failover = Invoke-Json POST "$BaseUrl/api/v1/payments/charge" @{
    amount_cents = 2500; currency = "USD"; customer_email = "buyer@example.com"
} @{
    Authorization = "Bearer $Token"
    "Idempotency-Key" = "demo-failover-1"
    "X-Correlation-Id" = "demo-corr-failover"
}
$failover | ConvertTo-Json
if ($failover.status -ne "COMPLETED" -or $failover.provider -ne "STRIPE") { throw "Expected COMPLETED/STRIPE failover" }
Write-Host "   Grafana → Correctness dashboard`n"

Write-Host "▶ Act 3 — Risk block: six `$1,500 charges for $DemoEmail…"
$blocked = $null
for ($i = 1; $i -le 6; $i++) {
    $result = Invoke-Json POST "$BaseUrl/api/v1/payments/charge" @{
        amount_cents = 150000; currency = "USD"; customer_email = $DemoEmail
    } @{
        Authorization = "Bearer $Token"
        "Idempotency-Key" = "demo-risk-$i"
    }
    Write-Host "   charge ${i}: $($result.status)"
    if ($result.status -eq "BLOCKED") { $blocked = $result; break }
}
if (-not $blocked) { throw "Expected BLOCKED by charge 6" }
$blockedTrail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/payments/$($blocked.payment_id)/trail" -Headers @{ Authorization = "Bearer $OpsToken" }
Write-Host "   Blocked trail:"; $blockedTrail | ConvertTo-Json -Depth 6

Write-Host "`n▶ Where to look: Grafana http://localhost:3000 | Tempo demo-corr-happy | MailHog http://localhost:8025"
Write-Host "✓ Demo complete."
