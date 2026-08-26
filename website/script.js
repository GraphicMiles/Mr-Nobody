// Mr Nobody — Website Scripts
// Naming: init + feature name, clean and maintainable

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

  window.addEventListener('resize', function () {
    if (window.innerWidth > 880) set(false);
  });
}

function initYear() {
  const el = document.getElementById('year');
  if (el) el.textContent = new Date().getFullYear();
}

function initInteractiveMockup() {
  const buttons = document.querySelectorAll('.mock-tab-btn');
  const panes = document.querySelectorAll('.mock-tab-pane');

  buttons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      const tabId = btn.getAttribute('data-tab');
      if (!tabId) return;

      buttons.forEach(function (b) {
        b.classList.remove('active');
      });
      panes.forEach(function (p) {
        p.classList.remove('active');
      });

      btn.classList.add('active');
      const targetPane = document.getElementById('pane-' + tabId);
      if (targetPane) {
        targetPane.classList.add('active');
      }
    });
  });
}

function initCopyChecksum() {
  const copyButtons = document.querySelectorAll('.copy-btn');
  copyButtons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      const textToCopy = btn.getAttribute('data-copy');
      if (!textToCopy) return;

      navigator.clipboard.writeText(textToCopy).then(function () {
        const originalHtml = btn.innerHTML;
        btn.innerHTML = '<i data-lucide="check" width="12"></i> Copied!';
        if (window.lucide) lucide.createIcons();

        setTimeout(function () {
          btn.innerHTML = originalHtml;
          if (window.lucide) lucide.createIcons();
        }, 2200);
      }).catch(function () {
        // Fallback prompt if clipboard API is restricted
        window.prompt('Copy checksum:', textToCopy);
      });
    });
  });
}

// Initialise everything when DOM is loaded
document.addEventListener('DOMContentLoaded', function () {
  initIcons();
  initMenu();
  initYear();
  initInteractiveMockup();
  initCopyChecksum();
});
