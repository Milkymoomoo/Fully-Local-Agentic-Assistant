// Theme: the saved choice (localStorage) or the system theme. Runs in <head> so there is no flash.
(function () {
  var KEY = "mm-theme";
  function system() { return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"; }
  function current() { return localStorage.getItem(KEY) || system(); }
  // <picture> elements carry a dark <source>; the toggle has to win over the system setting, so pick the file here.
  function swapImages(t) {
    document.querySelectorAll("picture").forEach(function (p) {
      var s = p.querySelector("source[data-dark], source[media*='prefers-color-scheme']"), img = p.querySelector("img");
      if (!s || !img) return;
      if (!s.dataset.dark) { s.dataset.dark = s.getAttribute("srcset"); img.dataset.light = img.getAttribute("src"); s.removeAttribute("media"); s.setAttribute("srcset", ""); s.media = "not all"; }
      img.src = t === "dark" ? s.dataset.dark : img.dataset.light;
    });
  }
  function apply(t) {
    document.documentElement.dataset.theme = t;
    var b = document.getElementById("theme-toggle");
    if (b) { b.setAttribute("aria-pressed", t === "dark"); b.title = t === "dark" ? "Switch to light mode" : "Switch to dark mode"; }
    swapImages(t);
  }
  window.mmTheme = {
    current: current, apply: function () { apply(current()); },
    toggle: function () { var t = current() === "dark" ? "light" : "dark"; localStorage.setItem(KEY, t); apply(t); },
    reset: function () { localStorage.removeItem(KEY); apply(current()); }
  };
  document.documentElement.dataset.theme = current();
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function () { if (!localStorage.getItem(KEY)) apply(current()); });
  document.addEventListener("DOMContentLoaded", function () { apply(current()); });
})();
