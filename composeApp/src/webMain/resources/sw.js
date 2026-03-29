/**
 * Service Worker for Nostrord PWA
 * Optimized for GitHub Pages deployment
 *
 * Caching strategies:
 * - WASM modules: Cache-first (immutable, large files)
 * - Fonts: Cache-first (immutable)
 * - JS bundles: Cache-first with background update
 * - HTML: Network-first (for updates)
 * - API/WebSocket: Network-only (not cached)
 */

const CACHE_VERSION = '__BUILD_VERSION__';
const STATIC_CACHE = `nostrord-static-${CACHE_VERSION}`;
const FONT_CACHE = `nostrord-fonts-${CACHE_VERSION}`;

// Detect base path (works on GitHub Pages with subdirectory)
const BASE_PATH = new URL(self.registration.scope).pathname;

// Files to precache on install (relative to base path)
const PRECACHE_URLS = [
    '',
    'index.html',
    'styles.css',
    'aes-js.min.js',
    'noble-crypto.min.js',
];

// Install event - precache critical resources
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(STATIC_CACHE)
            .then((cache) => {
                console.log('[SW] Precaching critical resources, base:', BASE_PATH);
                const urlsToCache = PRECACHE_URLS.map(url => BASE_PATH + url);
                return cache.addAll(urlsToCache).catch((err) => {
                    console.warn('[SW] Precache failed for some resources:', err);
                });
            })
            .then(() => self.skipWaiting())
    );
});

// Activate event - clean up old caches
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames
                    .filter((name) => {
                        return name.startsWith('nostrord-') &&
                               name !== STATIC_CACHE &&
                               name !== FONT_CACHE;
                    })
                    .map((name) => {
                        console.log('[SW] Deleting old cache:', name);
                        return caches.delete(name);
                    })
            );
        }).then(() => self.clients.claim())
    );
});

// Fetch event - route requests to appropriate cache strategy
self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);

    // Skip non-GET requests
    if (event.request.method !== 'GET') {
        return;
    }

    // Skip WebSocket and external requests
    if (url.protocol === 'wss:' || url.protocol === 'ws:') {
        return;
    }

    // Skip cross-origin requests (except for allowed CDNs)
    if (url.origin !== self.location.origin) {
        return;
    }

    // Route based on request type
    if (isWasmRequest(url)) {
        event.respondWith(cacheFirst(event.request, STATIC_CACHE));
    } else if (isFontRequest(url)) {
        event.respondWith(cacheFirst(event.request, FONT_CACHE));
    } else if (isStaticAsset(url)) {
        event.respondWith(staleWhileRevalidate(event.request, STATIC_CACHE));
    } else if (isHtmlRequest(event.request, url)) {
        event.respondWith(networkFirstWithFallback(event.request, STATIC_CACHE));
    } else {
        event.respondWith(networkFirst(event.request, STATIC_CACHE));
    }
});

// Request type detection
function isWasmRequest(url) {
    return url.pathname.endsWith('.wasm');
}

function isFontRequest(url) {
    return url.pathname.endsWith('.ttf') ||
           url.pathname.endsWith('.woff') ||
           url.pathname.endsWith('.woff2') ||
           url.pathname.includes('/font/');
}

function isStaticAsset(url) {
    return url.pathname.endsWith('.js') ||
           url.pathname.endsWith('.css') ||
           url.pathname.endsWith('.png') ||
           url.pathname.endsWith('.svg');
}

function isHtmlRequest(request, url) {
    return request.headers.get('accept')?.includes('text/html') ||
           url.pathname.endsWith('/') ||
           url.pathname.endsWith('.html') ||
           !url.pathname.includes('.');
}

// Cache-first strategy (for immutable content like WASM and fonts)
async function cacheFirst(request, cacheName) {
    const cache = await caches.open(cacheName);
    const cachedResponse = await cache.match(request);

    if (cachedResponse) {
        return cachedResponse;
    }

    try {
        const networkResponse = await fetch(request);
        if (networkResponse.ok) {
            cache.put(request, networkResponse.clone());
        }
        return networkResponse;
    } catch (error) {
        console.error('[SW] Cache-first fetch failed:', error);
        throw error;
    }
}

// Stale-while-revalidate strategy (for JS/CSS that may update)
async function staleWhileRevalidate(request, cacheName) {
    const cache = await caches.open(cacheName);
    const cachedResponse = await cache.match(request);

    const fetchPromise = fetch(request).then((networkResponse) => {
        if (networkResponse.ok) {
            cache.put(request, networkResponse.clone());
        }
        return networkResponse;
    }).catch(() => null);

    return cachedResponse || fetchPromise;
}

// Network-first strategy (for HTML and dynamic content)
async function networkFirst(request, cacheName) {
    const cache = await caches.open(cacheName);

    try {
        const networkResponse = await fetch(request);
        if (networkResponse.ok) {
            cache.put(request, networkResponse.clone());
        }
        return networkResponse;
    } catch (error) {
        const cachedResponse = await cache.match(request);
        if (cachedResponse) {
            return cachedResponse;
        }
        throw error;
    }
}

// Network-first with index.html fallback (for SPA routing on GitHub Pages)
async function networkFirstWithFallback(request, cacheName) {
    const cache = await caches.open(cacheName);

    try {
        const networkResponse = await fetch(request);
        if (networkResponse.ok) {
            cache.put(request, networkResponse.clone());
            return networkResponse;
        }
        // If 404, try to serve index.html for SPA routing
        if (networkResponse.status === 404) {
            const indexResponse = await cache.match(BASE_PATH + 'index.html');
            if (indexResponse) {
                return indexResponse;
            }
        }
        return networkResponse;
    } catch (error) {
        // Offline - try cache, then index.html
        const cachedResponse = await cache.match(request);
        if (cachedResponse) {
            return cachedResponse;
        }
        const indexResponse = await cache.match(BASE_PATH + 'index.html');
        if (indexResponse) {
            return indexResponse;
        }
        throw error;
    }
}
