@echo off
setlocal EnableExtensions
rem Сборка через виртуальный диск (T:, X:, ...) — путь без кириллицы, иначе protoc ломается на путях вроде D:\СГТУ\...
rem Использование: build.bat
rem Если T: занят: build.bat X

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "DRIVE=T"
if not "%~1"=="" set "DRIVE=%~1"

subst %DRIVE%: "%ROOT%" 2>nul
if errorlevel 1 (
  echo Не удалось сопоставить диск %DRIVE%: с папкой проекта. Занят? Запустите: %~nx0 X
  exit /b 1
)

pushd %DRIVE%:\
echo Сборка из %DRIVE%:\ (обход ошибки protoc с не-ASCII путями Windows^)
call mvn clean install -DskipTests
set ERR=%ERRORLEVEL%
popd
subst %DRIVE%: /d
exit /b %ERR%
