// Shared title (card 287d55cf): everything sits after the "#" – the browser never sends it to this server. Read here only.
(function () {
  var values = {};
  location.hash.replace(/^#/, "").split("&").forEach(function (part) {
    var i = part.indexOf("=");
    if (i > 0) { try { values[part.slice(0, i)] = decodeURIComponent(part.slice(i + 1)); } catch (e) {} }
  });
  if (values.n) { document.getElementById("titel").textContent = "„" + values.n + "“"; document.title = values.n + " – LiDio"; }
  if (values.a) { document.getElementById("interpret").textContent = values.a + (values.al ? " · " + values.al : ""); }
  // Ubuntu (and Android without verified link) open lidio://t#… – the same details.
  document.getElementById("oeffnen").href = "lidio://t" + location.hash;
})();
