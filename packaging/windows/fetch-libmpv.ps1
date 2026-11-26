# CI (Windows runner): libmpv-2.dll (shinchiro's mpv-winbuild-cmake, plain build: -v3 needs AVX2) and
# the Khronos Vulkan loader (Apache-2.0) next to it, into the app's resources/windows. From upstream
# Tubeless build.yml.
#   packaging/windows/fetch-libmpv.ps1 -Arch x86_64 -VulkanArch x64 -Out desktop/resources/windows
param([string]$Arch = "x86_64", [string]$VulkanArch = "x64", [string]$Out = "desktop/resources/windows")
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$headers = @{}
if ($env:GH_TOKEN) { $headers.Authorization = "Bearer $env:GH_TOKEN" }
$rel = Invoke-RestMethod https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest -Headers $headers
$asset = $rel.assets | Where-Object { $_.name -like "mpv-dev-$Arch-*.7z" -and $_.name -notlike '*-v3-*' } | Select-Object -First 1
if (-not $asset) { throw "no mpv-dev-$Arch build in the latest mpv-winbuild-cmake release" }
Invoke-WebRequest $asset.browser_download_url -OutFile mpv-dev.7z
7z e mpv-dev.7z libmpv-2.dll "-o$Out" -y
# libmpv needs vulkan-1.dll, which only some graphics drivers bring; LunarG sometimes lists a version
# before its file is up, so fall back to the ones before it
$list = Invoke-RestMethod https://vulkan.lunarg.com/sdk/versions/windows.json
foreach ($v in @('latest') + $list[0..2]) {
  try { Invoke-WebRequest "https://sdk.lunarg.com/sdk/download/$v/windows/vulkan-runtime-components.zip?u=" -OutFile vkrt.zip; break }
  catch { Write-Warning "no Vulkan runtime $v" }
}
7z x vkrt.zip -ovkrt -y | Out-Null
$vk = Get-ChildItem vkrt -Recurse -Filter vulkan-1.dll | Where-Object { $_.FullName -like "*\$VulkanArch\*" } | Select-Object -First 1
if ($vk) { Copy-Item $vk.FullName $Out } else { Write-Warning "no $VulkanArch vulkan-1.dll in the runtime components" }
Copy-Item (Get-ChildItem vkrt -Recurse -Filter VulkanRT-License.txt | Select-Object -First 1).FullName $Out
Get-ChildItem $Out
