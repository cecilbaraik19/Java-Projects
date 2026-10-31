/* Bandar Bazaar - shop and admin screens.
   Plain JavaScript, no libraries. The browser only ever talks to the API gateway (/api/...).
   arch.js adds the admin monitoring screens (services, traces, events). */
(function () {
  'use strict';

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
  var view = $('#view');

  var state = { user: null, cartCount: 0, categories: [], timer: null, fallback: null };
  var routes = {};

  // ------------------------------------------------------------------ small helpers
  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  /* A product picture: the real photo, with the drawn icon underneath as a fallback if the photo is missing. */
  function pic(p) {
    return '<span class="pic' + (p.image ? ' has-img' : '') + '">' + ic(p.icon || 'box') +
      (p.image ? '<img src="' + esc(p.image) + '" alt="' + esc(p.name || '') + '" loading="lazy" decoding="async">' : '') + '</span>';
  }
  document.addEventListener('error', function (e) {
    var img = e.target;
    if (img && img.tagName === 'IMG' && img.parentNode && img.parentNode.classList && img.parentNode.classList.contains('pic')) {
      img.parentNode.classList.remove('has-img');
      img.remove();
    }
  }, true);
  function ic(name, cls) { return '<svg class="ic ' + (cls || '') + '" aria-hidden="true"><use href="/static/icons.svg#i-' + esc(name) + '"/></svg>'; }
  function money(paise) {
    if (paise == null) return '-';
    var rs = paise / 100;
    return '₹' + rs.toLocaleString('en-IN', { minimumFractionDigits: rs % 1 ? 2 : 0, maximumFractionDigits: 2 });
  }
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function when(ms) {
    if (!ms) return '-';
    var d = new Date(ms);
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ', ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function ago(ms) {
    var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return s + ' s ago';
    if (s < 3600) return Math.round(s / 60) + ' min ago';
    if (s < 86400) return Math.round(s / 3600) + ' h ago';
    return Math.round(s / 86400) + ' d ago';
  }
  var CAT = { 'Electronics': 'electronics', 'Fashion': 'fashion', 'Home & Kitchen': 'home', 'Books': 'books', 'Sports & Fitness': 'sports', 'Beauty': 'beauty' };
  function catClass(c) { return 'cat-' + (CAT[c] || 'electronics'); }
  function svc(name) { return '<span class="svc sc-' + esc(name) + '">' + esc(name) + '</span>'; }
  function status(s) { return '<span class="status st-' + esc(s) + '">' + esc(String(s).replace('_', ' ')) + '</span>'; }
  function uid() {
    try { if (window.crypto && crypto.randomUUID) return crypto.randomUUID(); } catch (e) { /* older browser */ }
    return 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  }
  function store(key, val) {
    try {
      if (val === undefined) return JSON.parse(localStorage.getItem(key) || 'null');
      localStorage.setItem(key, JSON.stringify(val));
    } catch (e) { /* storage blocked - fine */ }
    return null;
  }
  /* Bar widths etc. are set from JS (the Content-Security-Policy blocks inline style attributes) */
  function applySizes(root) {
    $$('[data-w]', root).forEach(function (el) { el.style.width = el.getAttribute('data-w') + '%'; });
    $$('[data-h]', root).forEach(function (el) { el.style.height = el.getAttribute('data-h') + '%'; });
    $$('[data-l]', root).forEach(function (el) { el.style.left = el.getAttribute('data-l') + '%'; });
  }

  // ------------------------------------------------------------------ toast, banner, modal
  var toastTimer;
  function toast(msg, bad) {
    var t = $('#toast');
    t.innerHTML = ic(bad ? 'x' : 'check', 'sm') + '<span>' + esc(msg) + '</span>';
    t.className = 'toast' + (bad ? ' bad' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = 'toast hidden'; }, bad ? 5200 : 3000);
  }
  function banner(html, bad) {
    var b = $('#banner');
    if (!html) { b.className = 'banner hidden'; b.innerHTML = ''; return; }
    b.className = 'banner' + (bad ? ' bad' : '');
    b.innerHTML = html;
  }

  var modalOnSave = null;
  function modal(title, bodyHtml, saveLabel, onSave, opts) {
    opts = opts || {};
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = bodyHtml;
    $('#modalErr').textContent = '';
    $('#modalFoot').innerHTML = '<button type="button" class="btn" data-close>Close</button>' +
      (saveLabel ? '<button type="submit" class="btn ' + (opts.danger ? 'danger' : 'primary') + '" id="modalSave">' + esc(saveLabel) + '</button>' : '');
    modalOnSave = onSave;
    var m = $('#modal');
    if (!m.open) m.showModal();
    var first = $('#modalBody input, #modalBody select, #modalBody textarea');
    if (first) first.focus();
  }
  function closeModal() { var m = $('#modal'); if (m.open) m.close(); modalOnSave = null; }
  $('#modalForm').addEventListener('submit', function (e) {
    e.preventDefault();
    if (!modalOnSave) return closeModal();
    var btn = $('#modalSave');
    if (btn) btn.disabled = true;
    Promise.resolve().then(function () { return modalOnSave(formData($('#modalForm'))); })
      .then(function (keepOpen) { if (!keepOpen) closeModal(); })
      .catch(function (err) { $('#modalErr').textContent = err.message; })
      .then(function () { if (btn) btn.disabled = false; });
  });
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalFoot').addEventListener('click', function (e) { if (e.target.closest('[data-close]')) closeModal(); });

  function formData(form) {
    var out = {};
    $$('input, select, textarea', form).forEach(function (el) {
      if (!el.name) return;
      if (el.type === 'checkbox') out[el.name] = el.checked;
      else out[el.name] = el.value;
    });
    return out;
  }

  // ------------------------------------------------------------------ API calls (+ the live ticker)
  var ticker = [];
  function api(method, path, body) {
    var t0 = performance.now();
    var opts = { method: method, credentials: 'same-origin', headers: { 'Accept': 'application/json' } };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    return fetch(path, opts).then(function (res) {
      var info = {
        method: method, path: path.split('?')[0], status: res.status, ms: Math.round(performance.now() - t0),
        trace: res.headers.get('X-Trace-Id'), route: res.headers.get('X-Route') || res.headers.get('X-Served-By'),
        fallback: res.headers.get('X-Fallback')
      };
      if (path.indexOf('/api/arch') !== 0 && path.indexOf('/api/notifications') !== 0) pushTicker(info);
      if (info.fallback) {
        state.fallback = res.headers.get('X-Fallback-Reason') || 'a service is not answering';
      }
      return res.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = { error: 'Bad reply from the server' }; }
        if (!res.ok) {
          var err = new Error((data && data.error) || ('Request failed (' + res.status + ')'));
          err.status = res.status;
          err.trace = info.trace;
          if (res.status === 401 && state.user && path !== '/api/auth/login') { state.user = null; renderChrome(); }
          throw err;
        }
        return data;
      });
    }, function () {
      var err = new Error('Cannot reach the API gateway. Is the app running?');
      err.status = 0;
      throw err;
    });
  }
  function pushTicker(info) {
    ticker.unshift(info);
    ticker = ticker.slice(0, 8);
    $('#tickerList').innerHTML = ticker.map(function (t) {
      var cls = t.status >= 500 ? 'e' : (t.status >= 400 ? 'w' : '');
      var inner = '<span class="code ' + cls + '">' + t.status + '</span>' + esc(t.method) + ' ' + esc(t.path) +
        (t.route ? ' <span class="muted">&rarr; ' + esc(t.route) + '</span>' : '') +
        (t.fallback ? ' <span class="fb">[cache]</span>' : '') + ' <b>' + t.ms + 'ms</b>';
      return '<li>' + (t.trace && isAdmin() ? '<a href="#/arch/traces/' + esc(t.trace) + '" title="Open this trace">' + inner + '</a>' : '<a>' + inner + '</a>') + '</li>';
    }).join('');
  }

  // ------------------------------------------------------------------ routing
  function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }
  function parseHash() {
    var h = location.hash.replace(/^#\/?/, '');
    var q = {};
    var i = h.indexOf('?');
    if (i >= 0) {
      h.slice(i + 1).split('&').forEach(function (p) {
        var kv = p.split('=');
        if (kv[0]) q[decodeURIComponent(kv[0])] = decodeURIComponent((kv[1] || '').replace(/\+/g, ' '));
      });
      h = h.slice(0, i);
    }
    return { parts: h ? h.split('/') : ['shop'], q: q };
  }
  function setTimer(fn, ms) { clearTimer(); state.timer = setInterval(fn, ms); }
  function clearTimer() { if (state.timer) { clearInterval(state.timer); state.timer = null; } }

  function isAdmin() { return !!(state.user && state.user.role === 'ADMIN'); }

  function render() {
    clearTimer();
    state.fallback = null;
    banner(null);
    closeMenus();
    var r = parseHash();
    var key = r.parts[0];
    var handler = routes[key] || routes.shop;
    renderLanes(r.parts);
    view.innerHTML = '<p class="muted">Loading...</p>';
    Promise.resolve().then(function () { return handler(r.parts, r.q); })
      .then(function () {
        applySizes(view);
        if (state.fallback) {
          banner(ic('bolt') + (isAdmin()
            ? '<span><b>Showing a saved copy.</b> ' + esc(state.fallback) + '. The API gateway answered from its fallback cache (circuit breaker + fallback).</span>'
            : '<span><b>Some details may be a little out of date.</b> We are having trouble reaching part of the store. Please try again in a moment.</span>'));
        }
      })
      .catch(function (err) {
        if (err.status === 401) { go('#/login?next=' + encodeURIComponent(location.hash)); return; }
        if (err.redirect) return;
        view.innerHTML = '<div class="empty">' + ic('x') + '<h3>Something went wrong</h3><p>' + esc(err.message) + '</p>' +
          (err.trace && isAdmin() ? '<p><a class="btn small" href="#/arch/traces/' + esc(err.trace) + '">' + ic('route', 'sm') + ' See what happened (trace)</a></p>' : '') + '</div>';
      });
    window.scrollTo(0, 0);
  }
  window.addEventListener('hashchange', render);

  function needUser() {
    if (!state.user) { var e = new Error('Please sign in'); e.status = 401; throw e; }
  }
  function needAdmin() {
    needUser();
    if (state.user.role !== 'ADMIN') throw new Error('Only the store admin can open this page');
  }

  // ------------------------------------------------------------------ chrome: lanes, user menu, cart, bell
  var CAT_LIST = ['Electronics', 'Fashion', 'Home & Kitchen', 'Books', 'Sports & Fitness', 'Beauty'];
  function renderLanes(parts) {
    var at = parts.join('/');
    function lane(href, label, icon, on) { return '<a href="' + href + '" class="' + (on ? 'on' : '') + '">' + ic(icon, 'sm') + label + '</a>'; }
    var h = lane('#/shop', 'Shop', 'box', parts[0] === 'shop' || parts[0] === 'p');
    if (state.user) {
      h += lane('#/orders', 'My orders', 'truck', parts[0] === 'orders');
      h += lane('#/wallet', 'Wallet', 'wallet', parts[0] === 'wallet');
    }
    if (!isAdmin()) {
      CAT_LIST.forEach(function (c) { h += lane('#/shop?cat=' + encodeURIComponent(c), c, 'list', parseHash().q.cat === c); });
    }
    if (isAdmin()) {
      h += '<span class="sep"></span>';
      h += lane('#/admin', 'Overview <span class="tag">ADMIN</span>', 'chart', at === 'admin');
      h += lane('#/admin/products', 'Products', 'list', at === 'admin/products');
      h += lane('#/admin/stock', 'Stock', 'grid', at === 'admin/stock');
      h += lane('#/admin/orders', 'Orders', 'truck', at === 'admin/orders');
      h += '<span class="sep"></span>';
      h += lane('#/arch', 'Services', 'grid', at === 'arch');
      h += lane('#/arch/traces', 'Traces', 'route', parts[0] === 'arch' && parts[1] === 'traces');
      h += lane('#/arch/events', 'Events', 'signal', parts[0] === 'arch' && parts[1] === 'events');
    }
    $('#lanes').innerHTML = h;
  }

  function renderChrome() {
    var u = state.user;
    $('#signInBtn').classList.toggle('hidden', !!u);
    $('#userBtn').classList.toggle('hidden', !u);
    $('#cartBtn').classList.toggle('hidden', !u);
    $('#bellWrap').classList.toggle('hidden', !u);
    if (u) {
      $('#userBtn').innerHTML = ic('user', 'sm') + '<span>' + esc(u.name.split(' ')[0]) + '</span>';
      $('#userMenu').innerHTML = '<div class="who"><b>' + esc(u.name) + '</b><span class="muted mono">' + esc(u.username || '') + ' &middot; ' + esc(u.role) + '</span></div>' +
        '<a href="#/orders">' + ic('truck', 'sm') + 'My orders</a><a href="#/wallet">' + ic('wallet', 'sm') + 'Wallet</a>' +
        (u.role === 'ADMIN' ? '<a href="#/admin">' + ic('chart', 'sm') + 'Admin overview</a>' : '') +
        '<button type="button" id="logoutBtn">' + ic('out', 'sm') + 'Sign out</button>';
    }
    renderLanes(parseHash().parts);
    updateCartCount();
    // The live API-call bar is a developer tool: only the admin sees it.
    $('#ticker').classList.toggle('hidden', !isAdmin());
    document.body.classList.toggle('with-ticker', isAdmin());
  }
  function updateCartCount(n) {
    if (typeof n === 'number') state.cartCount = n;
    var c = $('#cartCount');
    c.textContent = state.cartCount;
    c.classList.toggle('hidden', !state.user || !state.cartCount);
  }
  function refreshCart() {
    if (!state.user) return Promise.resolve();
    return api('GET', '/api/cart').then(function (c) { updateCartCount(c.count || 0); }).catch(function () { /* cart service down: keep old count */ });
  }

  function closeMenus() {
    $('#userMenu').classList.add('hidden');
    $('#inbox').classList.add('hidden');
    $('#bellBtn').setAttribute('aria-expanded', 'false');
  }
  $('#userBtn').addEventListener('click', function (e) { e.stopPropagation(); var m = $('#userMenu'); var open = m.classList.contains('hidden'); closeMenus(); m.classList.toggle('hidden', !open); });
  $('#userMenu').addEventListener('click', function (e) {
    if (e.target.closest('#logoutBtn')) {
      api('POST', '/api/auth/logout').then(function () { state.user = null; renderChrome(); toast('Signed out'); go('#/shop'); });
    }
  });
  document.addEventListener('click', function (e) {
    if (!e.target.closest('#userMenu') && !e.target.closest('#userBtn') && !e.target.closest('#bellWrap')) closeMenus();
  });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeMenus(); });

  $('#themeBtn').addEventListener('click', function () {
    var dark = document.documentElement.getAttribute('data-theme') === 'dark' ||
      (!document.documentElement.getAttribute('data-theme') && window.matchMedia('(prefers-color-scheme: dark)').matches);
    var next = dark ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('bandar-theme', next); } catch (e) { /* ignore */ }
    themeIcon();
  });
  function themeIcon() {
    var dark = document.documentElement.getAttribute('data-theme') === 'dark' ||
      (!document.documentElement.getAttribute('data-theme') && window.matchMedia('(prefers-color-scheme: dark)').matches);
    $('#themeBtn').innerHTML = ic(dark ? 'sun' : 'moon');
  }

  $('#searchForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var q = $('#searchBox').value.trim();
    go('#/shop' + (q ? '?q=' + encodeURIComponent(q) : ''));
  });

  // ---- notifications (bell)
  var NOTE_ICON = { welcome: 'star', order: 'check', problem: 'x', ship: 'truck', money: 'wallet', stock: 'grid' };
  function loadInbox(open) {
    if (!state.user) return Promise.resolve();
    return api('GET', '/api/notifications').then(function (d) {
      $('#bellDot').classList.toggle('hidden', !d.unread);
      if (!open && $('#inbox').classList.contains('hidden')) return;
      $('#inbox').innerHTML = '<div class="inbox-head"><h3>Inbox ' + (d.unread ? '<span class="muted">(' + d.unread + ' new)</span>' : '') + '</h3>' +
        (d.unread ? '<button class="btn small ghost" type="button" data-act="read-all">Mark all read</button>' : '') + '</div>' +
        (d.items.length ? d.items.map(function (n) {
          return '<div class="note-item' + (n.read ? '' : ' unread') + '"><span class="ni">' + ic(NOTE_ICON[n.kind] || 'bell', 'sm') + '</span><div><b>' + esc(n.title) + '</b>' + esc(n.body) +
            '<div class="meta">' + ago(n.at) + (isAdmin() ? ' &middot; event <b>' + esc(n.topic || '') + '</b>' +
            (n.traceId ? ' &middot; <a href="#/arch/traces/' + esc(n.traceId) + '">trace</a>' : '') : '') + '</div></div></div>';
        }).join('') : '<p class="note">No messages yet.</p>');
    }).catch(function () {
      if (open) $('#inbox').innerHTML = '<div class="inbox-head"><h3>Inbox</h3></div>' + '<p class="note">Messages cannot be loaded right now. Your orders are safe - new messages will appear soon.</p>';
    });
  }
  $('#bellBtn').addEventListener('click', function (e) {
    e.stopPropagation();
    var box = $('#inbox');
    var open = box.classList.contains('hidden');
    closeMenus();
    if (open) { box.classList.remove('hidden'); $('#bellBtn').setAttribute('aria-expanded', 'true'); box.innerHTML = '<p class="note">Loading...</p>'; loadInbox(true); }
  });
  $('#inbox').addEventListener('click', function (e) {
    if (e.target.closest('[data-act="read-all"]')) api('POST', '/api/notifications/read-all').then(function () { loadInbox(true); });
  });
  setInterval(function () { loadInbox(false); }, 20000);

  // ------------------------------------------------------------------ shared product bits
  function stamp(p) {
    if (p.available == null) return '<span class="stamp unknown" title="Stock information is not available right now">Stock ?</span>';
    if (p.available <= 0) return '<span class="stamp out">Sold out</span>';
    if (p.low) return '<span class="stamp low">Only ' + p.available + ' left</span>';
    return '<span class="stamp ok">In stock</span>';
  }
  function stars(p) {
    if (!p.reviews) return '<span class="stars muted">New</span>';
    return '<span class="stars"><b>' + ic('star') + Number(p.rating).toFixed(1) + '</b>' + p.reviews + ' reviews</span>';
  }
  function off(p) { return p.mrp > p.price ? Math.round((p.mrp - p.price) * 100 / p.mrp) : 0; }
  function labelCard(p) {
    var o = off(p);
    return '<article class="label ' + catClass(p.category) + '">' +
      '<a class="label-top" href="#/p/' + p.id + '" aria-label="' + esc(p.name) + '"><span class="code">' + esc(p.sku) + '</span>' +
      (o >= 5 ? '<span class="off">' + o + '% OFF</span>' : '') + pic(p) + '</a>' +
      '<div class="perf"></div>' +
      '<div class="label-body"><span class="brand-line">' + esc(p.brand) + '</span>' +
      '<h3><a href="#/p/' + p.id + '">' + esc(p.name) + '</a></h3>' + stars(p) +
      '<div class="price-row"><span class="price">' + money(p.price) + '</span>' + (p.mrp > p.price ? '<span class="mrp">' + money(p.mrp) + '</span>' : '') + '</div>' +
      stamp(p) +
      '<div class="label-foot"><span class="barcode" aria-hidden="true"></span><span class="sku">#' + String(p.id).padStart(4, '0') + '</span><span class="spacer"></span>' +
      '<button class="add-btn" type="button" data-act="add" data-id="' + p.id + '" title="Add to cart"' + (p.available === 0 ? ' disabled' : '') + '>' + ic('plus') + '</button></div>' +
      '</div></article>';
  }
  function addToCart(id, qty) {
    if (!state.user) { toast('Please sign in to use the cart'); go('#/login?next=' + encodeURIComponent(location.hash)); return Promise.resolve(); }
    return api('POST', '/api/cart/items', { productId: Number(id), qty: qty || 1 }).then(function (c) {
      updateCartCount(c.count || 0);
      toast('Added to cart');
    }).catch(function (e) { toast(e.message, true); });
  }
  view.addEventListener('click', function (e) {
    var add = e.target.closest('[data-act="add"]');
    if (add && !add.closest('.pdp')) { addToCart(add.getAttribute('data-id'), 1); }
  });

  // ------------------------------------------------------------------ SHOP
  var TILES = [['Electronics', 'elc-hp-01', 'Headphones, phones, laptops'], ['Fashion', 'fsh-bp-10', 'Bags, shoes, shirts'],
    ['Home & Kitchen', 'hom-mg-14', 'Mugs, lamps, cookware'], ['Sports & Fitness', 'spt-db-25', 'Gym, yoga, games']];
  function tileImg(sku) { return '/static/img/products/' + sku + '.jpg'; }
  routes.shop = function (parts, q) {
    var qs = [];
    if (q.cat) qs.push('category=' + encodeURIComponent(q.cat));
    if (q.q) qs.push('q=' + encodeURIComponent(q.q));
    if (q.sort) qs.push('sort=' + encodeURIComponent(q.sort));
    $('#searchBox').value = q.q || '';
    var cats = state.categories.length ? Promise.resolve(state.categories) :
      api('GET', '/api/categories').then(function (c) { state.categories = c; return c; }).catch(function () { return []; });
    return Promise.all([api('GET', '/api/shop/products' + (qs.length ? '?' + qs.join('&') : '')), cats]).then(function (res) {
      var d = res[0], categories = res[1];
      var filtered = q.cat || q.q;
      var h = '';
      if (!filtered) {
        h += '<section class="hero"><div class="hero-text"><span class="kicker">Festive sale &middot; up to 40% off</span>' +
          '<h1>Big brands. <em>Small prices.</em></h1>' +
          '<p>Electronics, fashion, home, books, sports and beauty - packed with care and shipped from our harbour to your door.</p>' +
          '<div class="row"><a class="btn primary" href="#products">' + ic('box', 'sm') + 'Shop now</a><a class="btn dark" href="#/shop?sort=price-asc">' + ic('star', 'sm') + 'Lowest prices first</a></div>' +
          '<ul class="perks"><li>' + ic('truck', 'sm') + 'Free delivery above &#8377;999</li><li>' + ic('wallet', 'sm') + 'One-tap Bandar Pay</li><li>' + ic('undo', 'sm') + 'Cancel before shipping, full refund</li></ul></div>' +
          '<div class="hero-tiles">' + TILES.map(function (t) {
            return '<a class="tile ' + catClass(t[0]) + '" href="#/shop?cat=' + encodeURIComponent(t[0]) + '">' + pic({ image: tileImg(t[1]), icon: 'box', name: t[0] }) +
              '<span class="tile-label"><b>' + esc(t[0]) + '</b><small>' + esc(t[2]) + '</small></span></a>';
          }).join('') + '</div></section>';
      }
      h += '<div class="doors" id="products" role="list">' +
        '<a role="listitem" class="door' + (!q.cat ? ' on' : '') + '" href="#/shop' + (q.q ? '?q=' + encodeURIComponent(q.q) : '') + '"><span class="swatch"></span>All</a>' +
        categories.map(function (c) {
          return '<a role="listitem" class="door ' + catClass(c.name) + (q.cat === c.name ? ' on' : '') + '" href="#/shop?cat=' + encodeURIComponent(c.name) + '"><span class="swatch"></span>' + esc(c.name) + ' <span class="n">' + c.count + '</span></a>';
        }).join('') + '</div>';
      var sorts = [['popular', 'Most popular'], ['price-asc', 'Price: low to high'], ['price-desc', 'Price: high to low'], ['rating', 'Best rated'], ['newest', 'Newest']];
      h += '<div class="toolbar"><label class="sr" for="sortSel">Sort</label><select id="sortSel" class="inline">' + sorts.map(function (s) {
        return '<option value="' + s[0] + '"' + ((q.sort || 'popular') === s[0] ? ' selected' : '') + '>' + s[1] + '</option>';
      }).join('') + '</select><span class="info">' + d.products.length + ' products' + (q.cat ? ' in ' + esc(q.cat) : '') + (q.q ? ' for &ldquo;' + esc(q.q) + '&rdquo;' : '') + '</span><span class="spacer"></span>' +
        (isAdmin() ? '<span class="composed">Composed by ' + svc('api-gateway') + ' from ' + (d.fromCache ? '<b>its cache</b>' : svc('catalog-service')) + (d.stockKnown ? ' + ' + svc('inventory-service') : ' <b>(inventory down)</b>') + '</span>' : '') + '</div>';
      h += d.products.length ? '<div class="labels">' + d.products.map(labelCard).join('') + '</div>' :
        '<div class="empty">' + ic('search') + '<h3>Nothing found</h3><p>Try another word or category.</p></div>';
      view.innerHTML = h;
      $('#sortSel').addEventListener('change', function () {
        var p = []; if (q.cat) p.push('cat=' + encodeURIComponent(q.cat)); if (q.q) p.push('q=' + encodeURIComponent(q.q));
        p.push('sort=' + this.value); go('#/shop?' + p.join('&'));
      });
      var link = $('a[href="#products"]', view);
      if (link) link.addEventListener('click', function (e) { e.preventDefault(); $('#products').scrollIntoView({ behavior: 'smooth' }); });
    });
  };

  // ------------------------------------------------------------------ PRODUCT PAGE
  routes.p = function (parts) {
    return api('GET', '/api/shop/products/' + encodeURIComponent(parts[1])).then(function (d) {
      var p = d.product, o = off(p), qty = 1;
      var stockText = p.available == null ? 'Unknown' : (p.available <= 0 ? 'Sold out' : p.available + ' available');
      view.innerHTML = '<p><a href="#/shop?cat=' + encodeURIComponent(p.category) + '" class="btn small ghost">&larr; ' + esc(p.category) + '</a></p>' +
        '<div class="pdp ' + catClass(p.category) + '"><div class="pdp-art">' + pic(p) + '<span class="code">' + esc(p.sku) + '<small>' + esc(p.category.toUpperCase()) + '</small></span></div>' +
        '<div class="pdp-info"><span class="kicker">' + esc(p.brand) + '</span><h1>' + esc(p.name) + '</h1>' + stars(p) +
        '<div class="price-row big">' + '<span class="price">' + money(p.price) + '</span>' +
        (p.mrp > p.price ? '<span class="mrp">MRP ' + money(p.mrp) + '</span><span class="status st-OK">Save ' + o + '%</span>' : '') + '</div>' +
        '<div class="facts"><div><small>Brand</small><b>' + esc(p.brand || '-') + '</b></div><div><small>SKU</small><b class="mono">' + esc(p.sku) + '</b></div><div><small>Stock</small><b>' + esc(stockText) + '</b></div></div>' +
        '<p class="desc">' + esc(p.description) + '</p>' +
        '<div class="row"><div class="qty" aria-label="Quantity"><button type="button" data-q="-1" aria-label="Less">' + ic('minus', 'sm') + '</button><span id="qtyVal">1</span><button type="button" data-q="1" aria-label="More">' + ic('plus', 'sm') + '</button></div>' +
        '<button class="btn primary" type="button" id="addBtn"' + (p.available === 0 ? ' disabled' : '') + '>' + ic('cart', 'sm') + 'Add to cart</button>' +
        '<button class="btn dark" type="button" id="buyBtn"' + (p.available === 0 ? ' disabled' : '') + '>Buy now</button></div>' +
        '<ul class="perks light"><li>' + ic('truck', 'sm') + (p.price >= 99900 ? 'Free delivery' : 'Free delivery on orders above &#8377;999') + '</li><li>' + ic('undo', 'sm') + 'Cancel before shipping for a full refund</li><li>' + ic('wallet', 'sm') + 'Pay with Bandar Pay</li></ul>' +
        (isAdmin() ? '<div class="composed">Built by ' + svc('api-gateway') + ' from ' + svc('catalog-service') + (d.stockKnown ? ' + ' + svc('inventory-service') : ' (inventory down)') + '</div>' : '') +
        '</div></div>' +
        (d.related.length ? '<h2 class="related-h">' + 'More in ' + esc(p.category) + '</h2><div class="labels">' + d.related.map(function (r) { r.available = r.available === undefined ? null : r.available; return labelCard(r); }).join('') + '</div>' : '');
      $$('.qty button', view).forEach(function (b) {
        b.addEventListener('click', function () { qty = Math.max(1, Math.min(10, qty + Number(b.getAttribute('data-q')))); $('#qtyVal').textContent = qty; });
      });
      $('#addBtn').addEventListener('click', function () { addToCart(p.id, qty); });
      $('#buyBtn').addEventListener('click', function () { addToCart(p.id, qty).then(function () { if (state.user) go('#/cart'); }); });
      // related cards come from the catalog only (no stock call) - hide their stamps
      $$('.labels .stamp', view).forEach(function (s) { s.remove(); });
    });
  };

  // ------------------------------------------------------------------ CART
  routes.cart = function () {
    needUser();
    return api('GET', '/api/cart').then(function (c) {
      updateCartCount(c.count || 0);
      if (!c.items.length) {
        view.innerHTML = '<div class="head"><div><span class="kicker">Your bag</span><h1>Your cart</h1></div></div>' +
          '<div class="empty">' + ic('cart') + '<h3>Your cart is empty</h3><p>Find something you like and add it here.</p><a class="btn primary" href="#/shop">Go shopping</a></div>';
        return;
      }
      var shipping = c.subtotal >= 99900 ? 0 : 4900;
      view.innerHTML = '<div class="head"><div><span class="kicker">Your bag</span><h1>Your cart</h1><p>' + c.count + ' item(s) ready to ship.</p></div></div>' +
        '<div class="split"><section class="manifest"><div class="manifest-head"><span>Items</span><span>' + c.count + ' item(s)</span></div>' +
        c.items.map(function (it) {
          return '<div class="line-item ' + catClass(it.category) + '"><span class="mini-box">' + pic(it) + '</span>' +
            '<div><div class="name"><a href="#/p/' + it.productId + '">' + esc(it.name) + '</a></div><div class="sub mono">' + esc(it.sku) + ' &middot; ' + money(it.price) + ' each</div>' +
            '<button type="button" class="btn small ghost" data-act="remove" data-id="' + it.productId + '">' + ic('x', 'sm') + 'Remove</button></div>' +
            '<div class="qty"><button type="button" data-act="qty" data-id="' + it.productId + '" data-q="' + (it.qty - 1) + '" aria-label="Less">' + ic('minus', 'sm') + '</button><span>' + it.qty + '</span>' +
            '<button type="button" data-act="qty" data-id="' + it.productId + '" data-q="' + (it.qty + 1) + '" aria-label="More"' + (it.qty >= 10 ? ' disabled' : '') + '>' + ic('plus', 'sm') + '</button></div>' +
            '<span class="line-total">' + money(it.lineTotal) + '</span></div>';
        }).join('') + '</section>' +
        '<aside class="card summary"><h2>Summary</h2><dl><dt>Items</dt><dd>' + money(c.subtotal) + '</dd><dt>Shipping</dt><dd>' + (shipping ? money(shipping) : 'Free') + '</dd>' +
        '<dt class="total">Total</dt><dd class="total">' + money(c.subtotal + shipping) + '</dd></dl>' +
        (c.pricesKnown === false ? '<p class="note">Prices cannot be shown right now. Please try again in a moment.</p>' : '') +
        '<a class="btn primary wide" href="#/checkout">Checkout ' + ic('truck', 'sm') + '</a>' +
        (shipping ? '<p class="note">Add ' + money(99900 - c.subtotal) + ' more for free delivery.</p>' : '<p class="note info">' + ic('truck', 'sm') + ' You get <b>free delivery</b> on this order.</p>') +
        '</aside></div>';
      $$('[data-act="qty"], [data-act="remove"]', view).forEach(function (b) {
        b.addEventListener('click', function () {
          var id = b.getAttribute('data-id');
          var p = b.getAttribute('data-act') === 'remove' ? api('DELETE', '/api/cart/items/' + id) : api('PUT', '/api/cart/items/' + id, { qty: Number(b.getAttribute('data-q')) });
          p.then(function () { render(); }).catch(function (e) { toast(e.message, true); });
        });
      });
    });
  };

  // ------------------------------------------------------------------ CHECKOUT
  routes.checkout = function () {
    needUser();
    var key = uid();
    return Promise.all([api('GET', '/api/cart'), api('GET', '/api/wallet').catch(function () { return null; })]).then(function (res) {
      var c = res[0], w = res[1];
      if (!c.items.length) { go('#/cart'); return; }
      var shipping = c.subtotal >= 99900 ? 0 : 4900, total = c.subtotal + shipping;
      var a = store('bandar-address') || {};
      var enough = w && w.balance >= total;
      view.innerHTML = '<div class="head"><div><span class="kicker">Secure checkout</span><h1>Checkout</h1><p>Check your address, then pay with your Bandar Pay wallet.</p></div></div>' +
        '<div class="split"><form class="card" id="coForm" novalidate><h2>Deliver to</h2><div class="form-grid">' +
        '<label class="field">Full name<input name="name" required maxlength="60" value="' + esc(a.name || state.user.name) + '"></label>' +
        '<label class="field">Mobile (10 digits)<input name="phone" required inputmode="numeric" maxlength="12" value="' + esc(a.phone || '') + '" placeholder="98765 43210"></label>' +
        '<label class="field full">Address<input name="line" required maxlength="120" value="' + esc(a.line || '') + '" placeholder="House, street, area"></label>' +
        '<label class="field">City<input name="city" required maxlength="40" value="' + esc(a.city || '') + '" placeholder="Ranchi"></label>' +
        '<label class="field">PIN code<input name="pin" required inputmode="numeric" maxlength="6" value="' + esc(a.pin || '') + '" placeholder="834001"></label>' +
        '</div><h2 class="mt">' + 'Payment</h2>' +
        '<div class="note ' + (enough ? 'info' : '') + '">' + ic('wallet', 'sm') + ' Bandar Pay wallet balance: <b>' + (w ? money(w.balance) : 'not available right now') + '</b>' +
        (w && !enough ? '<br>Your balance is not enough for this order. <a href="#/wallet">Add money to your wallet</a> first.' : '') + '</div>' +
        '<p class="err-text" id="coErr" role="alert"></p>' +
        '<button class="btn primary wide" type="submit" id="placeBtn">Place order &middot; ' + money(total) + '</button>' +
        '<p class="note">' + ic('check', 'sm') + ' Your payment is protected: even if you click twice, you are charged only once.</p>' +
        '</form><aside class="card summary"><h2>Order</h2>' +
        c.items.map(function (it) { return '<div class="row between"><span>' + esc(it.name) + ' &times; ' + it.qty + '</span><b class="mono">' + money(it.lineTotal) + '</b></div>'; }).join('') +
        '<dl class="mt"><dt>Items</dt><dd>' + money(c.subtotal) + '</dd><dt>Shipping</dt><dd>' + (shipping ? money(shipping) : 'Free') + '</dd><dt class="total">Total</dt><dd class="total">' + money(total) + '</dd></dl>' +
        '<ul class="perks light"><li>' + ic('truck', 'sm') + 'Delivery in 3-5 days</li><li>' + ic('undo', 'sm') + 'Cancel any time before it ships</li><li>' + ic('bell', 'sm') + 'Updates in your inbox</li></ul></aside></div>';
      $('#coForm').addEventListener('submit', function (e) {
        e.preventDefault();
        var f = formData($('#coForm'));
        store('bandar-address', f);
        var btn = $('#placeBtn');
        btn.disabled = true;
        btn.textContent = 'Placing your order...';
        $('#coErr').textContent = '';
        api('POST', '/api/orders', { idempotencyKey: key, address: f }).then(function (o) {
          refreshCart();
          setTimeout(function () { loadInbox(false); refreshCart(); }, 900);
          go('#/orders/' + o.id + '?new=1');
        }).catch(function (err) {
          $('#coErr').textContent = err.message;
          btn.disabled = false;
          btn.textContent = 'Place order · ' + money(total);
        });
      });
    });
  };

  // ------------------------------------------------------------------ ORDERS
  function orderIcons(o) {
    return o.items.slice(0, 4).map(function (it) { return '<span class="mini-box cat-books" title="' + esc(it.name) + '">' + pic(it) + '</span>'; }).join('');
  }
  routes.orders = function (parts, q) {
    needUser();
    if (parts[1]) return orderDetail(parts[1], q);
    return api('GET', '/api/orders').then(function (list) {
      view.innerHTML = '<div class="head"><div><span class="kicker">Your account</span><h1>My orders</h1><p>Track, view or cancel your orders.</p></div></div>' +
        (list.length ? '<div class="orders">' + list.map(function (o) {
          return '<a class="bol st-line-' + esc(o.status) + '" href="#/orders/' + o.id + '"><div><div class="no">' + esc(o.orderNo) + '</div><div class="when">' + when(o.createdAt) + ' &middot; ' + o.items.length + ' item(s)</div></div>' +
            '<div class="icons">' + orderIcons(o) + '</div>' + status(o.status) + '<span class="total">' + money(o.total) + '</span></a>';
        }).join('') + '</div>' : '<div class="empty">' + ic('truck') + '<h3>No orders yet</h3><a class="btn primary" href="#/shop">Start shopping</a></div>');
    });
  };

  var STEP_ICON = { OK: 'check', FAILED: 'x', COMPENSATED: 'undo', RETRYING: 'refresh' };
  function sagaHtml(o) {
    if (!o.saga || !o.saga.length) return '<p class="muted">No saga steps recorded.</p>';
    return '<div class="route">' + o.saga.map(function (s) {
      return '<div class="stop ' + esc(s.status) + '"><span class="node">' + ic(STEP_ICON[s.status] || 'clock', 'sm') + '</span>' +
        '<div class="what"><b>' + esc(s.step) + ' ' + status(s.status) + '</b><div class="note-line">' + esc(s.note) + '</div>' + svc(s.service) + '</div>' +
        '<span class="ms">' + s.ms + ' ms</span></div>';
    }).join('') + '</div>';
  }
  var FLOW = [['CONFIRMED', 'Order placed', 'check'], ['PACKED', 'Packed', 'box'], ['SHIPPED', 'Shipped', 'truck'], ['DELIVERED', 'Delivered', 'star']];
  /* What a customer sees: a simple delivery tracker. */
  function trackerHtml(o) {
    if (o.status === 'FAILED' || o.status === 'REJECTED' || o.status === 'PENDING') {
      return '<p class="note">' + ic('x', 'sm') + ' This order was not placed. ' + esc(o.cancelReason) + ' No money was taken.</p>';
    }
    if (o.status === 'CANCELLED') {
      return '<p class="note">' + ic('undo', 'sm') + ' This order was cancelled (' + esc(o.cancelReason) + '). ' + money(o.total) + ' was refunded to your Bandar Pay wallet.</p>';
    }
    var at = {};
    o.history.forEach(function (h) { at[h.status] = h.at; });
    var idx = FLOW.map(function (f) { return f[0]; }).indexOf(o.status);
    return '<ol class="track">' + FLOW.map(function (f, i) {
      return '<li class="' + (i <= idx ? 'done' : '') + (i === idx ? ' now' : '') + '"><span class="dot">' + ic(f[2], 'sm') + '</span><b>' + f[1] + '</b><small>' + (at[f[0]] ? when(at[f[0]]) : (i === idx + 1 ? 'Next' : '')) + '</small></li>';
    }).join('') + '</ol>' + (o.status !== 'DELIVERED' ? '<p class="muted">Expected delivery in 3-5 days from the order date.</p>' : '');
  }
  function orderDetail(id, q) {
    return api('GET', '/api/orders/' + encodeURIComponent(id)).then(function (o) {
      var ok = ['CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED'].indexOf(o.status) >= 0;
      var result = '';
      if (q.new) {
        result = ok ? '<div class="card result ok" id="result"><span class="kicker">Thank you!</span><h2>' + ic('check') + ' Your order is confirmed</h2><p>We have received your payment of ' + money(o.total) +
          '. The details are in your inbox, and we will tell you when it ships.</p></div>'
          : '<div class="card result bad" id="result"><span class="kicker">Sorry</span><h2>' + ic('x') + ' Your order could not be placed</h2><p>' + esc(o.cancelReason) +
          '</p><p>No money was taken from your wallet. Your cart is still saved, so you can try again.</p></div>';
      }
      var canCancel = o.status === 'CONFIRMED' || o.status === 'PACKED';
      view.innerHTML = '<p><a href="#/orders" class="btn small ghost">&larr; My orders</a></p>' +
        '<div class="head"><div><span class="kicker">' + when(o.createdAt) + '</span><h1>' + esc(o.orderNo) + '</h1></div><div class="row">' + status(o.status) +
        (o.traceId && isAdmin() ? '<a class="btn small" href="#/arch/traces/' + esc(o.traceId) + '">' + ic('route', 'sm') + 'Trace</a>' : '') + '</div></div>' +
        result +
        '<div class="two-col"><div class="stack-gap"><section class="card"><h2>Order status</h2>' + trackerHtml(o) + '</section>' +
        (isAdmin() ? '<section class="card"><h2>Checkout steps <span class="tag-admin">admin</span></h2><p class="muted">The saga run by order-service. Indented orange steps are compensations (undo steps).</p>' + sagaHtml(o) + '</section>' : '') +
        '<section class="manifest"><div class="manifest-head"><span>Items</span><span>' + o.items.length + '</span></div>' + o.items.map(function (it) {
          return '<div class="line-item"><span class="mini-box cat-books">' + pic(it) + '</span><div><div class="name"><a href="#/p/' + it.productId + '">' + esc(it.name) + '</a></div><div class="sub mono">' + esc(it.sku) + ' &middot; ' + money(it.price) + ' &times; ' + it.qty + '</div></div><span></span><span class="line-total">' + money(it.lineTotal) + '</span></div>';
        }).join('') + '</section></div>' +
        '<aside class="stack-gap"><section class="card summary"><h2>Bill</h2><dl><dt>Items</dt><dd>' + money(o.subtotal) + '</dd><dt>Shipping</dt><dd>' + (o.shipping ? money(o.shipping) : 'Free') + '</dd><dt class="total">Total</dt><dd class="total">' + money(o.total) + '</dd></dl>' +
        (canCancel ? '<button class="btn danger wide" type="button" id="cancelBtn">' + ic('undo', 'sm') + 'Cancel order</button>' : '') + '</section>' +
        '<section class="card"><h2>Ship to</h2><p class="addr">' + esc(o.address.name + '\n' + o.address.line + '\n' + o.address.city + ' ' + o.address.pin + '\nPhone ' + o.address.phone) + '</p></section>' +
        '<section class="card"><h2>History</h2><ul class="timeline">' + o.history.map(function (h) { return '<li>' + status(h.status) + '<span class="muted">' + when(h.at) + ' &middot; ' + esc(h.by) + '</span></li>'; }).join('') + '</ul></section></aside></div>';
      var cb = $('#cancelBtn');
      if (cb) cb.addEventListener('click', function () {
        modal('Cancel ' + o.orderNo + '?', '<p>You will get a full refund of <b>' + money(o.total) + '</b> to your Bandar Pay wallet straight away.</p>' +
          '<label class="field">Reason (optional)<input name="reason" maxlength="120" placeholder="Changed my mind"></label>', 'Cancel order', function (f) {
            return api('POST', '/api/orders/' + o.id + '/cancel', { reason: f.reason }).then(function () { toast('Order cancelled and refunded'); setTimeout(function () { loadInbox(false); }, 800); render(); });
          }, { danger: true });
      });
    });
  }

  // ------------------------------------------------------------------ WALLET
  routes.wallet = function () {
    needUser();
    return api('GET', '/api/wallet').then(function (w) {
      view.innerHTML = '<div class="head"><div><span class="kicker">Bandar Pay</span><h1>Wallet</h1><p>Pay for orders in one tap. Refunds come back here instantly.</p></div></div>' +
        '<div class="two-col"><div class="stack-gap"><div class="wallet-card"><span class="kicker">Bandar Pay balance</span><span class="bal">' + money(w.balance) + '</span>' +
        '<span class="mono">' + esc(state.user.name) + '</span></div>' +
        '<section class="card"><h2>Add money</h2><div class="row">' + [1000, 5000, 20000].map(function (a) { return '<button class="btn" type="button" data-top="' + a + '">+ ' + money(a * 100) + '</button>'; }).join('') +
        '</div><p class="note">This is demo money for the project - no real payment is made.</p></section></div>' +
        '<section class="card"><h2>History</h2>' + (w.history.length ? '<ul class="ledger">' + w.history.map(function (l) {
          return '<li><span class="mini-box cat-' + (l.amount > 0 ? 'home' : 'fashion') + '">' + ic(l.kind === 'REFUND' ? 'undo' : (l.amount > 0 ? 'plus' : 'cart'), 'sm') + '</span><div><b>' + esc(l.note) + '</b><div class="muted mono">' + esc(l.kind) + ' &middot; ' + when(l.at) + '</div></div>' +
            '<span class="amt ' + (l.amount > 0 ? 'plus' : '') + '">' + (l.amount > 0 ? '+' : '&minus;') + money(Math.abs(l.amount)) + '</span></li>';
        }).join('') + '</ul>' : '<p class="muted">Nothing yet.</p>') + '</section></div>';
      $$('[data-top]', view).forEach(function (b) {
        b.addEventListener('click', function () {
          api('POST', '/api/wallet/topup', { amount: Number(b.getAttribute('data-top')) }).then(function () { toast('Money added'); render(); }).catch(function (e) { toast(e.message, true); });
        });
      });
    });
  };

  // ------------------------------------------------------------------ LOGIN / REGISTER
  function authShell(formHtml) {
    return '<div class="auth-page"><section class="dock"><span class="kicker">Welcome to Bandar Bazaar</span><h1>Big brands. <em>Small prices.</em></h1>' +
      '<p>Sign in to track your orders, pay with Bandar Pay and get updates in your inbox.</p>' +
      '<div class="dock-photos">' + ['elc-ph-02', 'fsh-sh-09', 'hom-lp-15', 'bok-bk-23', 'spt-bd-27', 'bty-pf-28'].map(function (sku) {
        return '<span class="ph">' + pic({ image: tileImg(sku), icon: 'box', name: '' }) + '</span>';
      }).join('') + '</div></section><section class="card auth-card">' + formHtml + '</section></div>';
  }
  routes.login = function (parts, q) {
    if (state.user) { go(q.next || '#/shop'); return; }
    view.innerHTML = authShell('<h2>Sign in</h2><form id="loginForm" novalidate><label class="field">Username<input name="username" autocomplete="username" required></label>' +
      '<label class="field">Password<input name="password" type="password" autocomplete="current-password" required></label><p class="err-text" id="loginErr" role="alert"></p>' +
      '<button class="btn primary wide" type="submit">Sign in</button></form><p class="muted">New here? <a href="#/register">Create an account</a> - you get a &#8377;10,000 welcome bonus.</p>' +
      '<div class="demo"><b>Demo accounts - click to fill</b>' +
      [['admin', 'admin123', 'Store admin'], ['riya', 'shop123', 'Customer (&#8377;75,000 in wallet)'], ['arjun', 'shop123', 'Customer (&#8377;1,200 in wallet)']].map(function (d) {
        return '<button type="button" class="demo-row" data-demo="' + d[0] + ':' + d[1] + '"><span>' + d[0] + ' / ' + d[1] + '</span><i>' + d[2] + '</i></button>';
      }).join('') + '</div>');
    $$('[data-demo]', view).forEach(function (b) {
      b.addEventListener('click', function () { var p = b.getAttribute('data-demo').split(':'); $('[name=username]').value = p[0]; $('[name=password]').value = p[1]; $('#loginForm button[type=submit]').focus(); });
    });
    $('#loginForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var f = formData(e.target);
      api('POST', '/api/auth/login', f).then(function (d) { signedIn(d.user, q.next); }).catch(function (err) { $('#loginErr').textContent = err.message; });
    });
  };
  routes.register = function (parts, q) {
    view.innerHTML = authShell('<h2>Create account</h2><form id="regForm" novalidate>' +
      '<label class="field">Full name<input name="name" required maxlength="60"></label>' +
      '<label class="field">Username<input name="username" required maxlength="20" autocomplete="username"><small>3-20 small letters, digits, _ or .</small></label>' +
      '<label class="field">Email<input name="email" type="email" required maxlength="100"></label>' +
      '<label class="field">Password<input name="password" type="password" required autocomplete="new-password"><small>At least 6 characters</small></label>' +
      '<p class="err-text" id="regErr" role="alert"></p><button class="btn primary wide" type="submit">Create account</button></form>' +
      '<p class="note info">' + ic('wallet', 'sm') + ' New accounts get a <b>&#8377;10,000 welcome bonus</b> in their Bandar Pay wallet.</p>' +
      '<p class="muted">Have an account? <a href="#/login">Sign in</a></p>');
    $('#regForm').addEventListener('submit', function (e) {
      e.preventDefault();
      api('POST', '/api/auth/register', formData(e.target)).then(function (d) {
        signedIn(d.user, q.next);
        setTimeout(function () { loadInbox(false); }, 1200);
      }).catch(function (err) { $('#regErr').textContent = err.message; });
    });
  };
  function signedIn(user, next) {
    state.user = user;
    renderChrome();
    refreshCart();
    loadInbox(false);
    toast('Welcome, ' + user.name.split(' ')[0]);
    go(next && next.indexOf('#/login') < 0 ? next : (user.role === 'ADMIN' ? '#/admin' : '#/shop'));
  }

  // ------------------------------------------------------------------ ADMIN
  routes.admin = function (parts) {
    needAdmin();
    var page = parts[1] || 'overview';
    if (page === 'products') return adminProducts();
    if (page === 'stock') return adminStock();
    if (page === 'orders') return adminOrders();
    return adminOverview();
  };

  function adminOverview() {
    return api('GET', '/api/admin/overview').then(function (d) {
      var o = d.orders || {}, pay = d.payments || {};
      var low = d.lowStock || [];
      var max = 1;
      (o.daily || []).forEach(function (x) { max = Math.max(max, x.revenue); });
      var h = '<div class="head"><div><span class="kicker">Store dashboard</span><h1>Store overview</h1><p>Sales, orders and stock at a glance. Each box comes from its own service; if one is down, only that box goes blank.</p></div>' +
        '<div class="row">' + ['order-service', 'inventory-service', 'payment-service', 'catalog-service'].map(function (s) { return d.missing.indexOf(s) >= 0 ? '<span class="status st-DOWN">' + esc(s) + '</span>' : svc(s); }).join('') + '</div></div>';
      if (d.missing.length) h += '<div class="banner bad">' + ic('bolt') + '<span><b>' + esc(d.missing.join(', ')) + '</b> did not answer. The rest of this page still works (graceful degradation).</span></div><br>';
      h += '<div class="kpis">' +
        kpi('Sales today', d.orders ? money(o.revenueToday) : '-', 'confirmed orders', 'var(--orange)') +
        kpi('Last 7 days', d.orders ? money(o.revenue7) : '-', 'avg order ' + (d.orders ? money(o.avgOrder) : '-'), 'var(--teal)') +
        kpi('Last 30 days', d.orders ? money(o.revenue30) : '-', '', 'var(--c-electronics)') +
        kpi('Money in wallets taken', d.payments ? money(pay.captured) : '-', d.payments ? money(pay.refunded) + ' refunded &middot; ' + pay.declinedCount + ' declined' : '', 'var(--ok)') +
        kpi('Products on sale', d.productCount == null ? '-' : d.productCount, 'from catalog-service', 'var(--c-books)') +
        kpi('Low stock', d.lowStock ? low.length : '-', 'items at or below their alert level', 'var(--warn)') +
        '</div>';
      h += '<div class="grid-2"><section class="card"><h2>Sales, last 14 days</h2>' + (d.orders ? '<div class="bars">' + o.daily.map(function (x, i) {
        var dt = new Date(x.date + 'T00:00:00');
        return '<div class="bar' + (i === o.daily.length - 1 ? ' today' : '') + '" title="' + esc(x.date) + ': ' + money(x.revenue) + ' from ' + x.orders + ' orders"><i class="fill" data-h="' + Math.round(x.revenue * 100 / max) + '"></i><small>' + dt.getDate() + '</small></div>';
      }).join('') + '</div>' : '<p class="muted">order-service is down.</p>') + '</section>' +
        '<section class="card"><h2>Orders by status</h2>' + (d.orders ? '<ul class="toplist">' + Object.keys(o.byStatus).map(function (k) {
          return '<li>' + status(k) + '<span class="v">' + o.byStatus[k] + '</span></li>';
        }).join('') + '</ul>' + (o.pendingCompensations ? '<p class="note">' + o.pendingCompensations + ' undo step(s) waiting for a service to come back.</p>' : '') : '<p class="muted">order-service is down.</p>') + '</section>' +
        '<section class="card"><h2>Top products</h2>' + (d.orders && o.top.length ? '<ul class="toplist">' + o.top.map(function (t) {
          return '<li><span class="mini-box cat-fashion">' + pic(t) + '</span><span>' + esc(t.name) + '<br><span class="muted">' + t.qty + ' sold</span></span><span class="v">' + money(t.revenue) + '</span></li>';
        }).join('') + '</ul>' : '<p class="muted">No data.</p>') + '</section>' +
        '<section class="card"><h2>Low stock</h2>' + (d.lowStock ? (low.length ? '<ul class="toplist">' + low.map(function (s) {
          return '<li><span class="status st-' + (s.available <= 0 ? 'FAILED' : 'COMPENSATED') + '">' + s.available + ' left</span><span>' + esc(s.name) + '<br><span class="muted mono">' + esc(s.sku) + '</span></span><a class="v btn small" href="#/admin/stock">Restock</a></li>';
        }).join('') + '</ul>' : '<p class="muted">All good.</p>') : '<p class="muted">inventory-service is down.</p>') + '</section></div>';
      view.innerHTML = h;
    });
  }
  function kpi(label, value, sub, color) {
    return '<div class="kpi" data-kc="' + esc(color) + '"><small>' + esc(label) + '</small><b>' + value + '</b><span>' + sub + '</span></div>';
  }

  var CATEGORIES = ['Electronics', 'Fashion', 'Home & Kitchen', 'Books', 'Sports & Fitness', 'Beauty'];
  var ICONS = ['headphones', 'phone', 'watch', 'laptop', 'keyboard', 'speaker', 'camera', 'tshirt', 'shoe', 'backpack', 'sunglasses', 'cap', 'mug', 'lamp', 'plant', 'bottle', 'pot', 'book', 'yoga', 'dumbbell', 'ball', 'bat', 'perfume', 'jar', 'box'];
  function productForm(p) {
    p = p || {};
    function opt(list, cur) { return list.map(function (x) { return '<option' + (x === cur ? ' selected' : '') + '>' + esc(x) + '</option>'; }).join(''); }
    return '<div class="form-grid">' +
      '<label class="field full">Name<input name="name" required maxlength="80" value="' + esc(p.name || '') + '"></label>' +
      (p.id ? '' : '<label class="field">SKU<input name="sku" required maxlength="20" placeholder="ELC-XX-31"><small>Never changes later</small></label>') +
      '<label class="field">Brand<input name="brand" maxlength="40" value="' + esc(p.brand || '') + '"></label>' +
      '<label class="field">Category<select name="category">' + opt(CATEGORIES, p.category) + '</select></label>' +
      '<label class="field">Icon (if no photo)<select name="icon">' + opt(ICONS, p.icon || 'box') + '</select></label>' +
      '<label class="field full">Photo<input name="image" maxlength="500" value="' + esc(p.image || '') + '" placeholder="https://images.unsplash.com/photo-...?w=600"><small>A web address (https://...) or a file in frontend/img/products. Leave empty to show the icon.</small></label>' +
      '<label class="field">Price (&#8377;)<input name="price" type="number" min="1" step="1" required value="' + (p.price ? p.price / 100 : '') + '"></label>' +
      '<label class="field">MRP (&#8377;)<input name="mrp" type="number" min="1" step="1" value="' + (p.mrp ? p.mrp / 100 : '') + '"></label>' +
      (p.id ? '<label class="field"><span>On sale</span><select name="active"><option value="true"' + (p.active ? ' selected' : '') + '>Yes</option><option value="false"' + (!p.active ? ' selected' : '') + '>No (hidden)</option></select></label>'
        : '<label class="field">Starting stock<input name="initialStock" type="number" min="0" value="20"><small>Sent in the product.created event</small></label>') +
      '<label class="field full">Description<textarea name="description" maxlength="400">' + esc(p.description || '') + '</textarea></label></div>';
  }
  function adminProducts() {
    return api('GET', '/api/products?all=true').then(function (list) {
      view.innerHTML = '<div class="head"><div><span class="kicker">Catalog</span><h1>Products</h1><p>' + list.length + ' products. Adding one publishes <b>product.created</b>; inventory-service creates its stock record when it hears that event.</p></div>' +
        '<button class="btn primary" type="button" id="newProd">' + ic('plus', 'sm') + 'Add product</button></div>' +
        '<div class="tbl-wrap"><table class="tbl"><thead><tr><th>Product</th><th>Category</th><th class="num">Price</th><th class="num">MRP</th><th>Status</th><th></th></tr></thead><tbody>' +
        list.map(function (p) {
          return '<tr><td><div class="cellname"><span class="mini-box ' + catClass(p.category) + '">' + pic(p) + '</span><div><b>' + esc(p.name) + '</b><div class="muted mono">' + esc(p.sku) + ' &middot; ' + esc(p.brand) + '</div></div></div></td>' +
            '<td>' + esc(p.category) + '</td><td class="num">' + money(p.price) + '</td><td class="num">' + money(p.mrp) + '</td><td>' + status(p.active ? 'OK' : 'CANCELLED').replace('>OK<', '>On sale<').replace('>CANCELLED<', '>Hidden<') + '</td>' +
            '<td class="num"><button class="btn small" type="button" data-edit="' + p.id + '">Edit</button></td></tr>';
        }).join('') + '</tbody></table></div>';
      $('#newProd').addEventListener('click', function () {
        modal('Add product', productForm(null), 'Save product', function (f) {
          return api('POST', '/api/products', f).then(function (p) { toast(p.name + ' added. Watch the Events page for product.created.'); render(); });
        });
      });
      $$('[data-edit]', view).forEach(function (b) {
        b.addEventListener('click', function () {
          var p = list.filter(function (x) { return String(x.id) === b.getAttribute('data-edit'); })[0];
          modal('Edit ' + p.sku, productForm(p), 'Save', function (f) {
            f.active = f.active === 'true';
            return api('PUT', '/api/products/' + p.id, f).then(function () { toast('Saved'); render(); });
          });
        });
      });
    });
  }

  function adminStock() {
    return Promise.all([api('GET', '/api/stock'), api('GET', '/api/stock/movements')]).then(function (res) {
      var list = res[0], moves = res[1];
      list.sort(function (a, b) { return a.available - b.available; });
      view.innerHTML = '<div class="head"><div><span class="kicker">Warehouse</span><h1>Stock</h1><p>Available = on hand &minus; reserved. Reserved items are held for orders that are being placed right now.</p></div></div>' +
        '<div class="tbl-wrap"><table class="tbl"><thead><tr><th>Item</th><th class="num">On hand</th><th class="num">Reserved</th><th class="num">Available</th><th>Level</th><th class="num">Alert at</th><th></th></tr></thead><tbody>' +
        list.map(function (s) {
          var pct = Math.min(100, Math.round(s.available * 100 / Math.max(20, s.lowAt * 4)));
          return '<tr><td><b>' + esc(s.name) + '</b><div class="muted mono">' + esc(s.sku) + '</div></td><td class="num">' + s.onHand + '</td><td class="num">' + s.reserved + '</td><td class="num"><b>' + s.available + '</b></td>' +
            '<td><div class="meter ' + (s.available <= 0 ? 'out' : (s.low ? 'low' : '')) + '"><i data-w="' + pct + '"></i></div></td><td class="num">' + s.lowAt + '</td>' +
            '<td class="num"><button class="btn small" type="button" data-adj="' + esc(s.sku) + '">Adjust</button></td></tr>';
        }).join('') + '</tbody></table></div>' +
        '<section class="card mt" id="moves">' + '<h2>Recent stock movements</h2>' + (moves.length ? '<ul class="toplist">' + moves.slice(0, 25).map(function (m) {
          return '<li><span class="status st-' + (m.delta > 0 ? 'OK' : 'COMPENSATED') + '">' + (m.delta > 0 ? '+' : '') + m.delta + '</span><span><b class="mono">' + esc(m.sku) + '</b> ' + esc(m.reason) + '</span><span class="v muted">' + esc(m.by) + ' &middot; ' + when(m.at) + '</span></li>';
        }).join('') + '</ul>' : '<p class="muted">No movements yet.</p>') + '</section>';
      $$('[data-adj]', view).forEach(function (b) {
        b.addEventListener('click', function () {
          var s = list.filter(function (x) { return x.sku === b.getAttribute('data-adj'); })[0];
          modal('Adjust ' + s.sku, '<p><b>' + esc(s.name) + '</b> - on hand ' + s.onHand + ', reserved ' + s.reserved + '</p><div class="form-grid">' +
            '<label class="field">Change (+ add / &minus; remove)<input name="delta" type="number" required value="10"></label>' +
            '<label class="field">Alert when at or below<input name="lowAt" type="number" min="0" value="' + s.lowAt + '"></label>' +
            '<label class="field full">Reason<input name="reason" required maxlength="80" value="New shipment arrived"></label></div>', 'Save', function (f) {
              return api('POST', '/api/stock/' + encodeURIComponent(s.sku) + '/adjust', { delta: Number(f.delta), lowAt: Number(f.lowAt), reason: f.reason }).then(function () { toast('Stock updated'); render(); });
            });
        });
      });
    });
  }

  var NEXT = { CONFIRMED: 'PACKED', PACKED: 'SHIPPED', SHIPPED: 'DELIVERED' };
  function adminOrders() {
    var q = parseHash().q;
    var qs = [];
    if (q.status) qs.push('status=' + encodeURIComponent(q.status));
    if (q.q) qs.push('q=' + encodeURIComponent(q.q));
    return api('GET', '/api/orders/all' + (qs.length ? '?' + qs.join('&') : '')).then(function (list) {
      var statuses = ['', 'CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'FAILED', 'REJECTED'];
      view.innerHTML = '<div class="head"><div><span class="kicker">Fulfilment</span><h1>All orders</h1><p>Moving an order forward publishes <b>order.status</b>; the customer gets a message from notification-service.</p></div>' +
        '<form class="row" id="ordFilter"><select class="inline" name="status">' + statuses.map(function (s) { return '<option value="' + s + '"' + ((q.status || '') === s ? ' selected' : '') + '>' + (s || 'All statuses') + '</option>'; }).join('') + '</select>' +
        '<input class="inline-input" name="q" placeholder="Order no. or name" value="' + esc(q.q || '') + '"><button class="btn" type="submit">Filter</button></form></div>' +
        (list.length ? '<div class="tbl-wrap"><table class="tbl"><thead><tr><th>Order</th><th>Customer</th><th>Placed</th><th class="num">Items</th><th class="num">Total</th><th>Status</th><th></th></tr></thead><tbody>' +
          list.map(function (o) {
            return '<tr><td><a class="mono" href="#/orders/' + o.id + '"><b>' + esc(o.orderNo) + '</b></a></td><td>' + esc(o.userName) + '<div class="muted">' + esc(o.address.city) + '</div></td><td>' + when(o.createdAt) + '</td>' +
              '<td class="num">' + o.items.length + '</td><td class="num">' + money(o.total) + '</td><td>' + status(o.status) + '</td><td class="num"><div class="row end">' +
              (NEXT[o.status] ? '<button class="btn small" type="button" data-next="' + o.id + '" data-to="' + NEXT[o.status] + '">Mark ' + NEXT[o.status].toLowerCase() + '</button>' : '') +
              (o.status === 'CONFIRMED' || o.status === 'PACKED' ? '<button class="btn small danger" type="button" data-cancel="' + o.id + '">Cancel</button>' : '') + '</div></td></tr>';
          }).join('') + '</tbody></table></div>' : '<div class="empty"><h3>No orders match</h3></div>');
      $('#ordFilter').addEventListener('submit', function (e) {
        e.preventDefault();
        var f = formData(e.target), p = [];
        if (f.status) p.push('status=' + encodeURIComponent(f.status));
        if (f.q) p.push('q=' + encodeURIComponent(f.q));
        go('#/admin/orders' + (p.length ? '?' + p.join('&') : ''));
      });
      $$('[data-next]', view).forEach(function (b) {
        b.addEventListener('click', function () {
          b.disabled = true;
          api('PUT', '/api/orders/' + b.getAttribute('data-next') + '/status', { status: b.getAttribute('data-to') }).then(function () { toast('Order moved to ' + b.getAttribute('data-to')); render(); })
            .catch(function (e) { toast(e.message, true); b.disabled = false; });
        });
      });
      $$('[data-cancel]', view).forEach(function (b) {
        b.addEventListener('click', function () {
          modal('Cancel this order?', '<p>The customer gets a full refund and the items go back to stock.</p><label class="field">Reason<input name="reason" maxlength="120" value="Cancelled by the store"></label>', 'Cancel order', function (f) {
            return api('POST', '/api/orders/' + b.getAttribute('data-cancel') + '/cancel', f).then(function () { toast('Cancelled and refunded'); render(); });
          }, { danger: true });
        });
      });
    });
  }

  // KPI top colours (set from JS because inline styles are blocked by the CSP)
  new MutationObserver(function () {
    $$('[data-kc]', view).forEach(function (el) { el.style.borderTopColor = el.getAttribute('data-kc'); el.removeAttribute('data-kc'); });
  }).observe(view, { childList: true, subtree: true });

  // ------------------------------------------------------------------ start
  window.Shop = {
    api: api, esc: esc, ic: ic, money: money, when: when, ago: ago, svc: svc, status: status, go: go, toast: toast, modal: modal,
    banner: banner, routes: routes, state: state, setTimer: setTimer, applySizes: applySizes, render: render, $: $, $$: $$, view: view
  };

  themeIcon();
  api('GET', '/api/session').then(function (s) {
    state.user = s.user;
  }).catch(function () { /* gateway not reachable - pages will say so */ }).then(function () {
    renderChrome();
    refreshCart();
    loadInbox(false);
    // arch.js has registered its routes by now
    render();
  });
})();
