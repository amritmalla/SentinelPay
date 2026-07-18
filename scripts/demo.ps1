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
    merchant_category = "retail"; card_country = "US"
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
        merchant_category = "retail"; card_country = "GB"
    } @{
        Authorization = "Bearer $Token"
        "Idempotency-Key" = "demo-risk-$i"
    }
    Write-Host "   charge ${i}: $($result.status)"
    if ($result.status -eq "BLOCKED") { $blocked = $result; break }
}
if (-not $blocked) { throw "Expected BLOCKED by charge 6" }
$blockedTrail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/payments/$($blocked.payment_id)/trail" -Headers @{ Authorization = "Bearer $OpsToken" }
Write-Host "   Blocked trail (card_country=GB vs merchant default US):"; $blockedTrail | ConvertTo-Json -Depth 6

Write-Host "`n▶ Act 4 — Adaptive routing: MockPay degrades, the bandit shifts traffic to Stripe…"
Invoke-Json POST "$BaseUrl/dev/providers/mockpay/program" @{ outcomes = @("HARD_FAIL") } $null | Out-Null
# Let the bandit observe a few MockPay failures (each fails over to Stripe and completes).
1..5 | ForEach-Object {
    try {
        Invoke-Json POST "$BaseUrl/api/v1/payments/charge" @{
            amount_cents = 2500; currency = "USD"; customer_email = "buyer@example.com"
        } @{ Authorization = "Bearer $Token"; "Idempotency-Key" = "demo-route-seed-$_"; "X-Correlation-Id" = "demo-corr-routing" } | Out-Null
    } catch { }
}
# Thompson sampling is stochastic per charge, so demonstrate the aggregate shift over a batch.
$stripeFirst = 0
$lastRouteId = $null
1..12 | ForEach-Object {
    try {
        $resp = Invoke-Json POST "$BaseUrl/api/v1/payments/charge" @{
            amount_cents = 2500; currency = "USD"; customer_email = "buyer@example.com"
        } @{ Authorization = "Bearer $Token"; "Idempotency-Key" = "demo-route-batch-$_"; "X-Correlation-Id" = "demo-corr-routing" }
        $lastRouteId = $resp.payment_id
        $trail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/payments/$($resp.payment_id)/trail" -Headers @{ Authorization = "Bearer $OpsToken" }
        if ($trail.routing.ordered_providers[0] -eq "stripe") { $stripeFirst++ }
    } catch { }
}
Write-Host "   $stripeFirst/12 charges routed Stripe-first after MockPay degraded"
if ($stripeFirst -lt 8) { throw "Expected the bandit to route Stripe-first for most charges, got $stripeFirst/12" }
$routeTrail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/payments/$lastRouteId/trail" -Headers @{ Authorization = "Bearer $OpsToken" }
$routeTrail.routing | ConvertTo-Json -Depth 8
# Deterministic proof the bandit learned: MockPay's posterior mean sits below Stripe's.
$mpMean = [double]$routeTrail.routing.providers.mockpay.bandit.posterior_mean
$stMean = [double]$routeTrail.routing.providers.stripe.bandit.posterior_mean
if (-not ($mpMean -lt $stMean)) { throw "Expected MockPay posterior mean ($mpMean) < Stripe ($stMean)" }
Write-Host "   Bandit posteriors — MockPay mean=$mpMean < Stripe mean=$stMean (learned MockPay is degraded)"
Write-Host "   Note: under the bandit the breaker rarely trips — the bandit demotes MockPay before its"
Write-Host "         failure window fills. The deterministic breaker->OPEN proof lives in RoutingShiftIT."
Write-Host "   Grafana → Provider Health: Stripe first-choice share up, MockPay posterior down`n"

Write-Host "▶ Act 5 — Recovery: MockPay healthy again, the bandit re-explores and re-learns…"
Invoke-Json POST "$BaseUrl/dev/providers/mockpay/program" @{ outcomes = @("AUTHORIZED") } $null | Out-Null
# Thompson sampling keeps probing the demoted provider; each success rebuilds its posterior.
1..15 | ForEach-Object {
    try {
        Invoke-Json POST "$BaseUrl/api/v1/payments/charge" @{
            amount_cents = 2500; currency = "USD"; customer_email = "buyer@example.com"
        } @{ Authorization = "Bearer $Token"; "Idempotency-Key" = "demo-recovery-$_"; "X-Correlation-Id" = "demo-corr-recovery" } | Out-Null
    } catch { }
}
$recovery = Invoke-Json POST "$BaseUrl/api/v1/payments/charge" @{
    amount_cents = 2500; currency = "USD"; customer_email = "buyer@example.com"
} @{
    Authorization = "Bearer $Token"; "Idempotency-Key" = "demo-recovery-final"; "X-Correlation-Id" = "demo-corr-recovery-final"
}
Write-Host "   Recovery trail (MockPay bandit posterior climbing as probes succeed):"
$recTrail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/payments/$($recovery.payment_id)/trail" -Headers @{ Authorization = "Bearer $OpsToken" }
$recTrail.routing.providers.mockpay.bandit | ConvertTo-Json
$recTrail.routing.providers.stripe.bandit | ConvertTo-Json
Write-Host "   Explore/exploit: Thompson sampling keeps probing recovered MockPay instead of waiting"
Write-Host "     for a fixed window to age out — its success count (alpha) climbs back over time."

Write-Host "`n▶ Where to look: Dashboard http://localhost:5173"
Write-Host "   Paste this merchant id at login (the demo uses a fresh one each run, so the"
Write-Host "   pre-filled default will show an empty list):"
Write-Host "     $MerchantId"
Write-Host "   Log in as OPS for the full trail + routing; MERCHANT sees the redacted summary."
Write-Host "   Pin the id with `$env:MERCHANT_ID=... to reuse it."
Write-Host "   Grafana http://localhost:3000 (Provider Health) | Tempo demo-corr-happy | MailHog http://localhost:8025"
Write-Host "✓ Demo complete — happy path, failover, risk block, adaptive routing shift, and bandit recovery observed."
