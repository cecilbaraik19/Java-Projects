/* Bandar Bazaar - admin monitoring screens: services (the yard), traces and events.
   Uses the helpers from app.js (window.Shop). */
(function () {
  'use strict';
  var S = window.Shop;
  var api = S.api, esc = S.esc, ic = S.ic, svc = S.svc, status = S.status, $ = S.$, $$ = S.$$, view = S.view;

  var SHORT = {
    'api-gateway': 'Gateway', 'registry': 'Registry', 'event-bus': 'Event bus', 'auth-service': 'Auth', 'catalog-service': 'Catalog',
    'inventory-service': 'Inventory', 'cart-service': 'Cart', 'order-service': 'Order', 'payment-service': 'Payment', 'notification-service': 'Notification'
  };
  var NO_CHAOS = ['api-gateway', 'registry'];

  function isAdmin() { return S.state.user && S.state.user.role === 'ADMIN'; }
  function needAdmin() {
    if (!S.state.user) { var e = new Error('Please sign in'); e.status = 401; throw e; }
    // Monitoring is a staff tool. Customers who open a monitoring link go back to the shop.
    if (S.state.user.role !== 'ADMIN') { S.go('#/shop'); var stop = new Error('redirect'); stop.redirect = true; throw stop; }
  }
  function port(url) { var m = /:(\d+)$/.exec(url || ''); return m ? m[1] : '?'; }

  // ================================================================== THE YARD
  S.routes.arch = function (parts) {
    needAdmin();
    if (parts[1] === 'traces') return parts[2] ? traceDetail(parts[2]) : traces();
    if (parts[1] === 'events') return events();
    return yard();
  };

  function yard() {
    view.innerHTML = '<div class="head"><div><span class="kicker">registry + every service\'s /internal/stats &middot; refreshes every 2.5 s</span><h1>Service monitor</h1>' +
      '<p>Each container is one microservice: its own program, its own port, its own data folder. They find each other through the registry and talk over HTTP or the event bus.</p></div>' +
      '</div>' +
      '<div class="yard-legend"><span><span class="lamp up"></span> UP - heartbeat received</span><span><span class="lamp down"></span> DOWN - switched off or missed 3 heartbeats</span><span><span class="lamp slow"></span> slowed down on purpose</span></div>' +
      '<div id="yardBody"><p class="muted">Reading the registry...</p></div>';
    var busy = false;
    function load() {
      if (busy) return;
      busy = true;
      Promise.all([api('GET', '/api/arch/services'), api('GET', '/api/arch/events?limit=14').catch(function () { return null; })])
        .then(function (r) { drawYard(r[0], r[1]); })
        .catch(function (e) { var b = $('#yardBody'); if (b) b.innerHTML = '<div class="empty"><h3>Cannot read the services</h3><p>' + esc(e.message) + '</p></div>'; })
        .then(function () { busy = false; });
    }
    load();
    S.setTimer(load, 2500);
  }

  function breakerRow(b) {
    var sub = b.state === 'OPEN' ? 'Refusing calls for ' + Math.ceil(b.retryInMs / 1000) + ' s more' :
      (b.state === 'HALF_OPEN' ? 'Letting ONE trial call through' : (b.failuresInRow ? b.failuresInRow + ' failure(s) in a row (trips at ' + b.threshold + ')' : 'Healthy'));
    return '<div class="breaker"><span class="fuse ' + esc(b.state) + '" title="' + esc(b.state) + '"></span><div><b>' + esc(SHORT[b.service] || b.service) + '</b> <span class="mono muted">' + esc(b.state.replace('_', ' ')) + '</span><div class="sub">' + esc(sub) + '</div></div>' +
      '<span class="mono muted" title="calls / failures / refused">' + b.calls + '/' + b.failures + '/' + b.rejected + '</span></div>';
  }

  function drawYard(d, ev) {
    var body = $('#yardBody');
    if (!body) return;
    var admin = isAdmin();
    var downCount = 0;
    var cards = d.instances.map(function (inst) {
      var st = inst.stats || {};
      var down = inst.status !== 'UP' || st.down;
      if (down) downCount++;
      var slow = st.latencyMs > 0;
      var lamp = down ? 'down' : (slow ? 'slow' : 'up');
      var silent = Math.round((inst.silentMs || 0) / 1000);
      var warn = '';
      if (st.unreachable) warn = 'Not answering at all.';
      else if (st.down && inst.status === 'UP') warn = 'Switched off. Heartbeats stopped - the registry will mark it DOWN in ' + Math.max(0, Math.ceil((d.ttlMs - inst.silentMs) / 1000)) + ' s.';
      else if (st.down) warn = 'Switched off. No heartbeat for ' + silent + ' s, so the registry marked it DOWN and nobody is sent to it.';
      else if (slow) warn = 'Slowed down by ' + st.latencyMs + ' ms per request.';
      var canChaos = admin && NO_CHAOS.indexOf(inst.name) < 0 && !st.unreachable;
      return '<article class="ctr sc-' + esc(inst.name) + (down ? ' is-down' : '') + '">' +
        '<div class="ctr-door"><div><span class="tag">BNDR ' + esc(port(inst.url)) + ' 0</span><h3>' + esc(SHORT[inst.name] || inst.name) + '</h3></div>' +
        '<span class="right"><span class="lamp ' + lamp + '"></span>' + (down ? 'DOWN' : 'UP') + '</span></div>' +
        '<div class="ctr-body"><div class="role">' + esc(inst.role || st.role || '') + '</div>' +
        '<div class="ctr-stats"><div><small>Requests</small><b>' + (st.requests != null ? st.requests : '-') + '</b></div><div><small>Avg ms</small><b>' + (st.avgMs != null ? st.avgMs : '-') + '</b></div><div><small>Errors</small><b>' + (st.errors != null ? st.errors : '-') + '</b></div></div>' +
        '<div class="mono muted">' + esc(inst.name) + ' &middot; :' + esc(port(inst.url)) + (inst.name !== 'registry' ? ' &middot; beat ' + silent + 's ago' : '') + '</div>' +
        (st.subscriptions && st.subscriptions.length ? '<div class="subs">listens to: ' + st.subscriptions.map(esc).join(', ') + '</div>' : '') +
        (st.outbox ? '<div class="warnline">' + st.outbox + ' event(s) waiting in the outbox (event bus is unreachable)</div>' : '') +
        (st.duplicateEvents ? '<div class="subs">ignored ' + st.duplicateEvents + ' duplicate event(s)</div>' : '') +
        (warn ? '<div class="warnline">' + esc(warn) + '</div>' : '') +
        (canChaos ? '<div class="ctr-foot"><button class="btn small ' + (st.down ? 'primary' : 'danger') + '" type="button" data-chaos="' + esc(inst.name) + '" data-down="' + (!st.down) + '">' + ic('power', 'sm') + (st.down ? 'Switch on' : 'Switch off') + '</button>' +
          '<button class="btn small" type="button" data-slow="' + esc(inst.name) + '" data-ms="' + (slow ? 0 : 800) + '">' + ic('clock', 'sm') + (slow ? 'Normal speed' : 'Slow +800 ms') + '</button></div>' : '') +
        '</div></article>';
    }).join('');

    var belt = '';
    if (ev && ev.events) {
      belt = '<div class="belt-wrap"><div class="belt"><h3>' + ic('signal', 'sm') + 'Event bus conveyor <span class="mono muted">' +
        (ev.stats ? ev.stats.published + ' published &middot; ' + ev.stats.delivered + ' delivered &middot; ' + ev.stats.retried + ' retries &middot; ' + ev.stats.dead + ' dead' : '') + '</span>' +
        '<a class="btn small ghost" href="#/arch/events">All events &rarr;</a></h3><div class="belt-items">' +
        (ev.events.length ? ev.events.map(function (e) {
          var bad = e.deliveries.some(function (x) { return x.status === 'DEAD' || x.status === 'RETRYING'; });
          return '<a class="parcel sc-' + esc(e.source) + (bad ? ' bad' : '') + '" href="#/arch/traces/' + esc(e.traceId) + '"><b>' + esc(e.topic) + '</b><span class="to">from ' + esc(SHORT[e.source] || e.source) + ' &rarr; ' +
            (e.deliveries.length ? e.deliveries.map(function (x) { return esc(SHORT[x.service] || x.service) + (x.status === 'DELIVERED' ? ' ✓' : ' (' + x.status.toLowerCase() + ')'); }).join(', ') : 'nobody listens') + '</span></a>';
        }).join('') : '<span class="muted">No events yet. Place an order to see some.</span>') + '</div></div></div>';
    }

    var experiments = '<section class="card"><h2>' + ic('bolt') + ' Failure drills</h2>' + (isAdmin() ?
      '<ol class="steps"><li><b>Switch off Payment</b>, then place an order as riya. The saga cannot take payment, so it <b>releases the stock</b> it had reserved. After 3 failures the order service\'s payment breaker trips to OPEN.</li>' +
      '<li><b>Switch off Catalog</b>, then open the Shop. The gateway shows a <b>saved copy</b> from its fallback cache, and its catalog breaker opens.</li>' +
      '<li><b>Switch off Inventory</b>: the shop still lists products, but with "Stock ?" (graceful degradation).</li>' +
      '<li><b>Switch off Event bus</b>, place an order, then switch it on: events wait in the order service\'s <b>outbox</b> and are sent later. Nothing is lost.</li>' +
      '<li><b>Switch off Notification</b>, place an order, wait 10 s, switch it on: the bus <b>retries</b> and finally the event goes to the dead-letter queue, where you can <b>replay</b> it.</li>' +
      '<li><b>Slow down Inventory</b> and open a trace: you will see the long bar.</li></ol>'
      : '') + '</section>';

    body.innerHTML = (downCount ? '<div class="banner bad">' + ic('bolt') + '<span><b>' + downCount + ' service(s) down.</b> Try the shop now - most of it should still work.</span></div><br>' : '') +
      '<div class="gatehouse"><section class="card"><h2>Gateway circuit breakers</h2><p class="muted">One per service. 3 failures in a row &rarr; OPEN for 10 s (fail fast) &rarr; HALF OPEN (one trial) &rarr; CLOSED.</p><div class="breakers">' +
      d.gatewayBreakers.map(breakerRow).join('') + '</div>' + (admin ? '<p><button class="btn small" type="button" id="resetBreakers">' + ic('refresh', 'sm') + 'Reset breakers + cache</button></p>' : '') + '</section>' +
      '<div class="stack-gap"><section class="card"><h2>Order saga breakers</h2><p class="muted">Inside order-service, around its calls to inventory and payment.</p><div class="breakers">' +
      (d.orderBreakers.length ? d.orderBreakers.map(breakerRow).join('') : '<p class="muted">order-service is not answering.</p>') + '</div></section>' + experiments + '</div></div>' +
      belt + '<div class="yard">' + cards + '</div>';

    $$('[data-chaos]', body).forEach(function (b) {
      b.addEventListener('click', function () {
        b.disabled = true;
        var down = b.getAttribute('data-down') === 'true';
        api('POST', '/api/arch/chaos', { service: b.getAttribute('data-chaos'), down: down })
          .then(function () { S.toast(b.getAttribute('data-chaos') + (down ? ' switched OFF' : ' switched ON')); })
          .catch(function (e) { S.toast(e.message, true); });
      });
    });
    $$('[data-slow]', body).forEach(function (b) {
      b.addEventListener('click', function () {
        b.disabled = true;
        api('POST', '/api/arch/chaos', { service: b.getAttribute('data-slow'), latencyMs: Number(b.getAttribute('data-ms')) })
          .then(function () { S.toast('Speed changed'); }).catch(function (e) { S.toast(e.message, true); });
      });
    });
    var rb = $('#resetBreakers', body);
    if (rb) rb.addEventListener('click', function () { api('POST', '/api/arch/breakers/reset').then(function () { S.toast('Breakers closed, cache cleared'); }); });
  }

  // ================================================================== TRACES
  function traces() {
    var q = parseQ();
    return api('GET', '/api/arch/traces' + (q.service ? '?service=' + encodeURIComponent(q.service) : '')).then(function (list) {
      var names = Object.keys(SHORT).filter(function (n) { return n !== 'registry'; });
      view.innerHTML = '<div class="head"><div><span class="kicker">distributed tracing</span><h1>Traces</h1><p>One trace = one user action followed through every service it touched. The gateway gives each request a trace id (X-Trace-Id) and every service passes it on.</p></div>' +
        '<div class="row"><select class="inline" id="svcFilter"><option value="">All services</option>' + names.map(function (n) { return '<option value="' + n + '"' + (q.service === n ? ' selected' : '') + '>' + n + '</option>'; }).join('') + '</select>' +
        '<button class="btn" type="button" id="reloadTr">' + ic('refresh', 'sm') + 'Refresh</button></div></div>' +
        (list.length ? '<div class="trace-list">' + list.map(function (t) {
          return '<a class="trace-row' + (t.error ? ' err' : '') + '" href="#/arch/traces/' + esc(t.traceId) + '"><span class="when mono muted">' + S.ago(t.start) + '</span>' +
            '<span class="p"><b>' + esc(t.method) + '</b> ' + esc(t.path) + ' <span class="muted">&middot; ' + t.spans + ' spans' + (t.async ? ' &middot; async events' : '') + '</span></span>' +
            '<span class="svcs">' + t.services.map(function (s) { return '<span class="svc sc-' + esc(s) + '">' + esc(SHORT[s] || s) + '</span>'; }).join('') + '</span>' +
            '<span class="ms">' + t.ms + ' ms</span></a>';
        }).join('') + '</div>' : '<div class="empty">' + ic('route') + '<h3>No traces yet</h3><p>Use the shop for a minute, then come back.</p><a class="btn primary" href="#/shop">Go shopping</a></div>');
      $('#svcFilter').addEventListener('change', function () { S.go('#/arch/traces' + (this.value ? '?service=' + this.value : '')); });
      $('#reloadTr').addEventListener('click', function () { S.render(); });
    });
  }
  function parseQ() {
    var q = {}, i = location.hash.indexOf('?');
    if (i >= 0) location.hash.slice(i + 1).split('&').forEach(function (p) { var kv = p.split('='); q[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || ''); });
    return q;
  }

  function traceDetail(id) {
    return api('GET', '/api/arch/traces/' + encodeURIComponent(id)).then(function (d) {
      var sp = d.spans;
      var t0 = sp[0].start, t1 = t0;
      sp.forEach(function (s) { t1 = Math.max(t1, s.start + s.ms); });
      var total = Math.max(1, t1 - t0);
      var services = [];
      sp.forEach(function (s) { if (services.indexOf(s.service) < 0) services.push(s.service); });
      var rows = sp.map(function (s) {
        var left = (s.start - t0) * 100 / total, width = Math.max(0.6, s.ms * 100 / total);
        var isStep = s.method === 'STEP';
        var label = s.ms + 'ms' + (s.status >= 400 ? ' &middot; ' + s.status : '');
        return '<div class="wf-row"><div class="wf-label"><span class="svc sc-' + esc(s.service) + '">' + esc(SHORT[s.service] || s.service) + '</span>' +
          '<span class="p" title="' + esc(s.method + ' ' + s.path) + '">' + (isStep ? '' : esc(s.method) + ' ') + esc(s.path) + (s.caller && s.caller !== s.service ? ' &larr; ' + esc(s.caller) : '') + '</span></div>' +
          '<div class="wf-track"><span class="wf-bar sc-' + esc(s.service) + (s.status >= 400 ? ' err' : '') + (isStep ? ' step' : '') + '" data-l="' + left.toFixed(2) + '" data-w="' + Math.min(100 - left, width).toFixed(2) + '" title="' + s.ms + ' ms, status ' + s.status + '">' + (width >= 7 ? label : '') + '</span>' +
          (width < 7 ? '<span class="wf-txt" data-l="' + Math.min(92, left + width).toFixed(2) + '">' + label + '</span>' : '') + '</div></div>';
      }).join('');
      var ticks = [0, 0.25, 0.5, 0.75, 1].map(function (f) { return '<span>' + Math.round(total * f) + ' ms</span>'; }).join('');
      view.innerHTML = '<p><a href="#/arch/traces" class="btn small ghost">&larr; All traces</a></p>' +
        '<div class="head"><div><span class="kicker">trace ' + esc(d.traceId) + '</span><h1>' + esc(sp[0].method === 'STEP' ? 'Background work' : sp[0].method + ' ' + sp[0].path) + '</h1>' +
        '<p>' + sp.length + ' spans across ' + services.length + ' services in ' + total + ' ms. Each bar is one request inside one service; striped bars are saga steps.</p></div>' +
        '<div class="row">' + services.map(svc).join('') + '</div></div>' +
        '<section class="card"><div class="wf-axis"><span></span><div class="ticks">' + ticks + '</div></div><div class="wf">' + rows + '</div></section>' +
        (d.events.length ? '<section class="card mt"><h2>Events published during this action</h2><p class="muted">These were sent <b>asynchronously</b>: the user did not wait for them. Their deliveries appear above as POST /events bars.</p><div class="tbl-wrap"><table class="tbl"><thead><tr><th>Topic</th><th>From</th><th>Delivered to</th></tr></thead><tbody>' +
          d.events.map(function (e) {
            return '<tr><td class="mono"><b>' + esc(e.topic) + '</b></td><td>' + svc(e.source) + '</td><td><div class="deliv">' + (e.deliveries.length ? e.deliveries.map(function (x) { return svc(x.service) + ' ' + status(x.status); }).join(' ') : '<span class="muted">no subscribers</span>') + '</div></td></tr>';
          }).join('') + '</tbody></table></div></section>' : '');
    });
  }

  // ================================================================== EVENTS
  function events() {
    return api('GET', '/api/arch/events?limit=80').then(function (d) {
      var st = d.stats || {};
      var subsByTopic = {};
      d.subscriptions.forEach(function (s) { (subsByTopic[s.topic] = subsByTopic[s.topic] || []).push(s.service); });
      view.innerHTML = '<div class="head"><div><span class="kicker">event-bus &middot; publish / subscribe</span><h1>Events</h1><p>Services announce what happened ("order.confirmed"). Whoever cares listens. The publisher never waits and never knows who listened.</p></div>' +
        '<button class="btn" type="button" id="reloadEv">' + ic('refresh', 'sm') + 'Refresh</button></div>' +
        '<div class="kpis"><div class="kpi"><small>Published</small><b>' + (st.published || 0) + '</b></div><div class="kpi"><small>Delivered</small><b>' + (st.delivered || 0) + '</b></div>' +
        '<div class="kpi"><small>Retries</small><b>' + (st.retried || 0) + '</b><span>receiver was down, tried again</span></div><div class="kpi"><small>Dead letters</small><b>' + (st.deadLetters || 0) + '</b><span>gave up after 4 tries</span></div></div>' +
        (d.deadLetters.length ? '<section class="card"><h2>Dead-letter queue</h2><p class="muted">Deliveries that failed 4 times. Switch the service back on, then replay.</p><div class="tbl-wrap"><table class="tbl"><thead><tr><th>Event</th><th>For</th><th>Last error</th><th>When</th><th></th></tr></thead><tbody>' +
          d.deadLetters.map(function (x) {
            return '<tr><td class="mono"><b>' + esc(x.topic) + '</b><div class="muted">' + esc(x.eventId) + '</div></td><td>' + svc(x.service) + '</td><td>' + esc(x.error) + '</td><td>' + S.when(x.at) + '</td>' +
              '<td class="num">' + (isAdmin() ? '<button class="btn small" type="button" data-replay="' + esc(x.id) + '">' + ic('refresh', 'sm') + 'Replay</button>' : '') + '</td></tr>';
          }).join('') + '</tbody></table></div></section><br>' : '') +
        '<div class="two-col"><section class="card"><h2>Recent events</h2>' + (d.events.length ? '<div class="tbl-wrap"><table class="tbl"><thead><tr><th>Topic</th><th>From</th><th>Deliveries</th><th>When</th></tr></thead><tbody>' +
          d.events.map(function (e) {
            return '<tr><td><a class="mono" href="#/arch/traces/' + esc(e.traceId) + '"><b>' + esc(e.topic) + '</b></a><div class="muted mono">' + esc(e.id) + '</div></td><td>' + svc(e.source) + '</td>' +
              '<td><div class="deliv">' + (e.deliveries.length ? e.deliveries.map(function (x) {
                return '<span class="status st-' + esc(x.status) + '" title="' + esc(x.attempts + ' attempt(s)' + (x.lastError ? ': ' + x.lastError : '')) + '">' + esc(SHORT[x.service] || x.service) + (x.attempts > 1 ? ' x' + x.attempts : '') + '</span>';
              }).join('') : '<span class="muted">nobody listens</span>') + '</div></td><td class="muted">' + S.ago(e.publishedAt) + '</td></tr>';
          }).join('') + '</tbody></table></div>' : '<p class="muted">No events yet.</p>') + '</section>' +
        '<section class="card"><h2>Who listens to what</h2><ul class="toplist">' + Object.keys(subsByTopic).sort().map(function (t) {
          return '<li><b class="mono">' + esc(t) + '</b><span class="v"><span class="deliv">' + subsByTopic[t].map(function (s) { return '<span class="svc sc-' + esc(s) + '">' + esc(SHORT[s] || s) + '</span>'; }).join('') + '</span></span></li>';
        }).join('') + '</ul></section></div>';
      $('#reloadEv').addEventListener('click', function () { S.render(); });
      $$('[data-replay]', view).forEach(function (b) {
        b.addEventListener('click', function () {
          api('POST', '/api/arch/dead-letters/' + encodeURIComponent(b.getAttribute('data-replay')) + '/replay').then(function () { S.toast('Sent again'); setTimeout(S.render, 800); })
            .catch(function (e) { S.toast(e.message, true); });
        });
      });
    });
  }

})();
