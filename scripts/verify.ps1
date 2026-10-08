param([string]$BaseUrl = 'http://localhost:8088')
$ErrorActionPreference = 'Stop'

function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Compose([string[]]$Arguments) {
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $Arguments" }
}
function Wait-Healthy([string]$Path = '/actuator/health/readiness') {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $health = Invoke-RestMethod "$BaseUrl$Path" -TimeoutSec 5
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 1000
    }
    throw "Health check timed out: $Path"
}
function Wait-Sent([string]$Id) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        $saved = Invoke-RestMethod "$BaseUrl/api/notifications/$Id" -TimeoutSec 5
        if ($saved.status -eq 'SENT') {
            Assert ($saved.deliveryCount -eq 1) 'Unexpected delivery count'
            return $saved
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Delivery timed out: $Id"
}

Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    Wait-Healthy
    $key = [Guid]::NewGuid().ToString()
    $headers = @{ 'Idempotency-Key' = $key }
    $body = @{ recipient = 'demo@example.com'; subject = 'Order ready'; body = 'Your order is ready.' } | ConvertTo-Json
    $created = Invoke-WebRequest "$BaseUrl/api/notifications" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
    Assert ($created.StatusCode -eq 202) 'Expected HTTP 202'
    $notification = $created.Content | ConvertFrom-Json
    $sent = Wait-Sent $notification.id
    $replay = Invoke-RestMethod "$BaseUrl/api/notifications" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
    Assert ($replay.id -eq $notification.id) 'Replay created a new notification'
    $changed = @{ recipient = 'demo@example.com'; subject = 'Changed'; body = 'Different body' } | ConvertTo-Json
    $conflict = Invoke-WebRequest "$BaseUrl/api/notifications" -Method Post -Headers $headers -ContentType 'application/json' -Body $changed -SkipHttpErrorCheck
    Assert ($conflict.StatusCode -eq 409) 'Expected HTTP 409 for conflicting replay'
    Write-Output "PASS: HTTP 202 -> SENT, idempotent replay, conflicting replay 409 (id=$($sent.id))"

    try {
        Compose -Arguments @('stop', 'rabbitmq')
        $pending = Invoke-RestMethod "$BaseUrl/api/notifications" -Method Post -Headers @{ 'Idempotency-Key' = [Guid]::NewGuid().ToString() } -ContentType 'application/json' -Body $body
        Assert ($pending.status -eq 'PENDING') 'Expected pending notification while broker is offline'
        $readiness = Invoke-WebRequest "$BaseUrl/actuator/health/readiness" -SkipHttpErrorCheck
        Assert ($readiness.StatusCode -eq 503) 'Readiness should fail when broker is unavailable'
        Compose -Arguments @('restart', 'app')
        Wait-Healthy '/actuator/health/liveness'
        $persisted = Invoke-RestMethod "$BaseUrl/api/notifications/$($pending.id)"
        Assert ($persisted.status -eq 'PENDING') 'Pending notification did not survive restart'
        $previous = Invoke-RestMethod "$BaseUrl/api/notifications/$($notification.id)"
        Assert ($previous.status -eq 'SENT') 'Sent notification did not survive restart'
    } finally {
        Compose -Arguments @('start', 'rabbitmq')
    }
    Wait-Healthy
    $recovered = Wait-Sent $pending.id
    Write-Output "PASS: broker outage, readiness 503, app restart, persistent outbox recovery (id=$($recovered.id))"
    Write-Output 'VERIFIED: all HTTP and recovery checks passed.'
} finally {
    Pop-Location
}
