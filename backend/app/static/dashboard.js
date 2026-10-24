// Sentinel demo dashboard. All dynamic text is attacker-controlled message data: it is only ever
// placed with textContent / setAttribute, never the HTML-parsing setters.
(function () {
  "use strict";
  var TOKEN_KEY = "sentinel_dashboard_token";
  var token = "";
  try { token = sessionStorage.getItem(TOKEN_KEY) || ""; } catch (e) { token = ""; }
  var timer = null;
  var SVG = "http://www.w3.org/2000/svg";
  var TIERS = ["info", "caution", "high", "critical"];

  function $(id) { return document.getElementById(id); }
  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined && text !== null) n.textContent = String(text);
    return n;
  }
  function svg(tag, attrs) {
    var n = document.createElementNS(SVG, tag);
    for (var k in attrs) n.setAttribute(k, String(attrs[k]));
    return n;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
  function trunc(s, n) { s = String(s); return s.length > n ? s.slice(0, n - 1) + "…" : s; }
  function typeClass(t) {
    return ["phone", "domain", "upi_id", "org", "claim", "amount"].indexOf(t) >= 0 ? t : "other";
  }
  function setStatus(msg, isError) {
    var s = $("status");
    s.textContent = msg || "";
    s.className = isError ? "error" : "";
  }

  function renderCurrent(d) {
    var tier = d.latest && TIERS.indexOf(d.latest.tier) >= 0 ? d.latest.tier : "info";
    var b = $("badge");
    b.className = "badge tier-" + tier;
    b.textContent = tier;
    var head = d.alerts && d.alerts.length ? d.alerts[0].headline : "";
    $("headline").textContent = head || "No alerts yet.";
    var ul = $("why");
    clear(ul);
    var why = (d.latest && d.latest.why) || [];
    if (!why.length) ul.appendChild(el("li", "empty", "Nothing to explain."));
    why.forEach(function (w) { ul.appendChild(el("li", null, w)); });
  }

  function renderEvents(d) {
    var ol = $("events");
    clear(ol);
    var ents = {};
    (d.entities || []).forEach(function (e) { ents[e.id] = e; });
    if (!(d.events || []).length) ol.appendChild(el("li", "empty", "No events yet."));
    (d.events || []).forEach(function (ev) {
      var li = el("li");
      var meta = el("div", "meta");
      meta.appendChild(el("span", "src", ev.source));
      meta.appendChild(document.createTextNode(new Date(ev.ts).toLocaleString()));
      li.appendChild(meta);
      li.appendChild(el("div", "text", ev.redacted_text || "(no text)"));
      (ev.entities || []).forEach(function (id) {
        var e = ents[id];
        if (e) li.appendChild(el("span", "chip", e.type + ": " + e.canonical));
      });
      ol.appendChild(li);
    });
  }

  function renderGraph(d) {
    var box = $("graph");
    clear(box);
    var ents = d.entities || [];
    if (!ents.length) { box.appendChild(el("p", "empty", "No entities yet.")); return; }
    var groups = {}, order = [];
    ents.forEach(function (e) {
      if (!groups[e.type]) { groups[e.type] = []; order.push(e.type); }
      groups[e.type].push(e);
    });
    var colW = 170, rowH = 34, top = 34, pad = 20;
    var rows = Math.max.apply(null, order.map(function (t) { return groups[t].length; }));
    var w = pad * 2 + colW * order.length, h = top + rows * rowH + pad;
    var root = svg("svg", {viewBox: "0 0 " + w + " " + h, role: "img",
                           "aria-label": "Linked entities graph"});
    var pos = {};
    order.forEach(function (t, ci) {
      var x = pad + ci * colW + 14;
      var label = svg("text", {x: x - 8, y: 18, "class": "col"});
      label.textContent = t;
      root.appendChild(label);
      groups[t].forEach(function (e, ri) { pos[e.id] = {x: x, y: top + ri * rowH + 8, e: e}; });
    });
    (d.edges || []).forEach(function (g) {
      var a = pos[g.a], b = pos[g.b];
      if (!a || !b) return;
      var line = svg("line", {x1: a.x, y1: a.y, x2: b.x, y2: b.y, "class": "edge"});
      var tt = svg("title", {});
      tt.textContent = g.relation + " (" + (g.evidence_event_ids || []).length + " events)";
      line.appendChild(tt);
      root.appendChild(line);
    });
    Object.keys(pos).forEach(function (id) {
      var p = pos[id];
      var c = svg("circle", {cx: p.x, cy: p.y, r: 6, "class": "node t-" + typeClass(p.e.type)});
      var tt = svg("title", {});
      tt.textContent = p.e.type + ": " + p.e.canonical;
      c.appendChild(tt);
      root.appendChild(c);
      var t = svg("text", {x: p.x + 11, y: p.y + 4, "class": "label"});
      t.textContent = trunc(p.e.canonical, 22);
      root.appendChild(t);
    });
    box.appendChild(root);
  }

  function load() {
    if (!token) { setStatus("Paste a bearer token to load a timeline.", true); return Promise.resolve(); }
    setStatus("Loading…");
    return fetch("/v1/dashboard/timeline", {headers: {Authorization: "Bearer " + token}, cache: "no-store"})
      .then(function (r) {
        if (r.status === 401) throw new Error("Invalid token.");
        if (r.status === 404) throw new Error("Dashboard is not enabled on this server.");
        if (!r.ok) throw new Error("Server error (" + r.status + ").");
        return r.json();
      })
      .then(function (d) {
        $("content").hidden = false;
        renderCurrent(d);
        renderGraph(d);
        renderEvents(d);
        setStatus("Updated " + new Date().toLocaleTimeString());
      })
      .catch(function (e) { setStatus(e.message || "Request failed.", true); });
  }

  function schedule() {
    if (timer) { clearInterval(timer); timer = null; }
    if ($("auto").checked) timer = setInterval(load, 5000);
  }

  $("token-form").addEventListener("submit", function (ev) {
    ev.preventDefault();
    token = $("token").value.trim();
    $("token").value = "";
    try { sessionStorage.setItem(TOKEN_KEY, token); } catch (e) { /* in-memory only */ }
    load();
  });
  $("auto").addEventListener("change", schedule);
  if (token) load();
})();
