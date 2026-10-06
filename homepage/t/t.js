// Shared title (card 287d55cf) or playlist – a "Mixtape" (card c9b15c67): everything sits after the "#", the browser never sends
// it to this server. Read here only.
(function () {
  var values = {};
  location.hash.replace(/^#/, "").split("&").forEach(function (part) {
    var i = part.indexOf("=");
    if (i > 0) { try { values[part.slice(0, i)] = decodeURIComponent(part.slice(i + 1)); } catch (e) {} }
  });
  var titel = document.getElementById("titel"), interpret = document.getElementById("interpret");
  if (values.k === "p") {
    // A Mixtape: name and the titles, packed (deflate + base64url) – unpacked by the browser itself.
    document.getElementById("art").textContent = "Jemand hat dir ein Mixtape geschickt";
    titel.textContent = "„" + (values.n || "Mixtape") + "“"; document.title = (values.n || "Mixtape") + " – LiDio";
    try {
      var b64 = (values.l || "").replace(/-/g, "+").replace(/_/g, "/");
      while (b64.length % 4) b64 += "=";
      var bytes = Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); });
      var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate"));
      new Response(stream).text().then(function (text) {
        var items = JSON.parse(text), list = document.getElementById("liste");
        interpret.textContent = items.length + " Titel";
        items.forEach(function (e) { var li = document.createElement("li"); li.textContent = (e[0] ? e[0] + " – " : "") + e[1]; list.appendChild(li); });
        list.hidden = false;
      });
    } catch (e) {}
  } else {
    if (values.n) { titel.textContent = "„" + values.n + "“"; document.title = values.n + " – LiDio"; }
    if (values.a) { interpret.textContent = values.a + (values.al ? " · " + values.al : ""); }
  }
  // Ubuntu (and Android without verified link) open lidio://t#… – the same details.
  document.getElementById("oeffnen").href = "lidio://t" + location.hash;
})();
