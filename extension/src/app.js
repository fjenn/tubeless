// Starts the Kotlin/Wasm app once the header rules are in place.
await tubeless.ready;
const t = performance.now();
await import("./tubeless.mjs");
console.log(`[tubeless] wasm module in ${Math.round(performance.now() - t)} ms`);
