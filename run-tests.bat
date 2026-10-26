@echo off
echo ========================================================
echo Running M-PESA Wallet End-to-End Concurrency Test Suite
echo ========================================================

call mvn clean test -Dtest=EndToEndTestSuite
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Test suite failed on first run! Exiting non-zero.
    exit /b %ERRORLEVEL%
)

echo.
echo ========================================================
echo Running second time to verify no manual cleanup required
echo ========================================================

call mvn test -Dtest=EndToEndTestSuite
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Test suite failed on second run! Exiting non-zero.
    exit /b %ERRORLEVEL%
)

echo [SUCCESS] All E2E concurrency tests passed twice in a row!
exit /b 0
