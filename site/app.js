// Starts the Kotlin/Wasm app once the bridge is up (or hands over to the extension: tubeless.ready never resolves then).
await tubeless.ready;
const t = performance.now();
try {
  await import("./tubeless.mjs");
  console.log(`[tubeless] wasm module in ${Math.round(performance.now() - t)} ms`);
} catch (e) {
  console.log("[tubeless] could not start:", e);
  document.getElementById("tubeless-loading").textContent = "This browser can't run Tubeless (it needs WebAssembly GC: Chrome 119+, Firefox 120+, Safari 18.2+).";
  throw e;
}
document.getElementById("tubeless-loading")?.remove();
