# Builds belle_llm (+ belle_ort for cpu) for Windows x64 with MSVC + Ninja.
#   pwsh desktop/native/ci/build-natives.ps1 -Backend cpu|cuda|vulkan [-Out <dir>] [-CudaRoot <dir>]
param([Parameter(Mandatory)][ValidateSet("cpu", "cuda", "vulkan")][string]$Backend, [string]$Out = "natives-$Backend", [string]$CudaRoot = "")
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot/../../.."
# MSVC environment (vcvars64) into this PowerShell session
$vs = & "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
cmd /c "`"$vs\VC\Auxiliary\Build\vcvars64.bat`" >nul && set" | ForEach-Object { if ($_ -match "^([^=]+)=(.*)$") { [Environment]::SetEnvironmentVariable($matches[1], $matches[2]) } }
$cm = @("-S", "$root/desktop/native", "-B", "build-$Backend", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DBELLE_LLM_BACKEND=$Backend", "-DCMAKE_C_COMPILER=cl", "-DCMAKE_CXX_COMPILER=cl")
if ($Backend -eq "cuda") { $cm += @("-DCMAKE_CUDA_COMPILER=$CudaRoot/bin/nvcc.exe", "-DCUDAToolkit_ROOT=$CudaRoot"); $env:PATH = "$CudaRoot\bin;$env:PATH" }
cmake @cm
if ($LASTEXITCODE) { throw "cmake configure failed" }
$targets = @("belle_llm"); if ($Backend -eq "cpu") { $targets += "belle_ort" }
cmake --build "build-$Backend" --target @targets --parallel
if ($LASTEXITCODE) { throw "build failed" }
New-Item -ItemType Directory -Force $Out | Out-Null
Copy-Item "build-$Backend/out/$Backend/*.dll" $Out
Get-ChildItem $Out | Format-Table Name, Length
