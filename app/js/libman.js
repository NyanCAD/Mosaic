(function(){
'use strict';var fy=function(a){for(var b=Array(arguments.length),c=0;;)if(c<b.length)b[c]=arguments[c],c+=1;else return b},gy=function(a){a=a.destroy;return null==a?null:a.m?a.m():a.call(null)},hy=function(){var a=$APP.tj;return null==a?[]:$APP.Ej(fy,a,$APP.Yr,null,gy)},iy=function(a,b,c){for(var d=0,e=$APP.G(a);;)if(d<e){var f=$APP.Zd(d+e);var g=$APP.P.h(a,f);g=c.g?c.g(g):c.call(null,g);0>$APP.Fd(g,b)?d=f+1:e=f}else return d},jy=function(a,b,c){return $APP.Gf($APP.hf.l($APP.Nf(a,0,b),new $APP.T(null,
1,5,$APP.V,[c],null),$APP.N([$APP.Nf(a,b,$APP.G(a))])))},ky=function(a,b){return $APP.Gf($APP.hf.h($APP.Nf(a,0,b),$APP.Nf(a,b+1,$APP.G(a))))},ly=function(a,b){function c(g){return $APP.Nf(g,0,2)}a=$APP.Gf(a);var d=c(b),e=iy(a,d,c),f=$APP.P.h(a,e);return $APP.wd(f)&&$APP.H.h(d,c(f))?$APP.X.i(a,e,b):jy(a,e,b)},my=function(a,b){function c(f){return $APP.Nf(f,0,2)}a=$APP.Gf(a);b=c(b);var d=iy(a,b,c),e=$APP.P.h(a,d);$APP.Wh($APP.N([a,b,d,e]));return $APP.wd(e)&&$APP.H.h(b,c(e))?ky(a,d):a},ny=function(a,
b){function c(e){return $APP.Nf(e,0,2)}a=$APP.Gf(a);b=c(b);var d=iy(a,b,c);a=$APP.P.h(a,d);return $APP.wd(a)&&$APP.H.h(b,c(a))},oy=function(a){var b=hy(),c=0===b.length,d=c||$APP.La(b.hasOwnProperty(0))?b[0]=$APP.Tj.g(!1):b[0],e=c||$APP.La(b.hasOwnProperty(1))?b[1]=$APP.$e(null):b[1],f=c||$APP.La(b.hasOwnProperty(2))?b[2]=function(){return $APP.af(d,!0)}:b[2];return $APP.p(function(){var g=$APP.y(d);return $APP.p(g)?g:$APP.qd($APP.y(a))}())?new $APP.T(null,3,5,$APP.V,[$APP.vq,new $APP.n(null,1,[$APP.Fq,
function(g){console.log(g);g.preventDefault();$APP.af(a,g.target.elements.namefield.value);return $APP.af(d,!1)}],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,5,[$APP.Wp,$APP.Dq,$APP.bj,"namefield",$APP.Iq,!0,$APP.Qp,$APP.y(a),$APP.mr,function(g){$APP.af(a,g.target.value);return $APP.af(d,!1)}],null)],null)],null):new $APP.T(null,3,5,$APP.V,[$APP.$p,new $APP.n(null,1,[$APP.Bp,function(g){return $APP.H.h(g.detail,1)?$APP.af(e,window.setTimeout(f,1E3)):window.clearTimeout($APP.y(e))}],
null),$APP.y(a)],null)},uy=function(a){var b=$APP.P.h($APP.y(py),a);if($APP.p(b))var c=b;else{b=$APP.P.i($APP.y(qy),a,ry);c=$APP.bj.g(b);c=$APP.p(window.dburl)?(new URL(c,window.dburl)).href:c;var d=new $APP.fs(c);c=$APP.km(d,"models",$APP.al(sy,new $APP.T(null,1,5,$APP.V,[a],null)));$APP.im(d,$APP.N([c]));$APP.p(ty.g(b))&&$APP.fs.sync(ty.g(b),d);$APP.Y.u(py,$APP.X,a,c)}return c},Ey=function(){return new $APP.T(null,2,5,$APP.V,[vy,$APP.Ch(function(){return function c(b){return new $APP.le(null,function(){for(;;){var d=
$APP.D(b);if(d){var e=d;if($APP.xd(e)){var f=$APP.lc(e),g=$APP.G(f),h=$APP.pe(g);return function(){for(var t=0;;)if(t<g){var x=$APP.kd(f,t),w=$APP.O(x,0,null),v=$APP.O(x,1,null),z=uy(w);$APP.te(h,new $APP.T(null,4,5,$APP.V,[wy,new $APP.n(null,3,[$APP.ej,w,xy,$APP.H.h(w,$APP.y(yy)),zy,function(I,J,M,R){return function(ba){return $APP.p(ba.target.open)?$APP.af(yy,R):null}}(t,z,x,w,v,f,g,h,e,d)],null),new $APP.T(null,2,5,$APP.V,[Ay,$APP.bj.g(v)],null),new $APP.T(null,2,5,$APP.V,[By,new $APP.T(null,3,
5,$APP.V,[$APP.tt,Cy,function(){return function(I,J,M,R,ba,U,W,pa,Z,oa){return function ib(Ua){return new $APP.le(null,function(Fb,Pb){return function(){for(;;){var ic=$APP.D(Ua);if(ic){if($APP.xd(ic)){var cb=$APP.lc(ic),$b=$APP.G(cb),ac=$APP.pe($b);a:for(var id=0;;)if(id<$b){var Gb=$APP.kd(cb,id),jd=$APP.O(Gb,0,null);Gb=$APP.O(Gb,1,null);var Ke=$APP.fd(jd.split(":"));jd=new $APP.T(null,4,5,$APP.V,[$APP.P.i(Gb,$APP.bj,Ke),new $APP.T(null,2,5,$APP.V,[oy,$APP.al(Pb,new $APP.T(null,2,5,$APP.V,[jd,$APP.bj],
null))],null),jd,Ke],null);ac.add(jd);id+=1}else{cb=!0;break a}return cb?$APP.se($APP.ue(ac),ib($APP.mc(ic))):$APP.se($APP.ue(ac),null)}cb=$APP.E(ic);ac=$APP.O(cb,0,null);cb=$APP.O(cb,1,null);$b=$APP.fd(ac.split(":"));return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.P.i(cb,$APP.bj,$b),new $APP.T(null,2,5,$APP.V,[oy,$APP.al(Pb,new $APP.T(null,2,5,$APP.V,[ac,$APP.bj],null))],null),ac,$b],null),ib($APP.Jc(ic)))}return null}}}(I,J,M,R,ba,U,W,pa,Z,oa),null,null)}}(t,z,x,w,v,f,g,h,e,d)($APP.y(z))}()],null)],
null)],null));t+=1}else return!0}()?$APP.se($APP.ue(h),c($APP.mc(e))):$APP.se($APP.ue(h),null)}var l=$APP.E(e),m=$APP.O(l,0,null),q=$APP.O(l,1,null),r=uy(m);return $APP.Q(new $APP.T(null,4,5,$APP.V,[wy,new $APP.n(null,3,[$APP.ej,m,xy,$APP.H.h(m,$APP.y(yy)),zy,function(t,x,w){return function(v){return $APP.p(v.target.open)?$APP.af(yy,w):null}}(r,l,m,q,e,d)],null),new $APP.T(null,2,5,$APP.V,[Ay,$APP.bj.g(q)],null),new $APP.T(null,2,5,$APP.V,[By,new $APP.T(null,3,5,$APP.V,[$APP.tt,Cy,function(){return function(t){return function v(w){return new $APP.le(null,
function(){for(;;){var z=$APP.D(w);if(z){if($APP.xd(z)){var I=$APP.lc(z),J=$APP.G(I),M=$APP.pe(J);a:for(var R=0;;)if(R<J){var ba=$APP.kd(I,R),U=$APP.O(ba,0,null);ba=$APP.O(ba,1,null);var W=$APP.fd(U.split(":"));U=new $APP.T(null,4,5,$APP.V,[$APP.P.i(ba,$APP.bj,W),new $APP.T(null,2,5,$APP.V,[oy,$APP.al(t,new $APP.T(null,2,5,$APP.V,[U,$APP.bj],null))],null),U,W],null);M.add(U);R+=1}else{I=!0;break a}return I?$APP.se($APP.ue(M),v($APP.mc(z))):$APP.se($APP.ue(M),null)}I=$APP.E(z);M=$APP.O(I,0,null);I=
$APP.O(I,1,null);J=$APP.fd(M.split(":"));return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.P.i(I,$APP.bj,J),new $APP.T(null,2,5,$APP.V,[oy,$APP.al(t,new $APP.T(null,2,5,$APP.V,[M,$APP.bj],null))],null),M,J],null),v($APP.Jc(z)))}return null}},null,null)}}(r,l,m,q,e,d)($APP.y(r))}()],null)],null)],null),c($APP.Jc(e)))}return null}},null,null)}($APP.hf.h(new $APP.T(null,1,5,$APP.V,[new $APP.T(null,2,5,$APP.V,[Dy,ry],null)],null),$APP.y(qy)))}())],null)},Hy=function(a){var b=$APP.y(Cy),c=$APP.P.h($APP.y(a),
b);return new $APP.T(null,2,5,$APP.V,[Fy,new $APP.T(null,4,5,$APP.V,[$APP.tt,Gy,function(){return function f(e){return new $APP.le(null,function(){for(;;){var g=$APP.D(e);if(g){if($APP.xd(g)){var h=$APP.lc(g),l=$APP.G(h),m=$APP.pe(l);a:for(var q=0;;)if(q<l){var r=$APP.kd(h,q),t=$APP.O(r,0,null);r=$APP.O(r,1,null);t=new $APP.T(null,4,5,$APP.V,[$APP.P.i(r,$APP.bj,t),new $APP.T(null,2,5,$APP.V,[oy,$APP.al(a,new $APP.T(null,4,5,$APP.V,[b,$APP.Or,t,$APP.bj],null))],null),t,t],null);m.add(t);q+=1}else{h=
!0;break a}return h?$APP.se($APP.ue(m),f($APP.mc(g))):$APP.se($APP.ue(m),null)}h=$APP.E(g);m=$APP.O(h,0,null);h=$APP.O(h,1,null);return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.P.i(h,$APP.bj,m),new $APP.T(null,2,5,$APP.V,[oy,$APP.al(a,new $APP.T(null,4,5,$APP.V,[b,$APP.Or,m,$APP.bj],null))],null),m,m],null),f($APP.Jc(g)))}return null}},null,null)}($APP.Or.g(c))}(),function(d){$APP.Wh($APP.N([c,d]));return $APP.H.h($APP.mf(c,new $APP.T(null,3,5,$APP.V,[$APP.Or,d,$APP.Wp],null)),"schematic")?function(){var e=
window,f=e.open,g=$APP.fd(b.split(":")),h=$APP.uh(d);g=[$APP.u.g(g),"$",$APP.u.g(h)].join("");h=$APP.P.i($APP.y(qy),$APP.y(yy),ry);var l=new URL("editor",window.location);l.searchParams.append("schem",g);l.searchParams.append("db",$APP.bj.h(h,""));l.searchParams.append("sync",ty.h(h,""));return f.call(e,l,b)}:null}],null)],null)},Ky=function(){var a=$APP.y(yy),b=$APP.P.i($APP.y(qy),a,ry);return $APP.p(a)?new $APP.T(null,3,5,$APP.V,[Iy,new $APP.T(null,2,5,$APP.V,[Jy,"Library properties"],null),new $APP.T(null,
5,5,$APP.V,[$APP.xp,new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,1,[$APP.Sp,"dbname"],null),"Name"],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,3,[$APP.Vk,"dbname",$APP.jr,$APP.bj.g(b),$APP.pp,function(c){return $APP.Y.u(qy,$APP.Vo,new $APP.T(null,2,5,$APP.V,[a,$APP.bj],null),c.target.value)}],null)],null),new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,1,[$APP.Sp,"dburl"],null),"URL"],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,3,[$APP.Vk,"dburl",$APP.jr,ty.g(b),
$APP.pp,function(c){return $APP.Y.u(qy,$APP.Vo,new $APP.T(null,2,5,$APP.V,[a,ty],null),c.target.value)}],null)],null)],null)],null):null},Py=function(a){$APP.Wh($APP.N([$APP.y(a)]));var b=$APP.Ar.g($APP.y(a)),c=$APP.O(b,0,null),d=$APP.O(b,1,null),e=2+c,f=2+d;$APP.Wh($APP.N([e,f]));return new $APP.T(null,2,5,$APP.V,[Ly,new $APP.T(null,2,5,$APP.V,[My,$APP.Ch(function(){return function l(h){return new $APP.le(null,function(){for(;;){var m=$APP.D(h);if(m){var q=m;if($APP.xd(q)){var r=$APP.lc(q),t=$APP.G(r),
x=$APP.pe(t);return function(){for(var v=0;;)if(v<t){var z=$APP.kd(r,v);$APP.te(x,new $APP.T(null,3,5,$APP.V,[Ny,new $APP.n(null,1,[$APP.ej,z],null),$APP.Ch(function(){return function(I,J,M,R,ba,U,W,pa,Z,oa,ya,Ua){return function Pb(Fb){return new $APP.le(null,function(ic,cb,$b,ac,id,Gb,jd,Ke,Ng,eg,fg,Le){return function(){for(;;){var Td=$APP.D(Fb);if(Td){var Ud=Td;if($APP.xd(Ud)){var rf=$APP.lc(Ud),Si=$APP.G(rf),Og=$APP.pe(Si);return function(){for(var Pg=0;;)if(Pg<Si){var gg=$APP.kd(rf,Pg),Ti=function(Ui,
Vi,Eb,nz,oz,pz,qz,rz,it){return function(Vy){return $APP.p(Vy.target.checked)?$APP.Y.l(a,$APP.Wo,$APP.Vp,ly,$APP.N([new $APP.T(null,3,5,$APP.V,[Eb,it,"#"],null)])):$APP.Y.l(a,$APP.Wo,$APP.Vp,my,$APP.N([new $APP.T(null,3,5,$APP.V,[Eb,it,"#"],null)]))}}(Pg,ic,gg,rf,Si,Og,Ud,Td,cb,$b,ac,id,Gb,jd,Ke,Ng,eg,fg,Le);$APP.te(Og,new $APP.T(null,3,5,$APP.V,[Oy,new $APP.n(null,1,[$APP.ej,gg],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,3,[$APP.Wp,"checkbox",$APP.Sq,ny($APP.Vp.g($APP.y(a)),new $APP.T(null,
2,5,$APP.V,[gg,cb],null)),$APP.pp,Ti],null)],null)],null));Pg+=1}else return!0}()?$APP.se($APP.ue(Og),Pb($APP.mc(Ud))):$APP.se($APP.ue(Og),null)}var ne=$APP.E(Ud),Jk=function(Pg,gg,Ti,Ui,Vi){return function(Eb){return $APP.p(Eb.target.checked)?$APP.Y.l(a,$APP.Wo,$APP.Vp,ly,$APP.N([new $APP.T(null,3,5,$APP.V,[gg,Vi,"#"],null)])):$APP.Y.l(a,$APP.Wo,$APP.Vp,my,$APP.N([new $APP.T(null,3,5,$APP.V,[gg,Vi,"#"],null)]))}}(ic,ne,Ud,Td,cb,$b,ac,id,Gb,jd,Ke,Ng,eg,fg,Le);return $APP.Q(new $APP.T(null,3,5,$APP.V,
[Oy,new $APP.n(null,1,[$APP.ej,ne],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,3,[$APP.Wp,"checkbox",$APP.Sq,ny($APP.Vp.g($APP.y(a)),new $APP.T(null,2,5,$APP.V,[ne,cb],null)),$APP.pp,Jk],null)],null)],null),Pb($APP.Jc(Ud)))}return null}}}(I,J,M,R,ba,U,W,pa,Z,oa,ya,Ua),null,null)}}(v,z,r,t,x,q,m,b,c,d,e,f)($APP.Ah(e))}())],null));v+=1}else return!0}()?$APP.se($APP.ue(x),l($APP.mc(q))):$APP.se($APP.ue(x),null)}var w=$APP.E(q);return $APP.Q(new $APP.T(null,3,5,$APP.V,[Ny,new $APP.n(null,
1,[$APP.ej,w],null),$APP.Ch(function(){return function(v,z,I,J,M,R,ba,U){return function Z(pa){return new $APP.le(null,function(){for(;;){var oa=$APP.D(pa);if(oa){var ya=oa;if($APP.xd(ya)){var Ua=$APP.lc(ya),ib=$APP.G(Ua),Fb=$APP.pe(ib);return function(){for(var cb=0;;)if(cb<ib){var $b=$APP.kd(Ua,cb),ac=function(id,Gb,jd,Ke,Ng,eg,fg,Le){return function(Td){return $APP.p(Td.target.checked)?$APP.Y.l(a,$APP.Wo,$APP.Vp,ly,$APP.N([new $APP.T(null,3,5,$APP.V,[Gb,Le,"#"],null)])):$APP.Y.l(a,$APP.Wo,$APP.Vp,
my,$APP.N([new $APP.T(null,3,5,$APP.V,[Gb,Le,"#"],null)]))}}(cb,$b,Ua,ib,Fb,ya,oa,v,z,I,J,M,R,ba,U);$APP.te(Fb,new $APP.T(null,3,5,$APP.V,[Oy,new $APP.n(null,1,[$APP.ej,$b],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,3,[$APP.Wp,"checkbox",$APP.Sq,ny($APP.Vp.g($APP.y(a)),new $APP.T(null,2,5,$APP.V,[$b,v],null)),$APP.pp,ac],null)],null)],null));cb+=1}else return!0}()?$APP.se($APP.ue(Fb),Z($APP.mc(ya))):$APP.se($APP.ue(Fb),null)}var Pb=$APP.E(ya),ic=function(cb,$b,ac,id){return function(Gb){return $APP.p(Gb.target.checked)?
$APP.Y.l(a,$APP.Wo,$APP.Vp,ly,$APP.N([new $APP.T(null,3,5,$APP.V,[cb,id,"#"],null)])):$APP.Y.l(a,$APP.Wo,$APP.Vp,my,$APP.N([new $APP.T(null,3,5,$APP.V,[cb,id,"#"],null)]))}}(Pb,ya,oa,v,z,I,J,M,R,ba,U);return $APP.Q(new $APP.T(null,3,5,$APP.V,[Oy,new $APP.n(null,1,[$APP.ej,Pb],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,3,[$APP.Wp,"checkbox",$APP.Sq,ny($APP.Vp.g($APP.y(a)),new $APP.T(null,2,5,$APP.V,[Pb,v],null)),$APP.pp,ic],null)],null)],null),Z($APP.Jc(ya)))}return null}},null,null)}}(w,
q,m,b,c,d,e,f)($APP.Ah(e))}())],null),l($APP.Jc(q)))}return null}},null,null)}($APP.Ah(f))}())],null)],null)},Qy=function(a){$APP.Wh($APP.N([$APP.y(a)]));return new $APP.T(null,5,5,$APP.V,[$APP.Jp,new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,2,[$APP.Sp,"bgwidth",$APP.hq,"Width of the background tile"],null),"Background width"],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,4,[$APP.Vk,"bgwidth",$APP.Wp,"number",$APP.Qp,$APP.P.i($APP.Ar.g($APP.y(a)),0,1),$APP.pp,$APP.la(function(b){return $APP.Y.l(a,
$APP.Wo,$APP.Ar,$APP.Ve($APP.X,new $APP.T(null,2,5,$APP.V,[0,0],null)),$APP.N([0,parseInt(b.target.value)]))})],null)],null),new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,2,[$APP.Sp,"bgheight",$APP.hq,"Width of the background tile"],null),"Background height"],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,4,[$APP.Vk,"bgheigt",$APP.Wp,"number",$APP.Qp,$APP.P.i($APP.Ar.g($APP.y(a)),1,1),$APP.pp,$APP.la(function(b){return $APP.Y.l(a,$APP.Wo,$APP.Ar,$APP.Ve($APP.X,new $APP.T(null,2,5,
$APP.V,[0,0],null)),$APP.N([1,parseInt(b.target.value)]))})],null)],null)],null)},Ry=function(a){return new $APP.T(null,2,5,$APP.V,[$APP.Jp,function(){return function d(c){return new $APP.le(null,function(){for(;;){var e=$APP.D(c);if(e){var f=e;if($APP.xd(f)){var g=$APP.lc(f),h=$APP.G(g),l=$APP.pe(h);return function(){for(var x=0;;)if(x<h){var w=$APP.kd(g,x),v=$APP.O(w,0,null),z=$APP.O(w,1,null),I=$APP.O(w,2,null);w=$APP.la(function(J,M,R,ba){return function(U){return $APP.Y.l(a,$APP.Wo,$APP.Vp,ly,
$APP.N([new $APP.T(null,3,5,$APP.V,[R,ba,U.target.value],null)]))}}(x,w,v,z,I,g,h,l,f,e));$APP.te(l,new $APP.T(null,4,5,$APP.V,[$APP.Jp,new $APP.n(null,1,[$APP.ej,new $APP.T(null,2,5,$APP.V,[v,z],null)],null),new $APP.T(null,5,5,$APP.V,[$APP.Rq,new $APP.n(null,2,[$APP.Sp,["port",$APP.u.g(v),":",$APP.u.g(z)].join(""),$APP.hq,"Port name"],null),v,"/",z],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,4,[$APP.Vk,["port",$APP.u.g(v),":",$APP.u.g(z)].join(""),$APP.Wp,"text",$APP.Qp,I,$APP.pp,
w],null)],null)],null));x+=1}else return!0}()?$APP.se($APP.ue(l),d($APP.mc(f))):$APP.se($APP.ue(l),null)}var m=$APP.E(f),q=$APP.O(m,0,null),r=$APP.O(m,1,null),t=$APP.O(m,2,null);m=$APP.la(function(x,w,v){return function(z){return $APP.Y.l(a,$APP.Wo,$APP.Vp,ly,$APP.N([new $APP.T(null,3,5,$APP.V,[w,v,z.target.value],null)]))}}(m,q,r,t,f,e));return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.Jp,new $APP.n(null,1,[$APP.ej,new $APP.T(null,2,5,$APP.V,[q,r],null)],null),new $APP.T(null,5,5,$APP.V,[$APP.Rq,new $APP.n(null,
2,[$APP.Sp,["port",$APP.u.g(q),":",$APP.u.g(r)].join(""),$APP.hq,"Port name"],null),q,"/",r],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,4,[$APP.Vk,["port",$APP.u.g(q),":",$APP.u.g(r)].join(""),$APP.Wp,"text",$APP.Qp,t,$APP.pp,m],null)],null)],null),d($APP.Jc(f)))}return null}},null,null)}($APP.Vp.g($APP.y(a)))}()],null)},Sy=function(a){var b=$APP.y(Cy),c=$APP.al(a,new $APP.T(null,1,5,$APP.V,[b],null));return $APP.p(b)?new $APP.T(null,2,5,$APP.V,[$APP.Jp,new $APP.T(null,7,5,$APP.V,[$APP.xp,
new $APP.T(null,2,5,$APP.V,[Qy,c],null),new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,2,[$APP.Sp,"ports",$APP.hq,"pattern for the device ports"],null),"ports"],null),new $APP.T(null,2,5,$APP.V,[Py,c],null),new $APP.T(null,2,5,$APP.V,[Ry,c],null),new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,2,[$APP.Sp,"symurl",$APP.hq,"image url for this component"],null),"url"],null),new $APP.T(null,2,5,$APP.V,[$APP.Pq,new $APP.n(null,4,[$APP.Vk,"symurl",$APP.Wp,"text",$APP.Qp,$APP.mf($APP.y(a),new $APP.T(null,
2,5,$APP.V,[b,$APP.Ur],null)),$APP.mr,function(d){return $APP.Y.u(a,$APP.Vo,new $APP.T(null,2,5,$APP.V,[b,$APP.Ur],null),d.target.value)}],null)],null)],null)],null):null},Xy=function(a){var b=$APP.al(a,new $APP.T(null,3,5,$APP.V,[$APP.y(Cy),$APP.Or,$APP.Nh.g($APP.y(Gy))],null));$APP.Wh($APP.N([$APP.eh($APP.mf($APP.y(a),new $APP.T(null,2,5,$APP.V,[$APP.y(Cy),$APP.Or],null)))]));return $APP.H.h($APP.Wp.g($APP.y(b)),"spice")?new $APP.T(null,5,5,$APP.V,[$APP.xp,new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,
1,[$APP.Sp,"reftempl"],null),"Reference template"],null),new $APP.T(null,2,5,$APP.V,[Ty,new $APP.n(null,3,[$APP.Vk,"reftempl",$APP.jr,Uy.h($APP.y(b),"X{name} {ports} {properties}"),$APP.pp,function(c){return $APP.Y.u(b,$APP.X,Uy,c.target.value)}],null)],null),new $APP.T(null,3,5,$APP.V,[$APP.Rq,new $APP.n(null,1,[$APP.Sp,"decltempl"],null),"Declaration template"],null),new $APP.T(null,2,5,$APP.V,[Ty,new $APP.n(null,3,[$APP.Vk,"decltempl",$APP.jr,Wy.g($APP.y(b)),$APP.pp,function(c){return $APP.Y.u(b,
$APP.X,Wy,c.target.value)}],null)],null)],null):"TODO: preview"},hz=function(){var a=uy(function(){var b=$APP.y(yy);return $APP.p(b)?b:Dy}());return new $APP.T(null,3,5,$APP.V,[$APP.Jp,new $APP.T(null,4,5,$APP.V,[Yy,new $APP.T(null,3,5,$APP.V,[Zy,new $APP.T(null,3,5,$APP.V,[$y,new $APP.n(null,2,[$APP.Bp,function(){var b=$APP.y(yy);b=$APP.p(b)?prompt("Enter the name of the new cell"):b;return $APP.p(b)?$APP.Y.u(a,$APP.X,["models:",$APP.u.g(b)].join(""),new $APP.n(null,1,[$APP.bj,b],null)):null},az,
null==$APP.y(yy)],null),"+ Add cell"],null),new $APP.T(null,3,5,$APP.V,[bz,new $APP.T(null,3,5,$APP.V,[$y,new $APP.n(null,2,[$APP.Bp,function(){var b=$APP.y(yy);$APP.p(b)&&(b=$APP.y(Cy),b=$APP.p(b)?prompt("Enter the name of the new schematic"):b);return $APP.p(b)?$APP.Y.u(a,$APP.Vo,new $APP.T(null,3,5,$APP.V,[$APP.y(Cy),$APP.Or,$APP.Nh.g(b)],null),new $APP.n(null,2,[$APP.bj,b,$APP.Wp,"schematic"],null)):null},az,null==$APP.y(yy)||null==$APP.y(Cy)],null),"+ Add schematic"],null),new $APP.T(null,3,
5,$APP.V,[cz,new $APP.T(null,1,5,$APP.V,[dz],null),new $APP.T(null,3,5,$APP.V,[$y,new $APP.n(null,2,[$APP.Bp,function(){var b=$APP.y(yy);$APP.p(b)&&(b=$APP.y(Cy),b=$APP.p(b)?prompt("Enter the name of the new SPICE model"):b);return $APP.p(b)?$APP.Y.u(a,$APP.Vo,new $APP.T(null,3,5,$APP.V,[$APP.y(Cy),$APP.Or,$APP.Nh.g(b)],null),new $APP.n(null,2,[$APP.bj,b,$APP.Wp,"spice"],null)):null},az,null==$APP.y(yy)||null==$APP.y(Cy)],null),"+ Add SPICE model"],null)],null)],null)],null),new $APP.T(null,3,5,$APP.V,
[ez,"Cell ",function(){var b=$APP.y(Cy);return $APP.p(b)?$APP.fd(b.split(":")):null}()],null),new $APP.T(null,2,5,$APP.V,[Hy,a],null)],null),new $APP.T(null,3,5,$APP.V,[fz,new $APP.T(null,2,5,$APP.V,[gz,new $APP.T(null,2,5,$APP.V,[Xy,a],null)],null),new $APP.T(null,2,5,$APP.V,[Sy,a],null)],null)],null)},lz=function(){return new $APP.T(null,3,5,$APP.V,[$APP.Jp,new $APP.T(null,4,5,$APP.V,[iz,new $APP.T(null,3,5,$APP.V,[jz,new $APP.T(null,2,5,$APP.V,[$APP.Fr,"Library"],null),new $APP.T(null,3,5,$APP.V,
[kz,new $APP.n(null,1,[$APP.Bp,function(){var a=prompt("Enter the name of the new database");return $APP.D(a)?$APP.Y.u(qy,$APP.X,["databases:",$APP.u.g(a)].join(""),new $APP.n(null,1,[$APP.bj,a],null)):null}],null),"+"],null)],null),new $APP.T(null,1,5,$APP.V,[Ey],null),new $APP.T(null,1,5,$APP.V,[Ky],null)],null),new $APP.T(null,1,5,$APP.V,[hz],null)],null)},mz=function(){window.name="libman";var a=new $APP.T(null,1,5,$APP.V,[lz],null),b=document.getElementById("mosaic_libman");return $APP.gl(a,
b)},Ly=new $APP.S(null,"table","table",-564943036),az=new $APP.S(null,"disabled","disabled",-1529784218),iz=new $APP.S(null,"div.libraries","div.libraries",704668226),fz=new $APP.S(null,"div.proppane","div.proppane",-1332639267),Iy=new $APP.S(null,"div.dbprops","div.dbprops",-1556934616),Wy=new $APP.S(null,"decltempl","decltempl",-878889161),jz=new $APP.S(null,"div.libhead","div.libhead",1365032704),Ny=new $APP.S(null,"tr","tr",-1424774646),Oy=new $APP.S(null,"td","td",1479933353),dz=new $APP.S(null,
"summary.button","summary.button",-1314921300),gz=new $APP.S(null,"div.preview","div.preview",-1882273840),xy=new $APP.S(null,"open","open",-1763596448),Ay=new $APP.S(null,"summary","summary",380847952),Uy=new $APP.S(null,"reftempl","reftempl",2007300758),bz=new $APP.S(null,"div.buttongroup.primary","div.buttongroup.primary",1038300842),kz=new $APP.S(null,"button.plus","button.plus",40612401),Yy=new $APP.S(null,"div.schsel","div.schsel",-1209283060),$y=new $APP.S(null,"button","button",1456579943),
My=new $APP.S(null,"tbody","tbody",-80678300),Dy=new $APP.S(null,"schematics","schematics",912295165),vy=new $APP.S(null,"div.cellsel","div.cellsel",-277762551),Fy=new $APP.S(null,"div.schematics","div.schematics",908542795),By=new $APP.S(null,"div.detailbody","div.detailbody",-1679946259),cz=new $APP.S(null,"details","details",1956795411),zy=new $APP.S(null,"on-toggle","on-toggle",-695538774),ty=new $APP.S(null,"url","url",276297046),Zy=new $APP.S(null,"div.addbuttons","div.addbuttons",-579122699),
ez=new $APP.S(null,"h2","h2",-372662728),Jy=new $APP.S(null,"h3","h3",2067611163),Ty=new $APP.S(null,"textarea","textarea",-650375824),wy=new $APP.S(null,"details.tree","details.tree",-1643409151);var yy=$APP.Tj.g(null),Cy=$APP.Tj.g(null),Gy=$APP.Tj.g(null),qy=$APP.km(new $APP.fs("local"),"databases",$APP.Tj.g($APP.Me)),sy=$APP.Tj.g($APP.Me),py=$APP.$e($APP.Me),ry=new $APP.n(null,2,[$APP.bj,"schematics",ty,"https://c6be5bcc-59a8-492d-91fd-59acc17fef02-bluemix.cloudantnosqldb.appdomain.cloud/schematics"],null);$APP.ka("nyancad.mosaic.libman.init",mz);try{mz()}catch(a){throw console.error("An error occurred when calling (nyancad.mosaic.libman/init)"),a;};
}).call(this);