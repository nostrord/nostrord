/**
 * Enable WASM streaming compilation for faster initialization.
 * WASM modules compile while downloading instead of waiting for full download.
 *
 * Note: Kotlin/WASM already configures experiments, so we only ensure they're set.
 * We don't add custom module rules as that conflicts with Kotlin's WASM handling.
 */
config.experiments = config.experiments || {};
config.experiments.asyncWebAssembly = true;
config.experiments.topLevelAwait = true;
