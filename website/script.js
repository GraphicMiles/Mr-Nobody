// Mr Nobody — Landing Page Script
// Simple, referenceable naming

function initIcons(){
  if(window.lucide){ lucide.createIcons(); }
}

function initMenu(){
  const btn = document.querySelector('.menu');
  const menu = document.querySelector('.mobilemenu');
  if(!btn || !menu) return;

  function set(open){
    menu.classList.toggle('open', open);
    btn.classList.toggle('open', open);
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  btn.addEventListener('click', (e)=>{
    e.stopPropagation();
    set(!menu.classList.contains('open'));
  });

  menu.querySelectorAll('a').forEach(a=>{
    a.addEventListener('click', ()=> set(false));
  });
}

function initYear(){
  const el = document.getElementById('year');
  if(el) el.textContent = new Date().getFullYear();
}

// Init
document.addEventListener('DOMContentLoaded', ()=>{
  initIcons();
  initMenu();
  initYear();
});
