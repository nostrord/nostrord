/**
 * Production build optimizations for wasmJs and js targets.
 * Ensures minification and deterministic module IDs for better caching.
 */
if (config.mode === 'production') {
    config.optimization = config.optimization || {};
    config.optimization.minimize = true;
    config.optimization.moduleIds = 'deterministic';

    // Enable better tree shaking
    config.optimization.usedExports = true;
    config.optimization.sideEffects = true;
}
