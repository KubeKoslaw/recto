/* Recto pagination script (ES5, runs in book pages; book scripts are blocked by CSP). */
(function () {
  if (window.__R) return;
  var R = window.__R = {};
  var de = document.documentElement;
  var W = 1, H = 1, scroll = false, spread = false, page = 0, count = 1, step = 1, ready = false;
  var m = [0, 0, 0, 0]; // top right bottom left (CSS px)
  var marked = -1, hit = null, scrollTimer = 0;
  /* Sticky reading anchor (character offset): kept across relayouts, reset by real navigation. */
  var anchorPos = -1, ignoreScroll = false;

  function body() { return document.body || de; }

  /*
   * Paged mode: the body is a clipped multi-column box scrolled one screen (W) at a time. Two-page
   * mode (unfolded foldables, tablets) puts two columns on a screen; the gap between them is the
   * right + left margin, so the screen stride stays W and the gutter sits at the centre (the fold).
   */
  function layoutCss() {
    var cw = Math.max(40, W - m[1] - m[3]), ch = Math.max(40, H - m[0] - m[2]);
    var gap = m[1] + m[3], colW = spread ? Math.max(20, (cw - gap) / 2) : cw;
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
      (spread
        ? '-webkit-column-width:auto!important;column-width:auto!important;-webkit-column-count:2!important;column-count:2!important;'
        : '-webkit-column-width:' + cw + 'px!important;column-width:' + cw + 'px!important;-webkit-column-count:auto!important;column-count:auto!important;') +
      '-webkit-column-gap:' + gap + 'px!important;column-gap:' + gap + 'px!important;' +
      '-webkit-column-fill:auto!important;column-fill:auto!important}' +
      'img,svg,video,canvas,iframe{max-width:' + colW + 'px!important;max-height:' + ch + 'px!important;object-fit:contain}' +
      'img{height:auto}img,svg,figure{-webkit-column-break-inside:avoid;break-inside:avoid}' +
      '#recto-pad{-webkit-column-break-before:always;break-before:column;display:block;height:1px;margin:0;padding:0;border:0}';
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
    pad();
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

  /*
   * Two-page mode: an odd number of columns leaves the last screen half full, and the clipped body
   * cannot scroll that far. An empty column at the end evens it out (it has no text, so text
   * positions are unaffected).
   */
  function pad() {
    var p = document.getElementById('recto-pad');
    if (!spread || scroll) {
      if (p) p.parentNode.removeChild(p);
      return;
    }
    var cols = Math.round((body().scrollWidth + m[1] + m[3]) / (W / 2)) - (p ? 1 : 0);
    if (cols % 2 === 1 && !p) {
      p = document.createElement('div');
      p.id = 'recto-pad';
      body().appendChild(p);
    } else if (cols % 2 === 0 && p) p.parentNode.removeChild(p);
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

  /* Text nodes of the body with their character offsets; rebuilt only when the DOM changes
     (search highlights), so positions are found by binary search instead of a full walk. */
  var tcache = null;
  function texts() {
    if (tcache) return tcache;
    var w = walker(), n, nodes = [], starts = [], total = 0;
    while ((n = w.nextNode())) { nodes.push(n); starts.push(total); total += n.length; }
    tcache = {nodes: nodes, starts: starts, total: total, text: null};
    return tcache;
  }
  function textChanged() { tcache = null; }

  /** Index of the node containing character offset pos (-1 past the end). */
  function nodeAt(pos) {
    var t = texts(), lo = 0, hi = t.nodes.length - 1;
    if (hi < 0 || pos >= t.total) return -1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (t.starts[mid] <= pos) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  function isAfter(rc) { return scroll ? rc.bottom > m[0] * 0.5 : rc.right > m[3] + 1; }

  /** Reading position as a character offset (the sticky anchor). */
  R.textPos = function () {
    if (anchorPos < 0) anchorPos = firstVisible();
    return anchorPos;
  };

  /** Character offset of the first visible character. */
  function firstVisible() {
    var t = texts(), nodes = t.nodes, range = document.createRange(), memo = {};
    /* State of a node: 1 reaches the visible page, 0 before it, -1 no box (blank / hidden). */
    function state(i) {
      if (memo[i] !== undefined) return memo[i];
      var n = nodes[i], st = -1;
      if (/\S/.test(n.data)) {
        range.selectNodeContents(n);
        var rects = range.getClientRects();
        if (rects.length) {
          st = 0;
          for (var k = 0; k < rects.length; k++) if (isAfter(rects[k])) { st = 1; break; }
        }
      }
      return (memo[i] = st);
    }
    /* First node at or after i that has a box (nodes.length if none). */
    function known(i) { while (i < nodes.length && state(i) < 0) i++; return i; }
    // Text runs in reading order, so "reaches the page" flips from 0 to 1 once: binary search.
    var lo = 0, hi = nodes.length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1, j = known(mid);
      if (j >= nodes.length || state(j) === 1) hi = mid; else lo = j + 1;
    }
    var i = known(lo);
    if (i >= nodes.length) return t.total;
    var n = nodes[i], a = 0, b = n.length - 1;
    while (a < b) {
      var c = (a + b) >> 1;
      range.setStart(n, c);
      range.setEnd(n, c + 1);
      var rr = range.getBoundingClientRect();
      if (isAfter(rr) || (!rr.width && !rr.height)) b = c; else a = c + 1;
    }
    return t.starts[i] + a;
  }

  /** Shows the page containing a character offset, which becomes the anchor. */
  R.goText = function (pos) {
    var i = nodeAt(pos), t = texts();
    if (i >= 0) showRect(rectOf(t.nodes[i], Math.max(0, pos - t.starts[i])));
    else show(count - 1);
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
    if (!hit) return;
    var marks = hit;
    hit = null;
    for (var i = 0; i < marks.length; i++) {
      var mk = marks[i], p = mk.parentNode;
      if (!p) continue;
      while (mk.firstChild) p.insertBefore(mk.firstChild, mk);
      p.removeChild(mk);
      p.normalize();
    }
    textChanged();
  }

  function squash(s) { return s.replace(/[\s\u200b\ufeff]+/g, ''); }

  /** Marks [start, start+len) of the body text, which may span several elements. */
  function highlight(start, len) {
    var t = texts(), end = start + len, marks = [], range = null;
    for (var i = Math.max(0, nodeAt(start)); i < t.nodes.length && t.starts[i] < end; i++) {
      var node = t.nodes[i], a = Math.max(0, start - t.starts[i]), b = Math.min(node.length, end - t.starts[i]);
      if (b <= a) continue;
      var r = document.createRange();
      r.setStart(node, a);
      r.setEnd(node, b);
      if (!range) range = r;
      var mk = document.createElement('mark');
      mk.style.cssText = 'background:#ffd54f!important;color:#000!important';
      try { r.surroundContents(mk); marks.push(mk); } catch (e) {}
    }
    textChanged();
    hit = marks;
    return marks.length ? marks[0].getBoundingClientRect() : range ? range.getBoundingClientRect() : null;
  }

  /**
   * Highlights and shows the n-th (0-based) case-insensitive match of the chapter text (matches
   * may cross tags). ctx: the text just before the match as the app saw it (whitespace removed);
   * if the n-th match does not follow it, the match that does is shown instead.
   */
  R.find = function (q, n, ctx) {
    clearHit();
    var t = texts(), lq = q.toLowerCase();
    if (!lq) return 0;
    if (t.text === null) {
      var parts = [];
      for (var k = 0; k < t.nodes.length; k++) parts.push(t.nodes[k].data);
      t.text = parts.join('');
    }
    var lower = t.text.toLowerCase(), hits = [], i = lower.indexOf(lq);
    while (i >= 0 && hits.length < 10000) {
      hits.push(i);
      i = lower.indexOf(lq, i + Math.max(1, lq.length));
    }
    var follows = function (p) {
      var pre = squash(lower.slice(Math.max(0, p - 200), p));
      if (pre.length >= ctx.length) return pre.slice(pre.length - ctx.length) === ctx;
      return p < 200 && ctx.slice(ctx.length - pre.length) === pre; // match near the chapter start
    };
    var pick = -1;
    if (ctx) {
      if (n < hits.length && follows(hits[n])) pick = n;
      else for (var h = 0; h < hits.length && pick < 0; h++) if (follows(hits[h])) pick = h;
    }
    if (pick < 0) {
      if (n >= hits.length) return 0;
      pick = n;
    }
    var rc = highlight(hits[pick], lq.length);
    if (rc) showRect(rc);
    anchorPos = -1;
    return 1;
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
    if (o.w) { fixedW = o.w; fixedH = o.h; spread = !!o.spread; }
  }

  /** The WebView was resized (rotation, split screen): re-layout keeping the position. */
  R.resize = function (w, h, sp) {
    sp = !!sp;
    if (!ready || (w === fixedW && h === fixedH && sp === spread)) return;
    var tp = R.textPos();
    fixedW = w;
    fixedH = h;
    spread = sp;
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
      else if (t.t === 'find') { if (!R.find(t.v, t.n, t.c)) R.find(t.v, 0); }
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
