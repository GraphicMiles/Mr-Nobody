// Mr Nobody — Landing Page Script
// Simple, referenceable naming: init + feature name

function initIcons() {
  if (window.lucide) {
    lucide.createIcons();
  }
}

function initMenu() {
  const btn = document.querySelector('.menu');
  const menu = document.querySelector('.mobilemenu');
  if (!btn || !menu) return;

  function set(open) {
    menu.classList.toggle('open', open);
    btn.classList.toggle('open', open);
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  btn.addEventListener('click', function (e) {
    e.stopPropagation();
    set(!menu.classList.contains('open'));
  });

  menu.querySelectorAll('a').forEach(function (a) {
    a.addEventListener('click', function () {
      set(false);
    });
  });

  // close the menu when the screen grows past the mobile breakpoint
  window.addEventListener('resize', function () {
    if (window.innerWidth > 800) set(false);
  });
}

function initYear() {
  const el = document.getElementById('year');
  if (el) el.textContent = new Date().getFullYear();
}

// Init
document.addEventListener('DOMContentLoaded', function () {
  initIcons();
  initMenu();
  initYear();
});
