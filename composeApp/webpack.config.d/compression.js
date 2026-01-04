/**
 * Webpack compression configuration for production builds.
 * Pre-compresses assets with gzip and brotli for faster serving.
 *
 * Benefits:
 * - 60-80% transfer size reduction
 * - Server just serves pre-compressed files (no CPU overhead)
 * - Works with any static file server that supports Accept-Encoding
 */

if (config.mode === 'production') {
    const CompressionPlugin = require('compression-webpack-plugin');

    config.plugins = config.plugins || [];

    // Gzip compression (wider browser support)
    config.plugins.push(
        new CompressionPlugin({
            filename: '[path][base].gz',
            algorithm: 'gzip',
            test: /\.(js|css|html|wasm|ttf|svg|json)$/,
            threshold: 1024,  // Only compress files > 1KB
            minRatio: 0.8,    // Only keep if 20%+ smaller
            deleteOriginalAssets: false,  // Keep originals for fallback
        })
    );

    // Brotli compression (better ratio, modern browsers)
    try {
        const zlib = require('zlib');
        config.plugins.push(
            new CompressionPlugin({
                filename: '[path][base].br',
                algorithm: 'brotliCompress',
                test: /\.(js|css|html|wasm|ttf|svg|json)$/,
                threshold: 1024,
                minRatio: 0.8,
                compressionOptions: {
                    params: {
                        [zlib.constants.BROTLI_PARAM_QUALITY]: 11,  // Max quality
                    },
                },
                deleteOriginalAssets: false,
            })
        );
        console.log('[Webpack] Compression enabled: gzip + brotli');
    } catch (e) {
        console.log('[Webpack] Compression enabled: gzip only (brotli unavailable)');
    }
}
