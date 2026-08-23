(function(){
  'use strict';
  var phone = document.getElementById('phone');
  var slides = Array.prototype.slice.call(document.querySelectorAll('.slide'));
  var progress = Array.prototype.slice.call(document.querySelectorAll('.progress i'));
  var nextButton = document.getElementById('next');
  var skipButton = document.getElementById('skip');
  var concept = document.body.dataset.concept || 'gravity';
  var current = 0;
  var busy = false;
  var cleanupFns = [];
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var M = window.Motion;
  var A = window.anime;
  var Matter = window.Matter;

  function logoSVG(){
    return '<svg class="logo-mark blink" viewBox="0 0 64 64" aria-hidden="true">' +
      '<path d="M18 30 24 15c2 5 6 6 8-3 2 9 6 8 8 3l6 15Z" fill="currentColor"/>' +
      '<rect x="14" y="30" width="36" height="4" rx="2" fill="currentColor"/>' +
      '<g class="lens"><circle cx="24" cy="42" r="7" fill="none" stroke="currentColor" stroke-width="3"/></g>' +
      '<g class="lens"><circle cx="40" cy="42" r="7" fill="none" stroke="currentColor" stroke-width="3"/></g>' +
      '<path d="M31 42h2" stroke="currentColor" stroke-width="3" stroke-linecap="round"/></svg>';
  }

  function addCleanup(fn){ cleanupFns.push(fn); return fn; }
  function clearEffects(){
    while(cleanupFns.length){
      try{ cleanupFns.pop()(); }catch(_e){}
    }
  }
  function later(fn, ms){ var id = setTimeout(fn, ms); addCleanup(function(){ clearTimeout(id); }); return id; }
  function rafLoop(fn){
    var id = 0, live = true, last = performance.now();
    function tick(now){
      if(!live) return;
      var dt = Math.min(.033, Math.max(.001,(now-last)/1000)); last = now;
      fn(dt, now); id = requestAnimationFrame(tick);
    }
    id = requestAnimationFrame(tick);
    addCleanup(function(){ live=false; cancelAnimationFrame(id); });
  }
  function motion(el, values, options){
    if(!M || !M.animate || reduced) return null;
    var c = M.animate(el, values, options || {});
    addCleanup(function(){ try{ c.stop(); }catch(_e){} });
    return c;
  }

  function transitionPreset(){
    var map = {
      gravity:{slide:{y:[-42,0],rotate:[-1.5,0],scale:[.98,1]},demo:{y:[68,0],rotate:[2.5,0]}},
      magnet:{slide:{scale:[.9,1],filter:['blur(12px)','blur(0px)']},demo:{rotate:[-5,0],scale:[.86,1]}},
      elastic:{slide:{y:[70,0],scaleY:[.88,1]},demo:{scale:[.82,1],borderRadius:['46px','30px']}},
      liquid:{slide:{opacity:[0,1],filter:['blur(18px)','blur(0px)']},demo:{scale:[1.08,1],rotate:[2,0]}},
      toss:{slide:{x:[120,0],rotate:[7,0]},demo:{x:[-80,0],rotate:[-5,0]}},
      domino:{slide:{x:[-50,0]},demo:{scaleX:[.72,1],transformOrigin:['0% 50%','0% 50%']}},
      pendulum:{slide:{rotate:[-3.5,0],transformOrigin:['50% 0%','50% 0%']},demo:{y:[30,0]}},
      kinetic:{slide:{y:[90,0],skewY:[7,0]},demo:{clipPath:['inset(100% 0 0 0)','inset(0% 0 0 0)']}},
      portal:{slide:{z:[-240,0],scale:[.72,1],filter:['blur(10px)','blur(0px)']},demo:{rotateX:[18,0],scale:[.76,1]}},
      constellation:{slide:{opacity:[0,1],scale:[1.04,1]},demo:{opacity:[0,1],filter:['brightness(2)','brightness(1)']}}
    };
    return map[concept] || map.gravity;
  }

  function animateEntrance(slide){
    if(reduced) return;
    var p = transitionPreset();
    motion(slide, Object.assign({opacity:[0,1]},p.slide), {type:'spring',stiffness:170,damping:22,mass:1});
    var copyBits = slide.querySelectorAll('.copy > *');
    if(copyBits.length) motion(copyBits,{opacity:[0,1],y:[18,0]}, {delay:M.stagger(.065),type:'spring',stiffness:230,damping:24});
    var demo = slide.querySelector('.demo');
    if(demo) motion(demo,Object.assign({opacity:[0,1]},p.demo),{delay:.06,type:'spring',stiffness:155,damping:20,mass:1.05});
    var items = slide.querySelectorAll('.result,.task-row,.answer-card,.mode');
    if(items.length) motion(items,{opacity:[0,1],y:[24,0],scale:[.97,1]}, {delay:M.stagger(.075,{startDelay:.16}),type:'spring',stiffness:210,damping:23});
  }

  function updateProgress(){
    progress.forEach(function(dot,i){
      dot.classList.toggle('done',i<current);
      dot.classList.toggle('active',i===current);
    });
    nextButton.innerHTML = current === slides.length-1
      ? 'Start browsing <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>'
      : 'Next <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>';
    nextButton.setAttribute('aria-label',current===slides.length-1?'Finish tutorial':'Next tutorial screen');
  }

  function typeText(el,text,speed){
    if(!el) return;
    if(reduced){el.textContent=text;return;}
    el.textContent=''; var index=0;
    var id=setInterval(function(){
      index++; el.textContent=text.slice(0,index);
      if(index>=text.length) clearInterval(id);
    },speed);
    addCleanup(function(){clearInterval(id);});
  }

  function runScreenSequence(index,slide){
    if(index===1) later(function(){typeText(slide.querySelector('.typed-query'),'private browser',70);},380);
    if(index===2){
      later(function(){typeText(slide.querySelector('.typed-agent'),'Find a laptop under ₦500k',42);},260);
      var rows=slide.querySelectorAll('.task-row');
      later(function(){if(rows[0])rows[0].classList.add('done');},1250);
      later(function(){if(rows[1])rows[1].classList.add('done');},1950);
    }
  }

  function startGravity(slide){
    if(!Matter || reduced) return;
    var demo=slide.querySelector('.demo'), logo=demo&&demo.querySelector('.logo-mark'), layer=demo&&demo.querySelector('.fx-layer');
    if(!demo||!logo||!layer) return;
    var rect=demo.getBoundingClientRect(), w=rect.width, h=rect.height;
    if(w<20||h<20)return;
    var Engine=Matter.Engine, Bodies=Matter.Bodies, Composite=Matter.Composite, Body=Matter.Body;
    var engine=Engine.create({gravity:{x:0,y:1.55,scale:.001}});
    var walls=[Bodies.rectangle(w/2,h+20,w+80,40,{isStatic:true}),Bodies.rectangle(-20,h/2,40,h+80,{isStatic:true}),Bodies.rectangle(w+20,h/2,40,h+80,{isStatic:true})];
    var logoBody=Bodies.rectangle(w/2,-50,92,82,{restitution:.46,friction:.45,frictionAir:.008,chamfer:{radius:18},angle:-.08});
    var tokenNames=['ADS BLOCKED','NO TRACKING','PRIVATE'];
    var tokenEls=[], tokenBodies=[];
    tokenNames.forEach(function(name,i){
      var el=document.createElement('span');el.className='physics-token';el.textContent=name;layer.appendChild(el);tokenEls.push(el);
      tokenBodies.push(Bodies.rectangle(w*.2+i*w*.3,-120-i*48,78,26,{restitution:.35,friction:.5,frictionAir:.012,chamfer:{radius:13},angle:(i-1)*.18}));
    });
    Composite.add(engine.world,walls.concat([logoBody]).concat(tokenBodies));
    logo.style.position='absolute';logo.style.left='0';logo.style.top='0';
    later(function(){Body.setVelocity(logoBody,{x:1.1,y:1});},100);
    rafLoop(function(){
      Engine.update(engine,1000/60);
      logo.style.transform='translate3d('+(logoBody.position.x-58)+'px,'+(logoBody.position.y-58)+'px,0) rotate('+logoBody.angle+'rad)';
      tokenBodies.forEach(function(b,i){tokenEls[i].style.transform='translate3d('+(b.position.x-39)+'px,'+(b.position.y-13)+'px,0) rotate('+b.angle+'rad)';});
    });
    addCleanup(function(){Matter.World.clear(engine.world,false);Engine.clear(engine);logo.removeAttribute('style');tokenEls.forEach(function(el){el.remove();});});
  }

  function startMagnet(slide){
    var demo=slide.querySelector('.demo'), layer=demo&&demo.querySelector('.fx-layer');if(!demo||!layer)return;
    var dots=[], state=[], count=18;
    for(var i=0;i<count;i++){
      var d=document.createElement('i');d.className='magnet-dot';layer.appendChild(d);dots.push(d);
      state.push({a:i/count*Math.PI*2,r:65+(i%4)*15,vr:0,spin:.34+(i%3)*.08,phase:i*.7});
    }
    var pointer={x:0,y:0};
    function onMove(e){var r=demo.getBoundingClientRect();pointer.x=(e.clientX-r.left-r.width/2)*.22;pointer.y=(e.clientY-r.top-r.height/2)*.22;}
    demo.addEventListener('pointermove',onMove);addCleanup(function(){demo.removeEventListener('pointermove',onMove);dots.forEach(function(d){d.remove();});});
    rafLoop(function(dt,now){
      var r=demo.getBoundingClientRect(),cx=r.width/2+pointer.x,cy=r.height/2+pointer.y;
      state.forEach(function(s,i){
        var target=68+(i%4)*17+Math.sin(now*.001+s.phase)*7;
        var force=(target-s.r)*11-s.vr*5.5;s.vr+=force*dt;s.r+=s.vr*dt;s.a+=s.spin*dt;
        var x=cx+Math.cos(s.a)*s.r,y=cy+Math.sin(s.a)*s.r*.62;
        dots[i].style.transform='translate3d('+(x-3.5)+'px,'+(y-3.5)+'px,0) scale('+(0.65+(i%3)*.2)+')';
        dots[i].style.opacity=String(.28+(i%5)*.12);
      });
    });
  }

  function startElastic(slide){
    var demo=slide.querySelector('.demo'),stage=demo&&demo.querySelector('.logo-stage');if(!stage)return;
    var band=document.createElement('div');band.className='elastic-band';stage.insertBefore(band,stage.firstChild);
    if(!reduced&&M){
      motion(band,{rotate:[-12,18,-7,0],scaleX:[.72,1.08,.96,1],scaleY:[1.2,.88,1.04,1]}, {duration:2.2,type:'spring',stiffness:85,damping:9,mass:1.25});
      var logo=stage.querySelector('.logo-mark');motion(logo,{y:[-110,12,-5,0],scaleY:[1.18,.78,1.05,1],scaleX:[.86,1.16,.97,1]}, {type:'spring',stiffness:135,damping:10,mass:1.15});
    }
    addCleanup(function(){band.remove();});
  }

  function startLiquid(slide){
    var demo=slide.querySelector('.demo'),stage=demo&&demo.querySelector('.logo-stage');if(!stage)return;
    var blob=document.createElement('div');blob.className='liquid-blob';stage.insertBefore(blob,stage.firstChild);
    if(A&&!reduced){
      var anim=A.animate(blob,{x:[-32,28],y:[18,-26],rotate:[-18,28],scale:[.92,1.12],borderRadius:['42% 58% 63% 37% / 44% 42% 58% 56%','62% 38% 43% 57% / 58% 65% 35% 42%'],ease:A.spring({stiffness:55,damping:8,mass:1.4}),alternate:true,loop:true});
      addCleanup(function(){try{anim.cancel();}catch(_e){try{anim.pause();}catch(_x){}}});
    }
    addCleanup(function(){blob.remove();});
  }

  function startToss(slide){
    var demo=slide.querySelector('.demo'),layer=demo&&demo.querySelector('.fx-layer');if(!demo||!layer)return;
    var labels=['PRIVATE','SEARCH','ASK'],cards=[];
    labels.forEach(function(label,i){
      var card=document.createElement('div');card.className='toss-card';card.textContent=label;layer.appendChild(card);
      var x=44+i*55,y=45+i*18,r=-13+i*12;card.dataset.x=x;card.dataset.y=y;card.dataset.r=r;cards.push(card);
      card.style.transform='translate3d('+x+'px,'+y+'px,0) rotate('+r+'deg)';
      if(M&&!reduced) motion(card,{x:[x+130,x],y:[y-50,y],rotate:[r+18,r]}, {delay:i*.08,type:'spring',stiffness:160,damping:18});
    });
    var active=null,last={x:0,y:0,t:0},vel={x:0,y:0},animating=false;
    function down(e){
      var card=e.target.closest('.toss-card');if(!card)return;active=card;active.setPointerCapture(e.pointerId);last={x:e.clientX,y:e.clientY,t:performance.now()};vel={x:0,y:0};animating=false;
    }
    function move(e){
      if(!active)return;var now=performance.now(),dt=Math.max(8,now-last.t),dx=e.clientX-last.x,dy=e.clientY-last.y;
      vel.x=dx/dt*16;vel.y=dy/dt*16;var x=+active.dataset.x+dx,y=+active.dataset.y+dy;active.dataset.x=x;active.dataset.y=y;active.style.transform='translate3d('+x+'px,'+y+'px,0) rotate('+(+active.dataset.r+vel.x*1.8)+'deg)';last={x:e.clientX,y:e.clientY,t:now};
    }
    function up(){if(!active)return;var card=active;active=null;animating=true;var x=+card.dataset.x,y=+card.dataset.y,r=+card.dataset.r;
      function coast(dt){if(!animating)return;var box=demo.getBoundingClientRect();vel.y+=.32;vel.x*=.965;vel.y*=.965;x+=vel.x*60*dt;y+=vel.y*60*dt;
        var minX=8,maxX=box.width-136,minY=8,maxY=box.height-176;
        if(x<minX){x=minX;vel.x=Math.abs(vel.x)*.62}if(x>maxX){x=maxX;vel.x=-Math.abs(vel.x)*.62}if(y<minY){y=minY;vel.y=Math.abs(vel.y)*.62}if(y>maxY){y=maxY;vel.y=-Math.abs(vel.y)*.58}
        card.dataset.x=x;card.dataset.y=y;card.style.transform='translate3d('+x+'px,'+y+'px,0) rotate('+(r+vel.x*1.6)+'deg)';
        if(Math.abs(vel.x)+Math.abs(vel.y)<.12)animating=false;
      }
      rafLoop(coast);
    }
    demo.addEventListener('pointerdown',down);demo.addEventListener('pointermove',move);demo.addEventListener('pointerup',up);demo.addEventListener('pointercancel',up);
    addCleanup(function(){animating=false;demo.removeEventListener('pointerdown',down);demo.removeEventListener('pointermove',move);demo.removeEventListener('pointerup',up);demo.removeEventListener('pointercancel',up);cards.forEach(function(c){c.remove();});});
  }

  function startDomino(slide){
    if(!Matter||reduced)return;var demo=slide.querySelector('.demo'),layer=demo&&demo.querySelector('.fx-layer');if(!demo||!layer)return;
    var w=demo.clientWidth,h=demo.clientHeight,engine=Matter.Engine.create({gravity:{x:0,y:1.3,scale:.001}}),els=[],bodies=[];
    var floor=Matter.Bodies.rectangle(w/2,h-25,w,20,{isStatic:true,friction:.8});
    for(var i=0;i<9;i++){
      var el=document.createElement('i');el.className='domino-piece';el.style.left='0';el.style.top='0';el.style.bottom='auto';layer.appendChild(el);els.push(el);
      bodies.push(Matter.Bodies.rectangle(35+i*(w-70)/8,h-70,18,72,{friction:.65,restitution:.05,frictionAir:.002,chamfer:{radius:2}}));
    }
    Matter.Composite.add(engine.world,[floor].concat(bodies));
    later(function(){Matter.Body.applyForce(bodies[0],{x:bodies[0].position.x,y:bodies[0].position.y-28},{x:.025,y:0});},500);
    rafLoop(function(){Matter.Engine.update(engine,1000/60);bodies.forEach(function(b,i){els[i].style.transform='translate3d('+(b.position.x-9)+'px,'+(b.position.y-36)+'px,0) rotate('+b.angle+'rad)';});});
    addCleanup(function(){Matter.World.clear(engine.world,false);Matter.Engine.clear(engine);els.forEach(function(e){e.remove();});});
  }

  function startPendulum(slide){
    if(!Matter||reduced)return;var demo=slide.querySelector('.demo'),stage=demo&&demo.querySelector('.logo-stage');if(!demo||!stage)return;
    var rig=document.createElement('div');rig.className='pendulum-rig';rig.innerHTML='<div class="pendulum-line"><div class="pendulum-bob">●</div></div>';stage.insertBefore(rig,stage.firstChild);
    var line=rig.querySelector('.pendulum-line'),w=demo.clientWidth,engine=Matter.Engine.create({gravity:{x:0,y:1,scale:.001}}),anchor={x:w/2,y:22};
    var bob=Matter.Bodies.circle(anchor.x-92,anchor.y+74,22,{density:.003,frictionAir:.012,restitution:.1});
    var rope=Matter.Constraint.create({pointA:anchor,bodyB:bob,length:126,stiffness:.98,damping:.025});Matter.Composite.add(engine.world,[bob,rope]);
    rafLoop(function(){Matter.Engine.update(engine,1000/60);var dx=bob.position.x-anchor.x,dy=bob.position.y-anchor.y,angle=Math.atan2(-dx,dy);line.style.top=anchor.y+'px';line.style.height=Math.sqrt(dx*dx+dy*dy)+'px';line.style.transform='rotate('+angle+'rad)';});
    addCleanup(function(){Matter.World.clear(engine.world,false);Matter.Engine.clear(engine);rig.remove();});
  }

  function startKinetic(slide){
    var demo=slide.querySelector('.demo'),layer=demo&&demo.querySelector('.fx-layer');if(!demo||!layer)return;
    var phrases=['BE NOBODY','SEARCH CLEAN','JUST ASK','STAY PRIVATE','START QUIET'];
    var word=document.createElement('div');word.className='kinetic-word';
    phrases[current].split('').forEach(function(ch){var s=document.createElement('span');s.className='kinetic-letter';s.innerHTML=ch===' '?'&nbsp;':ch;word.appendChild(s);});
    layer.appendChild(word);var letters=word.querySelectorAll('.kinetic-letter');
    if(A&&!reduced){var anim=A.animate(letters,{y:[90,0],rotate:[function(){return -18+Math.random()*36},0],scale:[.45,1],opacity:[0,1],delay:A.stagger(38,{from:'center'}),ease:A.spring({stiffness:115,damping:13,mass:1.1})});addCleanup(function(){try{anim.cancel();}catch(_e){}});}
    addCleanup(function(){word.remove();});
  }

  function startPortal(slide){
    var demo=slide.querySelector('.demo'),stage=demo&&demo.querySelector('.logo-stage');if(!demo||!stage)return;
    var frames=[],pointer={x:0,y:0},smooth={x:0,y:0};
    for(var i=0;i<6;i++){var f=document.createElement('i');f.className='portal-frame';f.style.setProperty('--size',(82+i*38)+'px');f.style.setProperty('--alpha',(62-i*7)+'%');stage.insertBefore(f,stage.firstChild);frames.push(f);}
    function move(e){var r=demo.getBoundingClientRect();pointer.x=(e.clientX-r.left-r.width/2)/r.width;pointer.y=(e.clientY-r.top-r.height/2)/r.height;}
    demo.addEventListener('pointermove',move);rafLoop(function(dt,now){smooth.x+=(pointer.x-smooth.x)*Math.min(1,dt*5);smooth.y+=(pointer.y-smooth.y)*Math.min(1,dt*5);frames.forEach(function(f,i){var z=-i*42+Math.sin(now*.0013+i)*9,scale=1+i*.025;f.style.transform='translate3d('+(smooth.x*(i+1)*10)+'px,'+(smooth.y*(i+1)*8)+'px,'+z+'px) rotateX('+(-smooth.y*18)+'deg) rotateY('+(smooth.x*18)+'deg) rotateZ('+(i*5+now*.002)+'deg) scale('+scale+')';});});
    addCleanup(function(){demo.removeEventListener('pointermove',move);frames.forEach(function(f){f.remove();});});
  }

  function startConstellation(slide){
    var demo=slide.querySelector('.demo');if(!demo||reduced)return;var canvas=document.createElement('canvas');canvas.className='constellation-canvas';demo.insertBefore(canvas,demo.firstChild);var ctx=canvas.getContext('2d'),pts=[],pointer={x:-999,y:-999};
    function resize(){var dpr=Math.min(2,devicePixelRatio||1);canvas.width=demo.clientWidth*dpr;canvas.height=demo.clientHeight*dpr;canvas.style.width=demo.clientWidth+'px';canvas.style.height=demo.clientHeight+'px';ctx.setTransform(dpr,0,0,dpr,0,0);if(!pts.length)for(var i=0;i<26;i++)pts.push({x:Math.random()*demo.clientWidth,y:Math.random()*demo.clientHeight,ox:0,oy:0,vx:(Math.random()-.5)*8,vy:(Math.random()-.5)*8});}
    function move(e){var r=demo.getBoundingClientRect();pointer.x=e.clientX-r.left;pointer.y=e.clientY-r.top;}function leave(){pointer.x=-999;pointer.y=-999;}resize();demo.addEventListener('pointermove',move);demo.addEventListener('pointerleave',leave);window.addEventListener('resize',resize);
    rafLoop(function(dt,now){var w=demo.clientWidth,h=demo.clientHeight;ctx.clearRect(0,0,w,h);pts.forEach(function(p,i){var dx=p.x-pointer.x,dy=p.y-pointer.y,d=Math.sqrt(dx*dx+dy*dy)||1;if(d<80){p.vx+=dx/d*(80-d)*dt*2;p.vy+=dy/d*(80-d)*dt*2}p.vx+=Math.sin(now*.0004+i)*dt*.7;p.vy+=Math.cos(now*.00045+i)*dt*.7;p.vx*=.992;p.vy*=.992;p.x+=p.vx*dt*7;p.y+=p.vy*dt*7;if(p.x<0||p.x>w)p.vx*=-1;if(p.y<0||p.y>h)p.vy*=-1;p.x=Math.max(0,Math.min(w,p.x));p.y=Math.max(0,Math.min(h,p.y));});
      ctx.lineWidth=.7;for(var i=0;i<pts.length;i++)for(var j=i+1;j<pts.length;j++){var dx=pts[i].x-pts[j].x,dy=pts[i].y-pts[j].y,d2=dx*dx+dy*dy;if(d2<5200){ctx.strokeStyle='rgba(105,216,255,'+(1-Math.sqrt(d2)/72)*.34+')';ctx.beginPath();ctx.moveTo(pts[i].x,pts[i].y);ctx.lineTo(pts[j].x,pts[j].y);ctx.stroke();}}ctx.fillStyle='#8de3ff';pts.forEach(function(p,i){ctx.globalAlpha=.35+(i%4)*.16;ctx.beginPath();ctx.arc(p.x,p.y,1.2+(i%3)*.45,0,Math.PI*2);ctx.fill();});ctx.globalAlpha=1;});
    addCleanup(function(){demo.removeEventListener('pointermove',move);demo.removeEventListener('pointerleave',leave);window.removeEventListener('resize',resize);canvas.remove();});
  }

  function startConceptFx(slide){
    // The hero demonstrates each concept's full physics system. Later screens
    // keep that motion language in their transitions without obscuring the UI.
    if(slide.dataset.screen!=='welcome') return;
    var starters={gravity:startGravity,magnet:startMagnet,elastic:startElastic,liquid:startLiquid,toss:startToss,domino:startDomino,pendulum:startPendulum,kinetic:startKinetic,portal:startPortal,constellation:startConstellation};
    if(starters[concept]) starters[concept](slide);
  }

  function activate(index){
    clearEffects();
    slides.forEach(function(s,i){s.classList.toggle('active',i===index);s.setAttribute('aria-hidden',i===index?'false':'true');});
    current=index;updateProgress();animateEntrance(slides[current]);runScreenSequence(current,slides[current]);startConceptFx(slides[current]);
  }

  function go(index){
    if(busy||index<0||index>=slides.length||index===current)return;
    busy=true;var old=slides[current];
    if(M&&!reduced){
      var direction=index>current?1:-1;var c=M.animate(old,{opacity:[1,0],x:[0,-24*direction],scale:[1,.985]},{duration:.18,ease:'easeIn'});
      c.finished.then(function(){activate(index);busy=false;}).catch(function(){activate(index);busy=false;});
    }else{activate(index);busy=false;}
  }

  function finish(){
    if(phone.classList.contains('complete'))return;
    clearEffects();phone.classList.add('complete');
    var home=document.getElementById('home');
    if(M&&!reduced){motion(home.querySelector('.home-hero'),{opacity:[0,1],y:[35,0],scale:[.92,1]},{type:'spring',stiffness:150,damping:18});motion(home.querySelectorAll('.home-title,.home-sub,.omnibox,.home-nav'),{opacity:[0,1],y:[24,0]},{delay:M.stagger(.08),type:'spring',stiffness:180,damping:21});}
  }

  nextButton.addEventListener('click',function(){if(current===slides.length-1)finish();else go(current+1);});
  skipButton.addEventListener('click',function(){go(slides.length-1);});
  document.addEventListener('keydown',function(e){if(e.key==='ArrowRight'){if(current===slides.length-1)finish();else go(current+1)}if(e.key==='ArrowLeft')go(current-1);});
  var touch=null;
  phone.addEventListener('pointerdown',function(e){if(e.target.closest('button,.toss-card'))return;touch={x:e.clientX,y:e.clientY,id:e.pointerId};});
  phone.addEventListener('pointerup',function(e){if(!touch||touch.id!==e.pointerId)return;var dx=e.clientX-touch.x,dy=e.clientY-touch.y;if(Math.abs(dx)>55&&Math.abs(dx)>Math.abs(dy)*1.4){if(dx<0&&current<slides.length-1)go(current+1);if(dx>0)go(current-1)}touch=null;});
  phone.addEventListener('pointercancel',function(){touch=null;});

  window.MrNobodyTutorial={go:go,finish:finish,get current(){return current;}};
  activate(0);
})();
