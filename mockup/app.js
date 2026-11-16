/* NexusClone mockup - screen routing, theme toggle, small interactions */

(function () {
  "use strict";

  var screens = {
    clones: "s-clones",
    detail: "s-detail",
    apps: "s-apps",
    proxy: "s-proxy",
    diag: "s-diag",
    settings: "s-settings"
  };

  var tabFor = {
    clones: "clones",
    detail: "clones",
    apps: "apps",
    proxy: "clones",
    diag: "settings",
    settings: "settings"
  };

  var device = document.getElementById("device");
  var railBtns = Array.prototype.slice.call(document.querySelectorAll(".rail-btn"));
  var tabs = Array.prototype.slice.call(document.querySelectorAll(".tab"));

  function show(name) {
    if (!screens[name]) return;

    Object.keys(screens).forEach(function (key) {
      var el = document.getElementById(screens[key]);
      if (el) el.classList.toggle("on", key === name);
    });

    railBtns.forEach(function (b) {
      b.classList.toggle("on", b.dataset.go === name);
    });

    var activeTab = tabFor[name];
    tabs.forEach(function (t) {
      t.classList.toggle("tab-on", t.dataset.go === activeTab);
    });

    var body = document.querySelector("#" + screens[name] + " .body");
    if (body) body.scrollTop = 0;

    if (history.replaceState) history.replaceState(null, "", "#" + name);
  }

  document.addEventListener("click", function (e) {
    var target = e.target.closest("[data-go]");
    if (!target) return;
    e.preventDefault();
    show(target.dataset.go);
  });

  // segmented controls
  document.querySelectorAll(".segmented").forEach(function (group) {
    group.addEventListener("click", function (e) {
      var seg = e.target.closest(".seg");
      if (!seg) return;
      group.querySelectorAll(".seg").forEach(function (s) {
        s.classList.toggle("seg-on", s === seg);
      });
    });
  });

  // chips
  document.querySelectorAll(".chips").forEach(function (group) {
    group.addEventListener("click", function (e) {
      var chip = e.target.closest(".chip");
      if (!chip) return;
      group.querySelectorAll(".chip").forEach(function (c) {
        c.classList.toggle("chip-on", c === chip);
      });
    });
  });

  // switches
  document.querySelectorAll(".switch").forEach(function (sw) {
    sw.addEventListener("click", function () {
      var on = sw.classList.toggle("switch-on");
      sw.setAttribute("aria-checked", on ? "true" : "false");
    });
  });

  // theme
  var themeBtn = document.getElementById("themeBtn");
  var themeLabel = document.getElementById("themeLabel");

  function setTheme(mode) {
    document.documentElement.setAttribute("data-theme", mode);
    themeLabel.textContent = mode === "dark" ? "Dark" : "Light";
    themeBtn.querySelector("use").setAttribute("href", mode === "dark" ? "#i-moon" : "#i-sun");
    try { localStorage.setItem("nc-mock-theme", mode); } catch (err) {}
  }

  themeBtn.addEventListener("click", function () {
    setTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark");
  });

  var saved = null;
  try { saved = localStorage.getItem("nc-mock-theme"); } catch (err) {}
  setTheme(saved === "light" ? "light" : "dark");

  // deep link
  var initial = (location.hash || "").replace("#", "");
  show(screens[initial] ? initial : "clones");
})();
