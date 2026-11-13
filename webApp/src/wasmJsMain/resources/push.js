// Thin Web Push helpers called from Kotlin/Wasm (see PushBridge.kt). Every function returns a string
// or a Promise of a string so the Kotlin side only deals with simple values.
window.scPush = {
  /** "unsupported" | "needs-home-screen" | "denied" | "granted" | "default" */
  support() {
    const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    const standalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
    if (isIOS && !standalone) return "needs-home-screen";
    if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) return "unsupported";
    return Notification.permission;
  },

  registerServiceWorker() {
    if (!("serviceWorker" in navigator)) return Promise.resolve("unsupported");
    return navigator.serviceWorker.register("/sw.js", { scope: "/" }).then(() => "ok", (e) => "error: " + e.message);
  },

  /** Resolves to the subscription JSON (endpoint + keys) or "" if there is none. */
  current() {
    if (!("serviceWorker" in navigator)) return Promise.resolve("");
    return navigator.serviceWorker.ready
      .then((reg) => reg.pushManager.getSubscription())
      .then((sub) => (sub ? JSON.stringify(sub.toJSON()) : ""));
  },

  /** Must be called from a user gesture on Safari. Resolves to subscription JSON, rejects on failure. */
  subscribe(vapidPublicKey) {
    return Notification.requestPermission().then((permission) => {
      if (permission !== "granted") throw new Error("Notifications were not allowed (" + permission + ")");
      return navigator.serviceWorker.ready;
    }).then((reg) => reg.pushManager.getSubscription().then((existing) => existing ||
      reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: scPush.urlBase64ToUint8Array(vapidPublicKey) })))
      .then((sub) => {
        const json = sub.toJSON();
        json.userAgent = navigator.userAgent;
        return JSON.stringify(json);
      });
  },

  /** Resolves to the endpoint that was removed, or "". */
  unsubscribe() {
    return navigator.serviceWorker.ready
      .then((reg) => reg.pushManager.getSubscription())
      .then((sub) => (sub ? sub.unsubscribe().then(() => sub.endpoint) : ""));
  },

  /** Calls back whenever the service worker tells open pages about a new alert. */
  onAlert(callback) {
    if (!("serviceWorker" in navigator)) return;
    navigator.serviceWorker.addEventListener("message", (event) => {
      if (event.data && event.data.type === "alert") callback();
    });
  },

  formatTime(ms) {
    return new Date(ms).toLocaleString();
  },

  urlBase64ToUint8Array(base64) {
    const padding = "=".repeat((4 - (base64.length % 4)) % 4);
    const raw = atob((base64 + padding).replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
  },
};
