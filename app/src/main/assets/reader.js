/* Recto pagination script (ES5, runs in book pages; book scripts are blocked by CSP). */
(function () {
  if (window.__R) return;
  var R = window.__R = {};
  var de = document.documentElement;
  var W = 1, H = 1, scroll = false, page = 0, count = 1, step = 1, ready = false;
  var m = [0, 0, 0, 0]; // top right bottom left (CSS px)
  var marked = -1, hit = null, scrollTimer = 0;
  /* Sticky reading anchor (character offset): kept across relayouts, reset by real navigation. */
  var anchorPos = -1, ignoreScroll = false;

  function body() { return document.body || de; }

  function layoutCss() {
    var cw = Math.max(40, W - m[1] - m[3]), ch = Math.max(40, H - m[0] - m[2]);
    if (scroll) {
      return 'html{overflow-x:hidden!important;overflow-y:auto!important;width:auto!important;height:auto!important;margin:0!important;padding:0!important}' +
        'body{margin:0!important;padding:' + m[0] + 'px ' + m[1] + 'px ' + m[2] + 'px ' + m[3] + 'px!important;box-sizing:border-box!important;' +
        'width:auto!important;height:auto!important;max-width:none!important;-webkit-columns:auto!important;columns:auto!important}' +
        'img,svg,video{max-width:100%!important;height:auto}';
    }
    return 'html{width:' + W + 'px!important;height:' + H + 'px!important;overflow:hidden!important;margin:0!important;padding:0!important;' +
      'max-width:none!important;max-height:none!important;min-height:0!important}' +
      'body{margin:' + m[0] + 'px ' + m[1] + 'px ' + m[2] + 'px ' + m[3] + 'px!important;padding:0!important;border:0!important;' +
      'width:' + cw + 'px!important;height:' + ch + 'px!important;max-width:none!important;max-height:none!important;' +
      'min-width:0!important;min-height:0!important;box-sizing:content-box!important;overflow:hidden!important;position:static!important;' +
      '-webkit-column-width:' + cw + 'px!important;column-width:' + cw + 'px!important;' +
      '-webkit-column-gap:' + (m[1] + m[3]) + 'px!important;column-gap:' + (m[1] + m[3]) + 'px!important;' +
      '-webkit-column-count:auto!important;column-count:auto!important;-webkit-column-fill:auto!important;column-fill:auto!important}' +
      'img,svg,video,canvas,iframe{max-width:' + cw + 'px!important;max-height:' + ch + 'px!important;object-fit:contain}' +
      'img{height:auto}img,svg,figure{-webkit-column-break-inside:avoid;break-inside:avoid}';
  }

  function apply() {
    var s = document.getElementById('recto-layout');
    if (!s) {
      s = document.createElement('style');
      s.id = 'recto-layout';
      (document.head || de).appendChild(s);
    }
    s.textContent = layoutCss();
  }

  /* Exact WebView size from the app (CSS px); innerWidth is unreliable once content overflows. */
  var fixedW = 0, fixedH = 0;
  function size() {
    var vv = window.visualViewport;
    W = fixedW || (vv && vv.width) || window.innerWidth || 1;
    H = fixedH || (vv && vv.height) || window.innerHeight || 1;
  }

  /* Paged mode scrolls the body (a clipped multi-column box), never the viewport. */
  function sx() { return body().scrollLeft; }

  function scrollMax() { return Math.max(0, Math.max(de.scrollHeight, body().scrollHeight) - H); }

  function measure() {
    if (scroll) {
      step = Math.max(1, Math.round(H * 0.92));
      count = Math.max(1, Math.ceil(scrollMax() / step) + 1);
      page = Math.min(count - 1, Math.round(window.pageYOffset / step));
      if (window.pageYOffset >= scrollMax() - 2) page = count - 1;
    } else {
      count = Math.max(1, Math.round((body().scrollWidth + m[1] + m[3]) / W));
      if (page > count - 1) page = count - 1;
    }
  }

  /* Position reports carry the reading anchor so the app can save it at any moment. */
  var reportTimer = 0;
  function report() {
    clearTimeout(reportTimer);
    reportTimer = setTimeout(function () { if (window.Recto) Recto.pos(page, count, R.textPos()); }, 0);
  }

  function ready2() { if (window.Recto) Recto.ready(page, count, R.textPos()); }

  /* Shows a page without touching the anchor (used by relayouts). */
  function show(p) {
    p = Math.max(0, Math.min(count - 1, p | 0));
    page = p;
    if (scroll) {
      ignoreScroll = true;
      window.scrollTo(0, p >= count - 1 ? scrollMax() : p * step);
    } else body().scrollLeft = p * W;
    report();
  }

  /* User navigation: the anchor becomes whatever is now at the top of the page. */
  function go(p) {
    show(p);
    anchorPos = -1;
  }

  function showRect(r) {
    if (scroll) {
      ignoreScroll = true;
      window.scrollTo(0, Math.max(0, r.top + window.pageYOffset - m[0]));
      measure();
      report();
    } else {
      var br = body().getBoundingClientRect();
      show(Math.floor((r.left - br.left + sx() + 1) / W));
    }
  }

  function rectOf(node, off) {
    var r = document.createRange();
    try {
      r.setStart(node, off);
      r.setEnd(node, Math.min(node.length, off + 1));
    } catch (e) { r.selectNode(node); }
    var rc = r.getBoundingClientRect();
    if (!rc.width && !rc.height && node.parentNode && node.parentNode.getBoundingClientRect) rc = node.parentNode.getBoundingClientRect();
    return rc;
  }

  function walker() { return document.createTreeWalker(body(), NodeFilter.SHOW_TEXT, null, false); }

  /** Reading position as a character offset (the sticky anchor). */
  R.textPos = function () {
    if (anchorPos < 0) anchorPos = firstVisible();
    return anchorPos;
  };

  /** Character offset of the first visible character. */
  function firstVisible() {
    var w = walker(), n, total = 0, range = document.createRange();
    while ((n = w.nextNode())) {
      var len = n.length;
      if (!/\S/.test(n.data)) { total += len; continue; }
      range.selectNodeContents(n);
      var rects = range.getClientRects(), after = false;
      for (var k = 0; k < rects.length; k++) {
        var rc = rects[k];
        if (scroll ? rc.bottom > m[0] * 0.5 : rc.right > m[3] + 1) { after = true; break; }
      }
      if (after) {
        var lo = 0, hi = len - 1;
        while (lo < hi) {
          var mid = (lo + hi) >> 1;
          range.setStart(n, mid);
          range.setEnd(n, mid + 1);
          var rr = range.getBoundingClientRect();
          if ((scroll ? rr.bottom > m[0] * 0.5 : rr.right > m[3] + 1) || (!rr.width && !rr.height)) hi = mid; else lo = mid + 1;
        }
        return total + lo;
      }
      total += len;
    }
    return total;
  }

  /** Shows the page containing a character offset, which becomes the anchor. */
  R.goText = function (pos) {
    var w = walker(), n, total = 0, found = false;
    while ((n = w.nextNode())) {
      if (total + n.length > pos) {
        showRect(rectOf(n, Math.max(0, pos - total)));
        found = true;
        break;
      }
      total += n.length;
    }
    if (!found) show(count - 1);
    anchorPos = pos;
  };

  R.goAnchor = function (id) {
    var el = document.getElementById(id) || document.getElementsByName(id)[0];
    if (!el) return go(0);
    showRect(el.getBoundingClientRect());
    anchorPos = -1;
  };

  R.goFrac = function (f) { measure(); go(Math.min(count - 1, Math.floor(f * count))); };

  R.go = function (p) { measure(); go(p); };

  function clearHit() {
    if (!hit || !hit.parentNode) { hit = null; return; }
    var p = hit.parentNode;
    while (hit.firstChild) p.insertBefore(hit.firstChild, hit);
    p.removeChild(hit);
    p.normalize();
    hit = null;
  }

  /** Highlights and shows the n-th (0-based) case-insensitive match. */
  R.find = function (q, n) {
    clearHit();
    var lq = q.toLowerCase(), k = 0, w = walker(), node;
    while ((node = w.nextNode())) {
      var t = node.data.toLowerCase(), i = t.indexOf(lq);
      while (i >= 0) {
        if (k === n) {
          var r = document.createRange();
          r.setStart(node, i);
          r.setEnd(node, i + q.length);
          var mk = document.createElement('mark');
          mk.style.cssText = 'background:#ffd54f!important;color:#000!important';
          try { r.surroundContents(mk); hit = mk; } catch (e) {}
          showRect((hit || r).getBoundingClientRect());
          anchorPos = -1;
          return 1;
        }
        k++;
        i = t.indexOf(lq, i + Math.max(1, lq.length));
      }
    }
    return 0;
  };

  R.next = function () {
    if (scroll) {
      var max = scrollMax();
      if (window.pageYOffset >= max - 2) return 0;
      ignoreScroll = true;
      window.scrollTo(0, Math.min(max, window.pageYOffset + step));
      anchorPos = -1;
      measure();
      report();
      return 1;
    }
    measure();
    if (page >= count - 1) return 0;
    go(page + 1);
    return 1;
  };

  R.prev = function () {
    if (scroll) {
      if (window.pageYOffset <= 2) return 0;
      ignoreScroll = true;
      window.scrollTo(0, Math.max(0, window.pageYOffset - step));
      anchorPos = -1;
      measure();
      report();
      return 1;
    }
    if (page <= 0) return 0;
    go(page - 1);
    return 1;
  };

  function setTheme(css) {
    var s = document.getElementById('recto-theme');
    if (s && css != null) s.textContent = css;
  }

  function opts(o) {
    if (!o) return;
    m = o.m;
    scroll = !!o.scroll;
    if (o.w) { fixedW = o.w; fixedH = o.h; }
  }

  /** The WebView was resized (rotation, split screen): re-layout keeping the position. */
  R.resize = function (w, h) {
    if (!ready || (w === fixedW && h === fixedH)) return;
    var tp = R.textPos();
    fixedW = w;
    fixedH = h;
    size();
    apply();
    measure();
    R.goText(tp);
    ready2();
  };

  /** Synchronous re-style (theme, margins, mode) keeping the reading position. */
  R.apply = function (css, o) {
    var tp = R.textPos();
    setTheme(css);
    opts(o);
    size();
    apply();
    measure();
    R.goText(tp);
    ready2();
  };

  R.mark = function () { marked = R.textPos(); return marked; };

  /** After a native text-zoom change: re-measure (twice, the zoom applies asynchronously). */
  R.restore = function (css, o) {
    setTheme(css);
    opts(o);
    var run = function () {
      size();
      apply();
      measure();
      if (marked >= 0) R.goText(marked); else show(page);
      ready2();
    };
    setTimeout(run, 40);
    setTimeout(function () { var c = count; measure(); if (c !== count) run(); }, 350);
  };

  function onClick(e) {
    var a = e.target;
    while (a && a.nodeType === 1 && !(String(a.tagName).toLowerCase() === 'a' && (a.getAttribute('href') || a.getAttribute('xlink:href')))) a = a.parentNode;
    if (a && a.nodeType === 1) {
      e.preventDefault();
      e.stopPropagation();
      var href = a.getAttribute('href') || a.getAttribute('xlink:href'), abs = href;
      try { abs = new URL(href, document.baseURI).href; } catch (x) {}
      if (window.Recto) Recto.link(abs);
      return;
    }
    var s = window.getSelection && window.getSelection();
    if (s && String(s).length) return;
    if (window.Recto) Recto.tap(e.clientX / W, e.clientY / H);
  }

  R.init = function (o) {
    opts(o);
    if (o.lang && !de.getAttribute('lang')) de.setAttribute('lang', o.lang);
    size();
    apply();
    document.addEventListener('click', onClick, true);
    window.addEventListener('scroll', function () {
      if (!scroll || !ready) return;
      if (ignoreScroll) ignoreScroll = false; else anchorPos = -1; // the reader scrolled by hand
      clearTimeout(scrollTimer);
      scrollTimer = setTimeout(function () { measure(); report(); }, 80);
    });
    var done = false;
    var fin = function () {
      if (done) return;
      done = true;
      measure();
      var t = o.target || {t: 'page', v: 0};
      if (t.t === 'last') go(count - 1);
      else if (t.t === 'text') R.goText(t.v);
      else if (t.t === 'anchor') R.goAnchor(t.v);
      else if (t.t === 'frac') go(Math.min(count - 1, Math.floor(t.v * count)));
      else if (t.t === 'find') { if (!R.find(t.v, t.n)) R.find(t.v, 0); }
      else go(t.v | 0);
      ready = true;
      ready2();
    };
    if (document.fonts && document.fonts.status !== 'loaded' && document.fonts.ready) {
      document.fonts.ready.then(fin);
      setTimeout(fin, 1500);
    } else fin();
  };
})();
