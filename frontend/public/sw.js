const SHELL_CACHE = 'app-shell-v2';
const SHELL_FILES = ['/', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png', '/notification-icon.png'];

async function cacheShell() {
  const cache = await caches.open(SHELL_CACHE);
  await cache.addAll(SHELL_FILES);
  const html = await (await cache.match('/')).text();
  const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((m) => m[1]);
  await cache.addAll(assets);
}

self.addEventListener('install', (event) => {
  event.waitUntil(cacheShell().then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(names.filter((n) => n !== SHELL_CACHE).map((n) => caches.delete(n)));
      await self.clients.claim();
    })(),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  // The API is never cached: personal data must not reach Cache Storage
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            // Clone before the page reads the body
            const copy = response.clone();
            caches.open(SHELL_CACHE).then((c) => c.put('/', copy));
          }
          return response;
        })
        .catch(() => caches.match('/')),
    );
    return;
  }
  if (url.pathname.startsWith('/assets/') || SHELL_FILES.includes(url.pathname)) {
    event.respondWith(
      caches.match(request).then(
        (hit) =>
          hit ||
          fetch(request).then((response) => {
            if (response.ok) {
              const copy = response.clone();
              caches.open(SHELL_CACHE).then((c) => c.put(request, copy));
            }
            return response;
          }),
      ),
    );
  }
});

const FALLBACK = {
  title: 'RiskTech & Advisory',
  body: 'Your vetting status has been updated',
  url: '/',
  tag: 'vetting-status',
};

function safeUrl(url) {
  return typeof url === 'string' && /^\/(?![/\\])/.test(url) ? url : '/';
}

self.addEventListener('push', (event) => {
  let data = FALLBACK;
  try {
    if (event.data) data = { ...FALLBACK, ...event.data.json() };
  } catch {
  }
  event.waitUntil(
    self.registration.showNotification(String(data.title), {
      body: String(data.body),
      icon: '/notification-icon.png',
      tag: String(data.tag),
      renotify: true,
      data: { url: safeUrl(data.url) },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = new URL(safeUrl(event.notification.data && event.notification.data.url), self.location.origin).href;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      const existing = windows.find((client) => new URL(client.url).origin === self.location.origin);
      if (existing) return existing.focus();
      return self.clients.openWindow(target);
    })(),
  );
});
