/*
 * Amnesia Browser — движок подмены отпечатков.
 * Внедряется до выполнения любых скриптов страницы во все фреймы.
 * Конфигурация подставляется приложением вместо маркера CONFIG.
 */
(function () {
  'use strict';
  var C = /*__CONFIG__*/null;
  if (!C) return;

  // --- режим совместимости: на этих сайтах/фреймах ничего не трогаем ---
  // Антибот-проверки (Cloudflare Turnstile, hCaptcha, reCAPTCHA) ищут противоречия
  // в окружении. Там работает только то, что подменено «нативно» на уровне WebView
  // (User-Agent и Client Hints), поэтому всё согласовано и проверка проходит.
  function matchHost(host, list) {
    host = String(host || '').toLowerCase();
    for (var i = 0; i < (list || []).length; i++) {
      var h = String(list[i]).toLowerCase();
      if (h && (host === h || host.slice(-h.length - 1) === '.' + h)) return true;
    }
    return false;
  }
  try {
    var here = location.hostname;
    var top = here;
    try {
      var ao = location.ancestorOrigins;
      if (ao && ao.length) top = new URL(ao[ao.length - 1]).hostname;
    } catch (e) { /* ignore */ }
    var challengeFrame = matchHost(here, C.challengeHosts) ||
      (/(^|\.)google\.com$|(^|\.)recaptcha\.net$/.test(here) && location.pathname.indexOf('/recaptcha') === 0);
    if (C.compatAll || challengeFrame || matchHost(top, C.compatHosts)) return;
  } catch (e) { /* ignore */ }

  // защита от повторного внедрения нужна только в запасном режиме (старый WebView)
  if (C.fallback) {
    var MARK = Symbol.for(C.key || '__amn');
    try {
      if (window[MARK]) return;
      Object.defineProperty(window, MARK, { value: true, enumerable: false });
    } catch (e) { /* ignore */ }
  }

  var NOISE = C.noise || {};
  var SEED = C.seed | 0;
  var patched = new WeakSet();
  var masks = new WeakMap();

  // ---------- утилиты ----------
  function hash(a, b) {
    var h = (a ^ Math.imul(b | 0, 0x9E3779B1)) | 0;
    h = Math.imul(h ^ (h >>> 16), 0x85EBCA6B);
    h = Math.imul(h ^ (h >>> 13), 0xC2B2AE35);
    return (h ^ (h >>> 16)) >>> 0;
  }
  function strHash(s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return h >>> 0;
  }
  function safe(fn) { try { fn(); } catch (e) { /* section failed, continue */ } }

  // функция выглядит как нативная при вызове toString()
  function mask(fn, name) {
    masks.set(fn, 'function ' + name + '() { [native code] }');
    return fn;
  }

  function installToString(win) {
    var FP = win.Function.prototype;
    var orig = FP.toString;
    // метод без prototype — как у нативных функций
    var repl = ({ toString() { return masks.has(this) ? masks.get(this) : orig.call(this); } }).toString;
    mask(repl, 'toString');
    Object.defineProperty(FP, 'toString', { value: repl, writable: true, configurable: true, enumerable: false });
  }

  function defGetter(obj, prop, valueFn) {
    if (!obj) return;
    var d = Object.getOwnPropertyDescriptor(obj, prop);
    var holder = {
      get [prop]() { return valueFn.call(this); }
    };
    var getter = Object.getOwnPropertyDescriptor(holder, prop).get;
    mask(getter, 'get ' + prop);
    try {
      Object.defineProperty(obj, prop, {
        get: getter,
        set: d ? d.set : undefined,
        enumerable: d ? d.enumerable : true,
        configurable: true
      });
    } catch (e) { /* ignore */ }
  }

  function hook(obj, prop, makeImpl) {
    if (!obj) return;
    var orig = obj[prop];
    if (typeof orig !== 'function') return;
    var impl = makeImpl(orig);
    var f = ({
      [prop](...args) { return impl.apply(this, args); }
    })[prop];
    try { Object.defineProperty(f, 'length', { value: orig.length }); } catch (e) { /* ignore */ }
    mask(f, prop);
    var d = Object.getOwnPropertyDescriptor(obj, prop);
    Object.defineProperty(obj, prop, {
      value: f,
      writable: d ? d.writable !== false : true,
      enumerable: d ? !!d.enumerable : false,
      configurable: true
    });
  }

  // getter-ы прототипа отдают наши значения для «поддельных» объектов
  function overrideGetters(proto, store, names) {
    if (!proto) return;
    names.forEach(function (n) {
      var d = Object.getOwnPropertyDescriptor(proto, n);
      var og = d && d.get;
      defGetter(proto, n, function () {
        var s = store.get(this);
        if (s && n in s) return s[n];
        return og ? og.call(this) : undefined;
      });
    });
  }

  function noisePixels(data, w, h, x0, y0) {
    if (!data || w * h > 4000000) return;
    for (var y = 0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        var n = hash(SEED ^ Math.imul(x + x0, 73856093), Math.imul(y + y0, 19349663));
        if ((n & 63) !== 0) continue;
        var i = (y * w + x) * 4;
        if (data[i + 3] === 0) continue;
        var ch = (n >>> 6) % 3;
        data[i + ch] = data[i + ch] ^ 1;
      }
    }
  }

  // ---------- основной патч окна ----------
  function patch(win) {
    if (!win || patched.has(win)) return;
    try { if (!win.Navigator) return; } catch (e) { return; } // cross-origin
    patched.add(win);

    safe(function () { installToString(win); });

    var N = win.Navigator && win.Navigator.prototype;

    // --- navigator ---
    safe(function () {
      // userAgent/appVersion WebView уже отдаёт нативно (тот же, что в заголовке и в Workers)
      if (C.fallback && C.userAgent) {
        defGetter(N, 'userAgent', function () { return C.userAgent; });
        defGetter(N, 'appVersion', function () { return C.userAgent.replace(/^Mozilla\//, ''); });
      }
      // у Android-профиля платформа и так настоящая (Linux arm) — лишний раз не трогаем
      if (C.platform != null && C.os !== 'android') defGetter(N, 'platform', function () { return C.platform; });
      if (C.vendor != null) defGetter(N, 'vendor', function () { return C.vendor; });
      if (C.productSub != null) defGetter(N, 'productSub', function () { return C.productSub; });
      if (typeof C.oscpu === 'string') defGetter(N, 'oscpu', function () { return C.oscpu; });
      if (C.hardwareConcurrency) defGetter(N, 'hardwareConcurrency', function () { return C.hardwareConcurrency; });
      if (C.deviceMemory === null) { try { delete N.deviceMemory; } catch (e) { /* ignore */ } }
      else if (C.deviceMemory) defGetter(N, 'deviceMemory', function () { return C.deviceMemory; });
      if (C.maxTouchPoints != null) defGetter(N, 'maxTouchPoints', function () { return C.maxTouchPoints; });
      defGetter(N, 'webdriver', function () { return false; });
      if (C.pdfViewer != null) defGetter(N, 'pdfViewerEnabled', function () { return !!C.pdfViewer; });
      if (C.dnt !== undefined) defGetter(N, 'doNotTrack', function () { return C.dnt; });
    });

    safe(function () {
      if (!C.languages || !C.languages.length) return;
      var langs = win.Object.freeze(win.Array.from(C.languages));
      defGetter(N, 'languages', function () { return langs; });
      defGetter(N, 'language', function () { return langs[0]; });
    });

    // --- User-Agent Client Hints (navigator.userAgentData) ---
    safe(function () {
      if (C.uaData === null) { delete N.userAgentData; return; }
      // если WebView подменил Client Hints сам — JS уже показывает те же значения, что и заголовки
      if (C.nativeUaData) return;
      if (!C.uaData || !win.NavigatorUAData) return;
      var U = C.uaData;
      var P = win.NavigatorUAData.prototype;
      function mkList(list) {
        return win.Object.freeze(win.Array.from((list || []).map(function (b) {
          return win.Object.freeze(win.Object.assign(new win.Object(), { brand: b.brand, version: b.version }));
        })));
      }
      var brands = mkList(U.brands);
      var uad = win.Object.create(P);
      var store = new WeakMap();
      store.set(uad, { brands: brands, mobile: !!U.mobile, platform: U.platform });
      overrideGetters(P, store, ['brands', 'mobile', 'platform']);
      hook(P, 'getHighEntropyValues', function (orig) {
        return function (hints) {
          if (!store.has(this)) return orig.apply(this, arguments);
          var r = { brands: brands, mobile: !!U.mobile, platform: U.platform };
          var all = {
            architecture: U.architecture || '',
            bitness: U.bitness || '',
            model: U.model || '',
            platformVersion: U.platformVersion || '',
            uaFullVersion: U.uaFullVersion || '',
            fullVersionList: mkList(U.fullVersionList),
            wow64: !!U.wow64,
            formFactors: win.Array.from(U.mobile ? ['Mobile'] : ['Desktop'])
          };
          (hints || []).forEach(function (h) { if (h in all) r[h] = all[h]; });
          return win.Promise.resolve(win.Object.assign(new win.Object(), r));
        };
      });
      hook(P, 'toJSON', function (orig) {
        return function () {
          if (!store.has(this)) return orig.apply(this, arguments);
          return win.Object.assign(new win.Object(), { brands: brands, mobile: !!U.mobile, platform: U.platform });
        };
      });
      defGetter(N, 'userAgentData', function () { return uad; });
    });

    // --- экран ---
    safe(function () {
      var sc = C.screen;
      if (!sc) return;
      var S = win.Screen && win.Screen.prototype;
      if (C.mobile && !C.desktopMode) {
        // на настоящем телефоне screen.width == innerWidth (в портрете). Иначе — явная подмена.
        var rw = Object.getOwnPropertyDescriptor(S, 'width').get.call(win.screen);
        var rh = Object.getOwnPropertyDescriptor(S, 'height').get.call(win.screen);
        sc = { w: rw, h: Math.max(rh, sc.h > rh - 120 && sc.h < rh + 120 ? sc.h : rh), aw: rw, ah: 0, dpr: sc.dpr, cd: sc.cd };
        sc.ah = sc.h;
      }
      defGetter(S, 'width', function () { return sc.w; });
      defGetter(S, 'height', function () { return sc.h; });
      defGetter(S, 'availWidth', function () { return sc.aw; });
      defGetter(S, 'availHeight', function () { return sc.ah; });
      defGetter(S, 'availLeft', function () { return 0; });
      defGetter(S, 'availTop', function () { return 0; });
      defGetter(S, 'colorDepth', function () { return sc.cd || 24; });
      defGetter(S, 'pixelDepth', function () { return sc.cd || 24; });
      defGetter(win, 'devicePixelRatio', function () { return sc.dpr; });
      if (!C.mobile) {
        defGetter(win, 'outerWidth', function () { return sc.aw; });
        defGetter(win, 'outerHeight', function () { return sc.ah; });
        defGetter(win, 'screenX', function () { return 0; });
        defGetter(win, 'screenY', function () { return 0; });
        defGetter(win, 'screenLeft', function () { return 0; });
        defGetter(win, 'screenTop', function () { return 0; });
      }
      var SO = win.ScreenOrientation && win.ScreenOrientation.prototype;
      if (SO) {
        var portrait = sc.w < sc.h;
        defGetter(SO, 'type', function () { return portrait ? 'portrait-primary' : 'landscape-primary'; });
        defGetter(SO, 'angle', function () { return 0; });
      }

      // media queries по device-* / resolution
      function lenPx(v) {
        var n = parseFloat(v);
        if (/em\s*$/i.test(v)) return n * 16;
        return n;
      }
      function rewrite(q) {
        return q.replace(/\(\s*(-webkit-)?(min-|max-)?(-webkit-)?(device-width|device-height|device-aspect-ratio|device-pixel-ratio|resolution)\s*:\s*([^)]+)\)/gi,
          function (m, w1, mm, w2, feat, val) {
            var actual, want;
            feat = feat.toLowerCase();
            val = val.trim();
            if (feat === 'device-width') { actual = sc.w; want = lenPx(val); }
            else if (feat === 'device-height') { actual = sc.h; want = lenPx(val); }
            else if (feat === 'device-aspect-ratio') {
              var p = val.split('/');
              actual = sc.w / sc.h; want = parseFloat(p[0]) / (parseFloat(p[1]) || 1);
            } else if (feat === 'device-pixel-ratio') { actual = sc.dpr; want = parseFloat(val); }
            else {
              actual = sc.dpr; want = parseFloat(val);
              if (/dpi$/i.test(val)) want = want / 96;
              else if (/dpcm$/i.test(val)) want = want * 2.54 / 96;
            }
            if (isNaN(want)) return m;
            var ok;
            mm = (mm || '').toLowerCase();
            if (mm === 'min-') ok = actual >= want - 1e-6;
            else if (mm === 'max-') ok = actual <= want + 1e-6;
            else ok = Math.abs(actual - want) < 0.01;
            return ok ? '(min-width: 0px)' : '(max-width: 0.0001px)';
          });
      }
      hook(win, 'matchMedia', function (orig) {
        return function (q) { return orig.call(this, rewrite(String(q))); };
      });
    });

    // --- десктоп: убрать признаки сенсорного экрана ---
    safe(function () {
      if (C.maxTouchPoints !== 0) return;
      ['ontouchstart', 'ontouchend', 'ontouchmove', 'ontouchcancel'].forEach(function (p) {
        [win, win.Window && win.Window.prototype, win.Document && win.Document.prototype,
          win.HTMLElement && win.HTMLElement.prototype, win.SVGElement && win.SVGElement.prototype,
          win.Element && win.Element.prototype].forEach(function (o) {
          try { if (o && Object.prototype.hasOwnProperty.call(o, p)) delete o[p]; } catch (e) { /* ignore */ }
        });
      });
    });

    // --- часовой пояс ---
    safe(function () {
      var TZ = C.timezone;
      if (!TZ) return;
      var ODTF = win.Intl.DateTimeFormat;
      new ODTF('en-US', { timeZone: TZ }); // throws if zone is unknown -> skip section
      var ODate = win.Date;
      var DP = ODate.prototype;
      var getTime = DP.getTime;

      var DateTimeFormat = function DateTimeFormat(locales, options) {
        var o = options == null ? {} : Object(options);
        if (o.timeZone === undefined) { o = Object.assign({}, o); o.timeZone = TZ; }
        return new.target ? Reflect.construct(ODTF, [locales, o], new.target) : ODTF(locales, o);
      };
      DateTimeFormat.prototype = ODTF.prototype;
      Object.defineProperty(DateTimeFormat, 'length', { value: 0 });
      DateTimeFormat.supportedLocalesOf = ODTF.supportedLocalesOf;
      try { Object.defineProperty(ODTF.prototype, 'constructor', { value: DateTimeFormat, writable: true, configurable: true, enumerable: false }); } catch (e) { /* ignore */ }
      mask(DateTimeFormat, 'DateTimeFormat');
      Object.defineProperty(win.Intl, 'DateTimeFormat', { value: DateTimeFormat, writable: true, configurable: true, enumerable: false });

      var partsFmt = new ODTF('en-US', {
        timeZone: TZ, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric',
        hour: 'numeric', minute: 'numeric', second: 'numeric'
      });
      var nameFmt = new ODTF('en-US', { timeZone: TZ, timeZoneName: 'long' });
      function parts(t) {
        var v = {};
        partsFmt.formatToParts(t).forEach(function (x) { v[x.type] = x.value; });
        return {
          y: +v.year, mo: +v.month, d: +v.day, h: (+v.hour) % 24, mi: +v.minute, s: +v.second
        };
      }
      function offsetFor(t) {
        if (isNaN(t)) return NaN;
        var p = parts(t);
        var asUTC = Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s);
        var tt = Math.floor(t / 1000) * 1000;
        return Math.round((tt - asUTC) / 60000);
      }
      function tzName(t) {
        var n = '';
        nameFmt.formatToParts(t).forEach(function (x) { if (x.type === 'timeZoneName') n = x.value; });
        return n;
      }
      function pad(n, l) { n = String(n); while (n.length < (l || 2)) n = '0' + n; return n; }
      var DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
      var MONS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
      function datePart(p) {
        var wd = new ODate(Date.UTC(p.y, p.mo - 1, p.d)).getUTCDay();
        return DAYS[wd] + ' ' + MONS[p.mo - 1] + ' ' + pad(p.d) + ' ' + pad(p.y, 4);
      }
      function timePart(t, p) {
        var off = -offsetFor(t);
        var sign = off >= 0 ? '+' : '-';
        off = Math.abs(off);
        return pad(p.h) + ':' + pad(p.mi) + ':' + pad(p.s) + ' GMT' + sign + pad(Math.floor(off / 60)) + pad(off % 60) + ' (' + tzName(t) + ')';
      }

      hook(DP, 'getTimezoneOffset', function () {
        return function () { return offsetFor(getTime.call(this)); };
      });
      hook(DP, 'toString', function () {
        return function () {
          var t = getTime.call(this);
          if (isNaN(t)) return 'Invalid Date';
          var p = parts(t);
          return datePart(p) + ' ' + timePart(t, p);
        };
      });
      hook(DP, 'toDateString', function () {
        return function () {
          var t = getTime.call(this);
          return isNaN(t) ? 'Invalid Date' : datePart(parts(t));
        };
      });
      hook(DP, 'toTimeString', function () {
        return function () {
          var t = getTime.call(this);
          return isNaN(t) ? 'Invalid Date' : timePart(t, parts(t));
        };
      });
      ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (m) {
        hook(DP, m, function (orig) {
          return function (locales, options) {
            var o = options == null ? {} : Object(options);
            if (o.timeZone === undefined) { o = Object.assign({}, o); o.timeZone = TZ; }
            return orig.call(this, locales, o);
          };
        });
      });
      if (win.Temporal && win.Temporal.Now) {
        hook(win.Temporal.Now, 'timeZoneId', function () { return function () { return TZ; }; });
        ['zonedDateTimeISO', 'plainDateTimeISO', 'plainDateISO', 'plainTimeISO'].forEach(function (m) {
          hook(win.Temporal.Now, m, function (orig) {
            return function (tz) { return orig.call(this, tz === undefined ? TZ : tz); };
          });
        });
      }
    });

    // --- canvas ---
    safe(function () {
      if (!NOISE.canvas) return;
      var C2D = win.CanvasRenderingContext2D && win.CanvasRenderingContext2D.prototype;
      if (!C2D) return;
      var origGet = C2D.getImageData;
      function noisyClone(canvas) {
        var w = canvas.width, h = canvas.height;
        if (!w || !h || w * h > 4000000) return null;
        var doc = canvas.ownerDocument || win.document;
        var c = doc.createElement('canvas');
        c.width = w; c.height = h;
        var ctx = c.getContext('2d');
        ctx.drawImage(canvas, 0, 0);
        var img = origGet.call(ctx, 0, 0, w, h);
        noisePixels(img.data, w, h, 0, 0);
        ctx.putImageData(img, 0, 0);
        return c;
      }
      [C2D, win.OffscreenCanvasRenderingContext2D && win.OffscreenCanvasRenderingContext2D.prototype].forEach(function (P) {
        hook(P, 'getImageData', function (orig) {
          return function (sx, sy) {
            var img = orig.apply(this, arguments);
            try { noisePixels(img.data, img.width, img.height, sx | 0, sy | 0); } catch (e) { /* ignore */ }
            return img;
          };
        });
      });
      var HC = win.HTMLCanvasElement && win.HTMLCanvasElement.prototype;
      ['toDataURL', 'toBlob'].forEach(function (m) {
        hook(HC, m, function (orig) {
          return function () {
            var c = null;
            try { c = noisyClone(this); } catch (e) { c = null; }
            return orig.apply(c || this, arguments);
          };
        });
      });
    });

    // --- canvas: измерение текста (шрифтовой отпечаток) ---
    safe(function () {
      if (!NOISE.fonts) return;
      var C2D = win.CanvasRenderingContext2D && win.CanvasRenderingContext2D.prototype;
      hook(C2D, 'measureText', function (orig) {
        return function (text) {
          var m = orig.apply(this, arguments);
          try {
            var k = hash(SEED, strHash(String(text) + '|' + this.font));
            var f = 1 + ((k % 2000) / 2000 - 0.5) * 0.0006;
            var w = m.width * f;
            Object.defineProperty(m, 'width', { value: w, enumerable: true });
          } catch (e) { /* ignore */ }
          return m;
        };
      });
    });

    // --- WebGL ---
    safe(function () {
      [win.WebGLRenderingContext, win.WebGL2RenderingContext].forEach(function (K) {
        if (!K) return;
        var P = K.prototype;
        if (C.webgl) {
          hook(P, 'getParameter', function (orig) {
            return function (p) {
              if (p === 37445) return C.webgl.vendor;
              if (p === 37446) return C.webgl.renderer;
              return orig.apply(this, arguments);
            };
          });
        }
        if (NOISE.webgl) {
          hook(P, 'readPixels', function (orig) {
            return function (x, y, w, h, format, type, px) {
              var r = orig.apply(this, arguments);
              try {
                if (px && px.BYTES_PER_ELEMENT === 1 && px.length >= w * h * 4) noisePixels(px, w, h, x | 0, y | 0);
              } catch (e) { /* ignore */ }
              return r;
            };
          });
        }
      });
    });

    // --- Web Audio ---
    safe(function () {
      if (!NOISE.audio) return;
      var AB = win.AudioBuffer && win.AudioBuffer.prototype;
      var done = new WeakSet();
      function noiseArr(arr) {
        for (var i = 0; i < arr.length; i += 97) {
          var n = hash(SEED, i);
          arr[i] = arr[i] + ((n & 0xffff) / 0xffff - 0.5) * 2e-7;
        }
      }
      hook(AB, 'getChannelData', function (orig) {
        return function () {
          var d = orig.apply(this, arguments);
          if (!done.has(d)) { done.add(d); try { noiseArr(d); } catch (e) { /* ignore */ } }
          return d;
        };
      });
      hook(AB, 'copyFromChannel', function (orig) {
        return function (dest, ch) {
          try { this.getChannelData(ch); } catch (e) { /* ignore */ }
          return orig.apply(this, arguments);
        };
      });
      var AN = win.AnalyserNode && win.AnalyserNode.prototype;
      hook(AN, 'getFloatFrequencyData', function (orig) {
        return function (arr) {
          var r = orig.apply(this, arguments);
          try {
            for (var i = 0; i < arr.length; i += 7) arr[i] += ((hash(SEED, i + 31) & 0xffff) / 0xffff - 0.5) * 1e-4;
          } catch (e) { /* ignore */ }
          return r;
        };
      });
    });

    // --- ClientRects ---
    safe(function () {
      if (!NOISE.rects) return;
      var d1 = ((hash(SEED, 101) % 1000) / 1000 - 0.5) * 2e-4;
      var d2 = ((hash(SEED, 202) % 1000) / 1000 - 0.5) * 2e-4;
      function shift(r) { return new win.DOMRect(r.x + d1, r.y + d1, r.width + d2, r.height + d2); }
      [win.Element && win.Element.prototype, win.Range && win.Range.prototype].forEach(function (P) {
        hook(P, 'getBoundingClientRect', function (orig) {
          return function () { return shift(orig.apply(this, arguments)); };
        });
      });
    });

    // --- WebRTC (утечка локального IP) ---
    safe(function () {
      if (!C.blockWebRTC) return;
      ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCSessionDescription',
        'RTCIceCandidate', 'RTCRtpReceiver', 'RTCRtpSender', 'RTCRtpTransceiver', 'RTCCertificate'].forEach(function (k) {
        try { delete win[k]; } catch (e) { /* ignore */ }
        try { if (k in win) Object.defineProperty(win, k, { value: undefined, writable: true, configurable: true }); } catch (e) { /* ignore */ }
      });
    });

    // --- медиаустройства ---
    safe(function () {
      if (!C.hideMedia) return;
      var MD = win.MediaDevices && win.MediaDevices.prototype;
      var MDI = win.MediaDeviceInfo && win.MediaDeviceInfo.prototype;
      var store = new WeakMap();
      overrideGetters(MDI, store, ['deviceId', 'kind', 'label', 'groupId']);
      hook(MD, 'enumerateDevices', function () {
        return function () {
          var list = ['audioinput', 'videoinput', 'audiooutput'].map(function (kind) {
            var o = win.Object.create(MDI || win.Object.prototype);
            store.set(o, { deviceId: '', kind: kind, label: '', groupId: '' });
            return o;
          });
          return win.Promise.resolve(win.Array.from(list));
        };
      });
    });

    // --- голоса синтеза речи ---
    safe(function () {
      if (!C.blockVoices) return;
      var SS = win.SpeechSynthesis && win.SpeechSynthesis.prototype;
      hook(SS, 'getVoices', function () { return function () { return new win.Array(); }; });
    });

    // --- батарея ---
    safe(function () {
      if (!C.battery) return;
      var BM = win.BatteryManager && win.BatteryManager.prototype;
      if (!BM) return;
      var b = C.battery;
      defGetter(BM, 'charging', function () { return !!b.charging; });
      defGetter(BM, 'level', function () { return b.level; });
      defGetter(BM, 'chargingTime', function () { return b.charging ? (b.level >= 1 ? 0 : Infinity) : Infinity; });
      defGetter(BM, 'dischargingTime', function () { return b.charging ? Infinity : Infinity; });
    });

    // --- сеть ---
    safe(function () {
      if (C.connection === null) { delete N.connection; return; }
      if (!C.connection) return;
      var NI = win.NetworkInformation && win.NetworkInformation.prototype;
      var c = C.connection;
      defGetter(NI, 'effectiveType', function () { return c.effectiveType; });
      defGetter(NI, 'rtt', function () { return c.rtt; });
      defGetter(NI, 'downlink', function () { return c.downlink; });
      defGetter(NI, 'saveData', function () { return false; });
    });

    // --- плагины (десктопный Chrome показывает 5 PDF-плагинов) ---
    safe(function () {
      if (!C.pdfPlugins || !win.PluginArray || !win.Plugin || !win.MimeType || !win.MimeTypeArray) return;
      var store = new WeakMap();
      var mimes = [['application/pdf', 'pdf'], ['text/pdf', 'pdf']].map(function (m) {
        var o = win.Object.create(win.MimeType.prototype);
        store.set(o, { type: m[0], suffixes: m[1], description: 'Portable Document Format' });
        return o;
      });
      var names = ['PDF Viewer', 'Chrome PDF Viewer', 'Chromium PDF Viewer', 'Microsoft Edge PDF Viewer', 'WebKit built-in PDF'];
      var plugins = names.map(function (n) {
        var p = win.Object.create(win.Plugin.prototype);
        store.set(p, { name: n, filename: 'internal-pdf-viewer', description: 'Portable Document Format', length: mimes.length });
        mimes.forEach(function (m, i) { Object.defineProperty(p, i, { value: m, enumerable: true }); });
        return p;
      });
      mimes.forEach(function (m) { store.get(m).enabledPlugin = plugins[0]; });
      function mkArray(proto, items, key) {
        var a = win.Object.create(proto);
        store.set(a, { length: items.length });
        items.forEach(function (it, i) { Object.defineProperty(a, i, { value: it, enumerable: true }); });
        items.forEach(function (it) { Object.defineProperty(a, store.get(it)[key], { value: it, enumerable: false }); });
        return a;
      }
      var pa = mkArray(win.PluginArray.prototype, plugins, 'name');
      var ma = mkArray(win.MimeTypeArray.prototype, mimes, 'type');
      overrideGetters(win.MimeType.prototype, store, ['type', 'suffixes', 'description', 'enabledPlugin']);
      overrideGetters(win.Plugin.prototype, store, ['name', 'filename', 'description', 'length']);
      overrideGetters(win.PluginArray.prototype, store, ['length']);
      overrideGetters(win.MimeTypeArray.prototype, store, ['length']);
      [[win.PluginArray.prototype, plugins, 'name'], [win.MimeTypeArray.prototype, mimes, 'type'], [win.Plugin.prototype, mimes, 'type']].forEach(function (cfg) {
        var P = cfg[0], items = cfg[1], key = cfg[2];
        hook(P, 'item', function (orig) {
          return function (i) { return store.has(this) ? (items[i >>> 0] || null) : orig.apply(this, arguments); };
        });
        hook(P, 'namedItem', function (orig) {
          return function (n) {
            if (!store.has(this)) return orig.apply(this, arguments);
            for (var i = 0; i < items.length; i++) if (store.get(items[i])[key] === n) return items[i];
            return null;
          };
        });
      });
      defGetter(N, 'plugins', function () { return pa; });
      defGetter(N, 'mimeTypes', function () { return ma; });
    });

    // --- window.chrome у Chrome-подобных профилей ---
    safe(function () {
      if (!C.chromeObj || win.chrome) return;
      var loadTimes = mask(({ loadTimes() { return {}; } }).loadTimes, 'loadTimes');
      var csi = mask(({ csi() { return {}; } }).csi, 'csi');
      Object.defineProperty(win, 'chrome', {
        value: {
          app: {
            isInstalled: false,
            InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
            RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
          },
          runtime: {},
          loadTimes: loadTimes,
          csi: csi
        },
        writable: true, configurable: true, enumerable: true
      });
    });

    // --- iframe: патчим окна при доступе через contentWindow ---
    safe(function () {
      var IF = win.HTMLIFrameElement && win.HTMLIFrameElement.prototype;
      if (!IF) return;
      ['contentWindow', 'contentDocument'].forEach(function (p) {
        var d = Object.getOwnPropertyDescriptor(IF, p);
        if (!d || !d.get) return;
        var og = d.get;
        defGetter(IF, p, function () {
          var r = og.call(this);
          try {
            var w = p === 'contentWindow' ? r : (r && r.defaultView);
            if (w) patch(w);
          } catch (e) { /* ignore */ }
          return r;
        });
      });
    });
  }

  patch(window);

  // --- десктоп-режим: форсируем ширину viewport ---
  if (C.desktopMode && window.top === window) {
    var want = 'width=' + (C.viewportWidth || 1280);
    var fix = function () {
      try {
        var d = document;
        var ms = d.querySelectorAll('meta[name="viewport" i]');
        if (!ms.length) {
          if (!d.head) return;
          var m = d.createElement('meta');
          m.setAttribute('name', 'viewport');
          m.setAttribute('content', want);
          d.head.appendChild(m);
        } else {
          for (var i = 0; i < ms.length; i++) if (ms[i].getAttribute('content') !== want) ms[i].setAttribute('content', want);
        }
      } catch (e) { /* ignore */ }
    };
    try {
      var mo = new MutationObserver(fix);
      mo.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
      document.addEventListener('DOMContentLoaded', function () {
        fix();
        setTimeout(function () { mo.disconnect(); if (document.head) { mo.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] }); } }, 3000);
      });
    } catch (e) { /* ignore */ }
  }
})();
