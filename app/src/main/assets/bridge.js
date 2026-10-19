/* Korea Master - jembatan aplikasi Android.
   Disisipkan otomatis di awal <head> setiap kali halaman dimuat, sebelum skrip halaman jalan.
   Karena disisipkan oleh aplikasi, HTML di Netlify tidak perlu diubah sama sekali. */
(function () {
  'use strict';
  if (window.__kmBridge) return;
  window.__kmBridge = true;

  var A = window.KMApp; // objek native dari aplikasi (lihat AppBridge.java)

  function define(name, value) {
    try {
      Object.defineProperty(window, name, { value: value, configurable: true, writable: true });
    } catch (e) {
      try { window[name] = value; } catch (e2) { /* abaikan */ }
    }
  }

  /* ---------- 1) Suara Korea (Web Speech API -> Android TextToSpeech) ---------- */
  if (A && typeof A.speak === 'function') {
    var voice = {
      name: 'Korean (Android TTS)',
      lang: 'ko-KR',
      default: true,
      localService: true,
      voiceURI: 'ko-KR'
    };

    var Utterance = function (text) {
      this.text = (text === undefined || text === null) ? '' : String(text);
      this.lang = '';
      this.voice = null;
      this.rate = 1;
      this.pitch = 1;
      this.volume = 1;
      this.onstart = null;
      this.onend = null;
      this.onerror = null;
    };

    var synth = {
      speaking: false,
      pending: false,
      paused: false,
      onvoiceschanged: null,
      speak: function (u) {
        try {
          var text = u && u.text ? String(u.text) : '';
          var lang = (u && (u.lang || (u.voice && u.voice.lang))) || 'ko-KR';
          var rate = Number(u && u.rate) || 1;
          var pitch = Number(u && u.pitch) || 1;
          if (text) A.speak(text, String(lang), rate, pitch);
          if (u && typeof u.onstart === 'function') {
            setTimeout(function () { try { u.onstart({}); } catch (e) { /* abaikan */ } }, 0);
          }
        } catch (e) {
          if (u && typeof u.onerror === 'function') {
            try { u.onerror({ error: 'synthesis-failed' }); } catch (e2) { /* abaikan */ }
          }
        }
      },
      cancel: function () { try { A.stopSpeak(); } catch (e) { /* abaikan */ } },
      pause: function () {},
      resume: function () {},
      getVoices: function () { return [voice]; },
      addEventListener: function () {},
      removeEventListener: function () {},
      dispatchEvent: function () { return true; }
    };

    define('SpeechSynthesisUtterance', Utterance);
    define('speechSynthesis', synth);
  }

  /* ---------- 2) Tombol "Export JSON": unduhan blob tidak jalan di WebView ---------- */
  if (A && typeof A.saveFile === 'function' && window.HTMLAnchorElement) {
    var sendBlob = function (href, name) {
      try {
        fetch(href)
          .then(function (r) { return r.blob(); })
          .then(function (b) {
            var fr = new FileReader();
            fr.onloadend = function () {
              try {
                var s = String(fr.result || '');
                var i = s.indexOf(',');
                A.saveFile(name || 'file', b.type || 'application/octet-stream', i >= 0 ? s.substring(i + 1) : '');
              } catch (e) { try { A.toast('Gagal menyiapkan file'); } catch (e2) { /* abaikan */ } }
            };
            fr.readAsDataURL(b);
          })
          .catch(function () { try { A.toast('Gagal menyiapkan file'); } catch (e) { /* abaikan */ } });
      } catch (e) { /* abaikan */ }
    };

    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try {
        var h = this.href || '';
        if (this.hasAttribute('download') && h.indexOf('blob:') === 0) {
          sendBlob(h, this.getAttribute('download') || 'file');
          return;
        }
      } catch (e) { /* lanjut ke perilaku biasa */ }
      return origClick.apply(this, arguments);
    };

    document.addEventListener('click', function (ev) {
      try {
        var t = ev.target;
        while (t && t.tagName !== 'A') t = t.parentNode;
        if (t && t.hasAttribute('download') && (t.href || '').indexOf('blob:') === 0) {
          ev.preventDefault();
          sendBlob(t.href, t.getAttribute('download') || 'file');
        }
      } catch (e) { /* abaikan */ }
    }, true);
  }

  /* ---------- 3) Login sekali saja ----------
     Setelah berhasil masuk, nama akun disimpan. Saat aplikasi dibuka lagi, langsung masuk.
     Baru diminta login lagi kalau pengguna menekan tombol "Keluar". */
  var SKEY = 'koreamaster_session';
  var pre = null;
  try {
    if (localStorage.getItem(SKEY)) {
      // sembunyikan layar login sementara supaya tidak berkedip
      pre = document.createElement('style');
      pre.textContent = '#loginScreen{visibility:hidden!important}';
      (document.head || document.documentElement).appendChild(pre);
    }
  } catch (e) { pre = null; }

  function dropPre() {
    try { if (pre && pre.parentNode) pre.parentNode.removeChild(pre); } catch (e) { /* abaikan */ }
    pre = null;
  }

  function watchSession(ls, nm) {
    try {
      new MutationObserver(function () {
        try {
          if (ls.classList.contains('hidden')) {
            var n = ((nm && nm.textContent) || '').trim();
            if (n && n !== '-') localStorage.setItem(SKEY, n);
          } else {
            localStorage.removeItem(SKEY); // pengguna menekan "Keluar"
          }
        } catch (e) { /* abaikan */ }
      }).observe(ls, { attributes: true, attributeFilter: ['class'] });
    } catch (e) { /* abaikan */ }
  }

  window.addEventListener('load', function () {
    try {
      var ls = document.getElementById('loginScreen');
      var nm = document.getElementById('accountName');
      if (!ls) { dropPre(); return; }
      var u = null;
      try { u = localStorage.getItem(SKEY); } catch (e) { u = null; }
      watchSession(ls, nm);
      if (u && typeof login === 'function' && typeof userKey === 'function' &&
          localStorage.getItem(userKey(u))) {
        login(u);
      } else if (u) {
        localStorage.removeItem(SKEY); // akun sudah tidak ada
      }
    } catch (e) { /* biarkan layar login tampil */ }
    dropPre();
  });
})();
