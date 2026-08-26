// Mr Nobody — Website Scripts
// Simple, referenceable naming: init + feature name

function initIcons() {
  if (window.lucide) {
    window.lucide.createIcons();
  }
}

function initMenu() {
  const toggle = document.querySelector('.menu-toggle');
  const menu = document.querySelector('.mobilemenu');
  if (!toggle || !menu) return;

  function setOpen(isOpen) {
    toggle.classList.toggle('open', isOpen);
    menu.classList.toggle('open', isOpen);
    toggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
  }

  toggle.addEventListener('click', function (e) {
    e.stopPropagation();
    setOpen(!menu.classList.contains('open'));
  });

  menu.querySelectorAll('a').forEach(function (link) {
    link.addEventListener('click', function () {
      setOpen(false);
    });
  });

  window.addEventListener('resize', function () {
    if (window.innerWidth > 880) {
      setOpen(false);
    }
  });
}

function initMockupTabs() {
  const tabs = document.querySelectorAll('.mock-tab-item');
  const panels = document.querySelectorAll('.mockup-panel');

  tabs.forEach(function (tab) {
    tab.addEventListener('click', function () {
      const targetId = tab.getAttribute('data-tab');
      if (!targetId) return;

      tabs.forEach(function (t) { t.classList.remove('active'); });
      panels.forEach(function (p) { p.classList.remove('active'); });

      tab.classList.add('active');
      const targetPanel = document.getElementById('panel-' + targetId);
      if (targetPanel) {
        targetPanel.classList.add('active');
      }
    });
  });
}

function initCopyButtons() {
  const buttons = document.querySelectorAll('.copy-btn');
  buttons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      const text = btn.getAttribute('data-copy');
      if (!text) return;

      navigator.clipboard.writeText(text).then(function () {
        const originalHtml = btn.innerHTML;
        btn.innerHTML = '<i data-lucide="check" width="12"></i> Copied';
        initIcons();
        setTimeout(function () {
          btn.innerHTML = originalHtml;
          initIcons();
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
  initIcons();
  initMenu();
  initMockupTabs();
  initCopyButtons();
  initYear();
});
