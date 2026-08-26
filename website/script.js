// Mr Nobody — Website Scripts
// Simple, referenceable naming: init + feature name

function initMenu() {
  const btn = document.querySelector('.menu-btn');
  const menu = document.querySelector('.mobilemenu');
  if (!btn || !menu) return;

  function toggle(open) {
    menu.classList.toggle('open', open);
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    const icon = btn.querySelector('i');
    if (icon) {
      icon.className = open ? 'fas fa-xmark' : 'fas fa-bars';
    }
  }

  btn.addEventListener('click', function (e) {
    e.stopPropagation();
    toggle(!menu.classList.contains('open'));
  });

  menu.querySelectorAll('a').forEach(function (link) {
    link.addEventListener('click', function () {
      toggle(false);
    });
  });

  window.addEventListener('resize', function () {
    if (window.innerWidth > 880) {
      toggle(false);
    }
  });
}

function initCopyButtons() {
  const buttons = document.querySelectorAll('.copy-btn');
  buttons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      const text = btn.getAttribute('data-copy');
      if (!text) return;

      navigator.clipboard.writeText(text).then(function () {
        const originalText = btn.innerHTML;
        btn.innerHTML = '<i class="fas fa-check"></i> COPIED';
        setTimeout(function () {
          btn.innerHTML = originalText;
        }, 2000);
      }).catch(function () {
        window.prompt('Copy checksum:', text);
      });
    });
  });
}

function initYear() {
  const el = document.getElementById('year');
  if (el) {
    el.textContent = new Date().getFullYear();
  }
}

document.addEventListener('DOMContentLoaded', function () {
  initMenu();
  initCopyButtons();
  initYear();
});
