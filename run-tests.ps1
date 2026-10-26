Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "Running M-PESA Wallet End-to-End Concurrency Test Suite" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

mvn -B test -Dtest=EndToEndTestSuite
if ($LASTEXITCODE -ne 0) {
    Write-Error "Test suite failed on first run! Exiting non-zero."
    exit $LASTEXITCODE
}

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host "Running second time to verify no manual cleanup required" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

mvn -B test -Dtest=EndToEndTestSuite
if ($LASTEXITCODE -ne 0) {
    Write-Error "Test suite failed on second run! Exiting non-zero."
    exit $LASTEXITCODE
}

Write-Host "`n[SUCCESS] All E2E concurrency tests passed twice in a row!" -ForegroundColor Green
exit 0
