@echo off
setlocal
pushd "%~dp0..\frontend" || exit /b 1
if defined MDOP_NODE_HOME (
  call "%MDOP_NODE_HOME%\corepack.cmd" pnpm %*
) else (
  call corepack.cmd pnpm %*
)
set "MDOP_PNPM_EXIT=%ERRORLEVEL%"
popd
exit /b %MDOP_PNPM_EXIT%
