# Assembles a minimal CUDA toolkit (compiler + the libraries llama.cpp links) from NVIDIA's redistributable archives,
# each checked against the SHA-256 in NVIDIA's own manifest. No installer, no driver needed to compile.
#   pwsh desktop/native/ci/fetch-cuda.ps1 -Version 13.5.0 -Out C:\cuda
param([string]$Version = "13.5.0", [string]$Out = "C:\cuda")
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is many times slower with the progress bar
$base = "https://developer.download.nvidia.com/compute/cuda/redist"
$man = Invoke-RestMethod "$base/redistrib_$Version.json"
New-Item -ItemType Directory -Force $Out, "$env:RUNNER_TEMP\cuda-dl" | Out-Null
foreach ($c in @("cuda_nvcc", "libnvvm", "cuda_crt", "cuda_cudart", "cccl", "libcublas", "cuda_profiler_api")) {
    $w = $man.$c."windows-x86_64"; if (-not $w) { Write-Host "skip $c (not in $Version)"; continue }
    $zip = "$env:RUNNER_TEMP\cuda-dl\$($w.relative_path | Split-Path -Leaf)"
    Write-Host "$c $($man.$c.version) ($([math]::Round($w.size / 1MB)) MB)"
    Invoke-WebRequest "$base/$($w.relative_path)" -OutFile $zip
    $sha = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
    if ($sha -ne $w.sha256) { throw "$c checksum mismatch: $sha" }
    $tmp = "$env:RUNNER_TEMP\cuda-x\$c"; Expand-Archive $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    Copy-Item "$($inner.FullName)\*" $Out -Recurse -Force
    Remove-Item $zip, $tmp -Recurse -Force
}
# CUDA 13 keeps the x64 DLLs in bin\x64: nvcc's tools and CMake look in bin
if (Test-Path "$Out\bin\x64") { Copy-Item "$Out\bin\x64\*" "$Out\bin" -Force }
& "$Out\bin\nvcc.exe" --version
