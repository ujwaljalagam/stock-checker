/* Stock Checker service worker: shows push alerts, sends delivery receipts, follows subscription rotation.
 *
 * Payloads use the Declarative Web Push shape ({"web_push": 8030, "notification": {...}}). Safari 18.4+ can show
 * those without running this worker; everywhere else (and on Safari when the worker is installed) the push
 * event below does the work, so the same payload serves every browser.
 */
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let message = {};
  try {
    message = event.data ? event.data.json() : {};
  } catch (e) {
    message = { notification: { title: "Stock Checker", body: event.data ? event.data.text() : "" } };
  }
  const n = message.notification || message;
  const data = Object.assign({}, n.data || {}, { navigate: n.navigate || "/" });

  event.waitUntil((async () => {
    await self.registration.showNotification(n.title || "Stock Checker", {
      body: n.body || "",
      tag: n.tag,
      data,
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      silent: !!n.silent,
    });
    // Delivery receipt: tells the server the alert is actually on screen, so it can skip the email fallback.
    if (data.alertId && data.receipt) {
      try {
        await fetch("/api/alert-shown", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ alertId: data.alertId, receipt: data.receipt }),
          keepalive: true,
        });
      } catch (e) { /* offline: the email fallback covers it */ }
    }
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    windows.forEach((client) => client.postMessage({ type: "alert", alertId: data.alertId }));
  })());
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL((event.notification.data && event.notification.data.navigate) || "/", self.location.origin).href;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
      if (new URL(client.url).origin === self.location.origin && "focus" in client) {
        await client.focus();
        if ("navigate" in client) await client.navigate(target);
        return;
      }
    }
    await self.clients.openWindow(target);
  })());
});

// Browsers may rotate or expire a subscription. Re-subscribe and tell the server, using the old endpoint as proof.
self.addEventListener("pushsubscriptionchange", (event) => {
  event.waitUntil((async () => {
    const old = event.oldSubscription;
    let next = event.newSubscription;
    if (!next && old && old.options) {
      next = await self.registration.pushManager.subscribe(old.options);
    }
    if (!old || !next) return;
    await fetch("/api/push/resubscribe", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ oldEndpoint: old.endpoint, subscription: next.toJSON() }),
    });
  })());
});
