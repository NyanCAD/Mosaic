(function(){
'use strict';var Hy=function(a){for(var b=Array(arguments.length),c=0;;)if(c<b.length)b[c]=arguments[c],c+=1;else return b},Iy=function(a){a=a.destroy;return null==a?null:a.m?a.m():a.call(null)},Jy=function(){var a=$APP.xj;return null==a?[]:$APP.Ij(Hy,a,$APP.Sr,null,Iy)},Ky=function(a,b,c){for(var d=0,e=$APP.F(a);;)if(d<e){var f=$APP.fe(d+e);var g=$APP.P.h(a,f);g=c.g?c.g(g):c.call(null,g);0>$APP.Od(g,b)?d=f+1:e=f}else return d},Ly=function(a,b,c){return $APP.Of($APP.pf.l($APP.Wf(a,0,b),new $APP.T(null,
1,5,$APP.V,[c],null),$APP.K([$APP.Wf(a,b,$APP.F(a))])))},My=function(a,b){return $APP.Of($APP.pf.h($APP.Wf(a,0,b),$APP.Wf(a,b+1,$APP.F(a))))},Ny=function(a,b){function c(g){return $APP.Wf(g,0,2)}a=$APP.Of(a);var d=c(b),e=Ky(a,d,c),f=$APP.P.h(a,e);return $APP.Dd(f)&&$APP.G.h(d,c(f))?$APP.Y.i(a,e,b):Ly(a,e,b)},Oy=function(a,b){function c(f){return $APP.Wf(f,0,2)}a=$APP.Of(a);b=c(b);var d=Ky(a,b,c),e=$APP.P.h(a,d);$APP.ci($APP.K([a,b,d,e]));return $APP.Dd(e)&&$APP.G.h(b,c(e))?My(a,d):a},Py=function(a,
b){function c(e){return $APP.Wf(e,0,2)}a=$APP.Of(a);b=c(b);var d=Ky(a,b,c);a=$APP.P.h(a,d);return $APP.Dd(a)&&$APP.G.h(b,c(a))},Qy=function(a){var b=Jy(),c=0===b.length,d=c||$APP.La(b.hasOwnProperty(0))?b[0]=$APP.el(!1):b[0],e=c||$APP.La(b.hasOwnProperty(1))?b[1]=$APP.ef(null):b[1],f=c||$APP.La(b.hasOwnProperty(2))?b[2]=function(){return $APP.hf(d,!0)}:b[2];return $APP.p(function(){var g=$APP.y(d);return $APP.p(g)?g:$APP.xd($APP.y(a))}())?new $APP.T(null,3,5,$APP.V,[$APP.Eq,new $APP.n(null,1,[$APP.Pq,
function(g){console.log(g);g.preventDefault();$APP.hf(a,g.target.elements.namefield.value);return $APP.hf(d,!1)}],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,5,[$APP.eq,$APP.Nq,$APP.fj,"namefield",$APP.Sq,!0,$APP.Zp,$APP.y(a),$APP.yr,function(g){$APP.hf(a,g.target.value);return $APP.hf(d,!1)}],null)],null)],null):new $APP.T(null,3,5,$APP.V,[$APP.iq,new $APP.n(null,1,[$APP.Kp,function(g){return $APP.G.h(g.detail,1)?$APP.hf(e,window.setTimeout(f,1E3)):window.clearTimeout($APP.y(e))}],
null),$APP.y(a)],null)},Wy=function(a){var b=$APP.P.h($APP.y(Ry),a);if($APP.p(b))var c=b;else{b=$APP.P.i($APP.y(Sy),a,Ty);c=$APP.fj.g(b);c=$APP.p(window.dburl)?(new URL(c,window.dburl)).href:c;var d=new $APP.rs(c);c=$APP.qm(d,"models",$APP.fl(Uy,new $APP.T(null,1,5,$APP.V,[a],null)));$APP.om(d,$APP.K([c]));$APP.p(Vy.g(b))&&$APP.rs.sync(Vy.g(b),d);$APP.Z.u(Ry,$APP.Y,a,c)}return c},ez=function(){return new $APP.T(null,2,5,$APP.V,[Xy,$APP.Ih(function(){return function c(b){return new $APP.se(null,function(){for(;;){var d=
$APP.C(b);if(d){var e=d;if($APP.Ed(e)){var f=$APP.sc(e),g=$APP.F(f),h=$APP.ve(g);return function(){for(var t=0;;)if(t<g){var v=$APP.rd(f,t),u=$APP.M(v,0,null),x=$APP.M(v,1,null),A=Wy(u);$APP.ze(h,new $APP.T(null,4,5,$APP.V,[Yy,new $APP.n(null,3,[$APP.ij,u,Zy,$APP.G.h(u,$APP.y($y)),az,function(I,J,N,O){return function(U){return $APP.p(U.target.open)?$APP.hf($y,O):null}}(t,A,v,u,x,f,g,h,e,d)],null),new $APP.T(null,2,5,$APP.V,[$APP.Hq,$APP.fj.g(x)],null),new $APP.T(null,2,5,$APP.V,[bz,new $APP.T(null,
3,5,$APP.V,[$APP.Ht,cz,function(){return function(I,J,N,O,U,S,W,oa,X,ka){return function Ra(Ja){return new $APP.se(null,function($a,ab){return function(){for(;;){var eb=$APP.C(Ja);if(eb){if($APP.Ed(eb)){var Ma=$APP.sc(eb),ib=$APP.F(Ma),Hb=$APP.ve(ib);a:for(var Pb=0;;)if(Pb<ib){var bb=$APP.rd(Ma,Pb),cc=$APP.M(bb,0,null);bb=$APP.M(bb,1,null);var dc=$APP.od(cc.split(":"));cc=new $APP.T(null,4,5,$APP.V,[$APP.P.i(bb,$APP.fj,dc),new $APP.T(null,2,5,$APP.V,[Qy,$APP.fl(ab,new $APP.T(null,2,5,$APP.V,[cc,$APP.fj],
null))],null),cc,dc],null);Hb.add(cc);Pb+=1}else{Ma=!0;break a}return Ma?$APP.ye($APP.Ae(Hb),Ra($APP.tc(eb))):$APP.ye($APP.Ae(Hb),null)}Ma=$APP.D(eb);Hb=$APP.M(Ma,0,null);Ma=$APP.M(Ma,1,null);ib=$APP.od(Hb.split(":"));return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.P.i(Ma,$APP.fj,ib),new $APP.T(null,2,5,$APP.V,[Qy,$APP.fl(ab,new $APP.T(null,2,5,$APP.V,[Hb,$APP.fj],null))],null),Hb,ib],null),Ra($APP.Sc(eb)))}return null}}}(I,J,N,O,U,S,W,oa,X,ka),null,null)}}(t,A,v,u,x,f,g,h,e,d)($APP.y(A))}()],null)],
null)],null));t+=1}else return!0}()?$APP.ye($APP.Ae(h),c($APP.tc(e))):$APP.ye($APP.Ae(h),null)}var l=$APP.D(e),m=$APP.M(l,0,null),q=$APP.M(l,1,null),r=Wy(m);return $APP.Q(new $APP.T(null,4,5,$APP.V,[Yy,new $APP.n(null,3,[$APP.ij,m,Zy,$APP.G.h(m,$APP.y($y)),az,function(t,v,u){return function(x){return $APP.p(x.target.open)?$APP.hf($y,u):null}}(r,l,m,q,e,d)],null),new $APP.T(null,2,5,$APP.V,[$APP.Hq,$APP.fj.g(q)],null),new $APP.T(null,2,5,$APP.V,[bz,new $APP.T(null,3,5,$APP.V,[$APP.Ht,cz,function(){return function(t){return function x(u){return new $APP.se(null,
function(){for(;;){var A=$APP.C(u);if(A){if($APP.Ed(A)){var I=$APP.sc(A),J=$APP.F(I),N=$APP.ve(J);a:for(var O=0;;)if(O<J){var U=$APP.rd(I,O),S=$APP.M(U,0,null);U=$APP.M(U,1,null);var W=$APP.od(S.split(":"));S=new $APP.T(null,4,5,$APP.V,[$APP.P.i(U,$APP.fj,W),new $APP.T(null,2,5,$APP.V,[Qy,$APP.fl(t,new $APP.T(null,2,5,$APP.V,[S,$APP.fj],null))],null),S,W],null);N.add(S);O+=1}else{I=!0;break a}return I?$APP.ye($APP.Ae(N),x($APP.tc(A))):$APP.ye($APP.Ae(N),null)}I=$APP.D(A);N=$APP.M(I,0,null);I=$APP.M(I,
1,null);J=$APP.od(N.split(":"));return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.P.i(I,$APP.fj,J),new $APP.T(null,2,5,$APP.V,[Qy,$APP.fl(t,new $APP.T(null,2,5,$APP.V,[N,$APP.fj],null))],null),N,J],null),x($APP.Sc(A)))}return null}},null,null)}}(r,l,m,q,e,d)($APP.y(r))}()],null)],null)],null),c($APP.Sc(e)))}return null}},null,null)}($APP.pf.h(new $APP.T(null,1,5,$APP.V,[new $APP.T(null,2,5,$APP.V,[dz,Ty],null)],null),$APP.y(Sy)))}())],null)},hz=function(a){var b=$APP.y(cz),c=$APP.P.h($APP.y(a),b);return new $APP.T(null,
2,5,$APP.V,[fz,new $APP.T(null,4,5,$APP.V,[$APP.Ht,gz,function(){return function f(e){return new $APP.se(null,function(){for(;;){var g=$APP.C(e);if(g){if($APP.Ed(g)){var h=$APP.sc(g),l=$APP.F(h),m=$APP.ve(l);a:for(var q=0;;)if(q<l){var r=$APP.rd(h,q),t=$APP.M(r,0,null);r=$APP.M(r,1,null);t=new $APP.T(null,4,5,$APP.V,[$APP.P.i(r,$APP.fj,t),new $APP.T(null,2,5,$APP.V,[Qy,$APP.fl(a,new $APP.T(null,4,5,$APP.V,[b,$APP.bs,t,$APP.fj],null))],null),t,t],null);m.add(t);q+=1}else{h=!0;break a}return h?$APP.ye($APP.Ae(m),
f($APP.tc(g))):$APP.ye($APP.Ae(m),null)}h=$APP.D(g);m=$APP.M(h,0,null);h=$APP.M(h,1,null);return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.P.i(h,$APP.fj,m),new $APP.T(null,2,5,$APP.V,[Qy,$APP.fl(a,new $APP.T(null,4,5,$APP.V,[b,$APP.bs,m,$APP.fj],null))],null),m,m],null),f($APP.Sc(g)))}return null}},null,null)}($APP.bs.g(c))}(),function(d){$APP.ci($APP.K([c,d]));return $APP.G.h($APP.tf(c,new $APP.T(null,3,5,$APP.V,[$APP.bs,d,$APP.eq],null)),"schematic")?function(){var e=window,f=e.open,g=$APP.od(b.split(":")),
h=$APP.Ah(d);g=[$APP.w.g(g),"$",$APP.w.g(h)].join("");h=$APP.P.i($APP.y(Sy),$APP.y($y),Ty);var l=new URL("editor",window.location);l.searchParams.append("schem",g);l.searchParams.append("db",$APP.fj.h(h,""));l.searchParams.append("sync",Vy.h(h,""));return f.call(e,l,b)}:null}],null)],null)},kz=function(){var a=$APP.y($y),b=$APP.P.i($APP.y(Sy),a,Ty);return $APP.p(a)?new $APP.T(null,3,5,$APP.V,[iz,new $APP.T(null,2,5,$APP.V,[jz,"Library properties"],null),new $APP.T(null,5,5,$APP.V,[$APP.Gp,new $APP.T(null,
3,5,$APP.V,[$APP.cr,new $APP.n(null,1,[$APP.aq,"dbname"],null),"Name"],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,3,[$APP.Zk,"dbname",$APP.vr,$APP.fj.g(b),$APP.yp,function(c){return $APP.Z.u(Sy,$APP.cp,new $APP.T(null,2,5,$APP.V,[a,$APP.fj],null),c.target.value)}],null)],null),new $APP.T(null,3,5,$APP.V,[$APP.cr,new $APP.n(null,1,[$APP.aq,"dburl"],null),"URL"],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,3,[$APP.Zk,"dburl",$APP.vr,Vy.g(b),$APP.yp,function(c){return $APP.Z.u(Sy,
$APP.cp,new $APP.T(null,2,5,$APP.V,[a,Vy],null),c.target.value)}],null)],null)],null)],null):null},pz=function(a){$APP.ci($APP.K([$APP.y(a)]));var b=$APP.Nr.g($APP.y(a)),c=$APP.M(b,0,null),d=$APP.M(b,1,null),e=2+c,f=2+d;$APP.ci($APP.K([e,f]));return new $APP.T(null,2,5,$APP.V,[lz,new $APP.T(null,2,5,$APP.V,[mz,$APP.Ih(function(){return function l(h){return new $APP.se(null,function(){for(;;){var m=$APP.C(h);if(m){var q=m;if($APP.Ed(q)){var r=$APP.sc(q),t=$APP.F(r),v=$APP.ve(t);return function(){for(var x=
0;;)if(x<t){var A=$APP.rd(r,x);$APP.ze(v,new $APP.T(null,3,5,$APP.V,[nz,new $APP.n(null,1,[$APP.ij,A],null),$APP.Ih(function(){return function(I,J,N,O,U,S,W,oa,X,ka,ta,Ja){return function ab($a){return new $APP.se(null,function(eb,Ma,ib,Hb,Pb,bb,cc,dc,vb,ec,Qc,qb){return function(){for(;;){var Cb=$APP.C($a);if(Cb){var fc=Cb;if($APP.Ed(fc)){var Id=$APP.sc(fc),ch=$APP.F(Id),Ef=$APP.ve(ch);return function(){for(var Ff=0;;)if(Ff<ch){var We=$APP.rd(Id,Ff),dh=function(eh,fh,Bb,Us,Vs,Ws,Fl,Lg,ff){return function(Fe){return $APP.p(Fe.target.checked)?
$APP.Z.l(a,$APP.dp,$APP.dq,Ny,$APP.K([new $APP.T(null,3,5,$APP.V,[Bb,ff,"#"],null)])):$APP.Z.l(a,$APP.dp,$APP.dq,Oy,$APP.K([new $APP.T(null,3,5,$APP.V,[Bb,ff,"#"],null)]))}}(Ff,eb,We,Id,ch,Ef,fc,Cb,Ma,ib,Hb,Pb,bb,cc,dc,vb,ec,Qc,qb);$APP.ze(Ef,new $APP.T(null,3,5,$APP.V,[oz,new $APP.n(null,1,[$APP.ij,We],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,3,[$APP.eq,"checkbox",$APP.dr,Py($APP.dq.g($APP.y(a)),new $APP.T(null,2,5,$APP.V,[We,Ma],null)),$APP.yp,dh],null)],null)],null));Ff+=1}else return!0}()?
$APP.ye($APP.Ae(Ef),ab($APP.tc(fc))):$APP.ye($APP.Ae(Ef),null)}var Jd=$APP.D(fc),bi=function(Ff,We,dh,eh,fh){return function(Bb){return $APP.p(Bb.target.checked)?$APP.Z.l(a,$APP.dp,$APP.dq,Ny,$APP.K([new $APP.T(null,3,5,$APP.V,[We,fh,"#"],null)])):$APP.Z.l(a,$APP.dp,$APP.dq,Oy,$APP.K([new $APP.T(null,3,5,$APP.V,[We,fh,"#"],null)]))}}(eb,Jd,fc,Cb,Ma,ib,Hb,Pb,bb,cc,dc,vb,ec,Qc,qb);return $APP.Q(new $APP.T(null,3,5,$APP.V,[oz,new $APP.n(null,1,[$APP.ij,Jd],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,
3,[$APP.eq,"checkbox",$APP.dr,Py($APP.dq.g($APP.y(a)),new $APP.T(null,2,5,$APP.V,[Jd,Ma],null)),$APP.yp,bi],null)],null)],null),ab($APP.Sc(fc)))}return null}}}(I,J,N,O,U,S,W,oa,X,ka,ta,Ja),null,null)}}(x,A,r,t,v,q,m,b,c,d,e,f)($APP.Gh(0,e))}())],null));x+=1}else return!0}()?$APP.ye($APP.Ae(v),l($APP.tc(q))):$APP.ye($APP.Ae(v),null)}var u=$APP.D(q);return $APP.Q(new $APP.T(null,3,5,$APP.V,[nz,new $APP.n(null,1,[$APP.ij,u],null),$APP.Ih(function(){return function(x,A,I,J,N,O,U,S){return function X(oa){return new $APP.se(null,
function(){for(;;){var ka=$APP.C(oa);if(ka){var ta=ka;if($APP.Ed(ta)){var Ja=$APP.sc(ta),Ra=$APP.F(Ja),$a=$APP.ve(Ra);return function(){for(var Ma=0;;)if(Ma<Ra){var ib=$APP.rd(Ja,Ma),Hb=function(Pb,bb,cc,dc,vb,ec,Qc,qb){return function(Cb){return $APP.p(Cb.target.checked)?$APP.Z.l(a,$APP.dp,$APP.dq,Ny,$APP.K([new $APP.T(null,3,5,$APP.V,[bb,qb,"#"],null)])):$APP.Z.l(a,$APP.dp,$APP.dq,Oy,$APP.K([new $APP.T(null,3,5,$APP.V,[bb,qb,"#"],null)]))}}(Ma,ib,Ja,Ra,$a,ta,ka,x,A,I,J,N,O,U,S);$APP.ze($a,new $APP.T(null,
3,5,$APP.V,[oz,new $APP.n(null,1,[$APP.ij,ib],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,3,[$APP.eq,"checkbox",$APP.dr,Py($APP.dq.g($APP.y(a)),new $APP.T(null,2,5,$APP.V,[ib,x],null)),$APP.yp,Hb],null)],null)],null));Ma+=1}else return!0}()?$APP.ye($APP.Ae($a),X($APP.tc(ta))):$APP.ye($APP.Ae($a),null)}var ab=$APP.D(ta),eb=function(Ma,ib,Hb,Pb){return function(bb){return $APP.p(bb.target.checked)?$APP.Z.l(a,$APP.dp,$APP.dq,Ny,$APP.K([new $APP.T(null,3,5,$APP.V,[Ma,Pb,"#"],null)])):$APP.Z.l(a,
$APP.dp,$APP.dq,Oy,$APP.K([new $APP.T(null,3,5,$APP.V,[Ma,Pb,"#"],null)]))}}(ab,ta,ka,x,A,I,J,N,O,U,S);return $APP.Q(new $APP.T(null,3,5,$APP.V,[oz,new $APP.n(null,1,[$APP.ij,ab],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,3,[$APP.eq,"checkbox",$APP.dr,Py($APP.dq.g($APP.y(a)),new $APP.T(null,2,5,$APP.V,[ab,x],null)),$APP.yp,eb],null)],null)],null),X($APP.Sc(ta)))}return null}},null,null)}}(u,q,m,b,c,d,e,f)($APP.Gh(0,e))}())],null),l($APP.Sc(q)))}return null}},null,null)}($APP.Gh(0,f))}())],
null)],null)},qz=function(a){$APP.ci($APP.K([$APP.y(a)]));return new $APP.T(null,5,5,$APP.V,[$APP.Sp,new $APP.T(null,3,5,$APP.V,[$APP.cr,new $APP.n(null,2,[$APP.aq,"bgwidth",$APP.qq,"Width of the background tile"],null),"Background width"],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,4,[$APP.Zk,"bgwidth",$APP.eq,"number",$APP.Zp,$APP.P.i($APP.Nr.g($APP.y(a)),0,1),$APP.yp,$APP.la(function(b){return $APP.Z.l(a,$APP.dp,$APP.Nr,$APP.af($APP.Y,new $APP.T(null,2,5,$APP.V,[0,0],null)),$APP.K([0,
parseInt(b.target.value)]))})],null)],null),new $APP.T(null,3,5,$APP.V,[$APP.cr,new $APP.n(null,2,[$APP.aq,"bgheight",$APP.qq,"Width of the background tile"],null),"Background height"],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,4,[$APP.Zk,"bgheigt",$APP.eq,"number",$APP.Zp,$APP.P.i($APP.Nr.g($APP.y(a)),1,1),$APP.yp,$APP.la(function(b){return $APP.Z.l(a,$APP.dp,$APP.Nr,$APP.af($APP.Y,new $APP.T(null,2,5,$APP.V,[0,0],null)),$APP.K([1,parseInt(b.target.value)]))})],null)],null)],null)},
rz=function(a){return new $APP.T(null,2,5,$APP.V,[$APP.Sp,function(){return function d(c){return new $APP.se(null,function(){for(;;){var e=$APP.C(c);if(e){var f=e;if($APP.Ed(f)){var g=$APP.sc(f),h=$APP.F(g),l=$APP.ve(h);return function(){for(var v=0;;)if(v<h){var u=$APP.rd(g,v),x=$APP.M(u,0,null),A=$APP.M(u,1,null),I=$APP.M(u,2,null);u=$APP.la(function(J,N,O,U){return function(S){return $APP.Z.l(a,$APP.dp,$APP.dq,Ny,$APP.K([new $APP.T(null,3,5,$APP.V,[O,U,S.target.value],null)]))}}(v,u,x,A,I,g,h,
l,f,e));$APP.ze(l,new $APP.T(null,4,5,$APP.V,[$APP.Sp,new $APP.n(null,1,[$APP.ij,new $APP.T(null,2,5,$APP.V,[x,A],null)],null),new $APP.T(null,5,5,$APP.V,[$APP.cr,new $APP.n(null,2,[$APP.aq,["port",$APP.w.g(x),":",$APP.w.g(A)].join(""),$APP.qq,"Port name"],null),x,"/",A],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,4,[$APP.Zk,["port",$APP.w.g(x),":",$APP.w.g(A)].join(""),$APP.eq,"text",$APP.Zp,I,$APP.yp,u],null)],null)],null));v+=1}else return!0}()?$APP.ye($APP.Ae(l),d($APP.tc(f))):$APP.ye($APP.Ae(l),
null)}var m=$APP.D(f),q=$APP.M(m,0,null),r=$APP.M(m,1,null),t=$APP.M(m,2,null);m=$APP.la(function(v,u,x){return function(A){return $APP.Z.l(a,$APP.dp,$APP.dq,Ny,$APP.K([new $APP.T(null,3,5,$APP.V,[u,x,A.target.value],null)]))}}(m,q,r,t,f,e));return $APP.Q(new $APP.T(null,4,5,$APP.V,[$APP.Sp,new $APP.n(null,1,[$APP.ij,new $APP.T(null,2,5,$APP.V,[q,r],null)],null),new $APP.T(null,5,5,$APP.V,[$APP.cr,new $APP.n(null,2,[$APP.aq,["port",$APP.w.g(q),":",$APP.w.g(r)].join(""),$APP.qq,"Port name"],null),
q,"/",r],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,4,[$APP.Zk,["port",$APP.w.g(q),":",$APP.w.g(r)].join(""),$APP.eq,"text",$APP.Zp,t,$APP.yp,m],null)],null)],null),d($APP.Sc(f)))}return null}},null,null)}($APP.dq.g($APP.y(a)))}()],null)},sz=function(a){var b=$APP.y(cz),c=$APP.fl(a,new $APP.T(null,1,5,$APP.V,[b],null));return $APP.p(b)?new $APP.T(null,2,5,$APP.V,[$APP.Sp,new $APP.T(null,7,5,$APP.V,[$APP.Gp,new $APP.T(null,2,5,$APP.V,[qz,c],null),new $APP.T(null,3,5,$APP.V,[$APP.cr,
new $APP.n(null,2,[$APP.aq,"ports",$APP.qq,"pattern for the device ports"],null),"ports"],null),new $APP.T(null,2,5,$APP.V,[pz,c],null),new $APP.T(null,2,5,$APP.V,[rz,c],null),new $APP.T(null,3,5,$APP.V,[$APP.cr,new $APP.n(null,2,[$APP.aq,"symurl",$APP.qq,"image url for this component"],null),"url"],null),new $APP.T(null,2,5,$APP.V,[$APP.ar,new $APP.n(null,4,[$APP.Zk,"symurl",$APP.eq,"text",$APP.Zp,$APP.tf($APP.y(a),new $APP.T(null,2,5,$APP.V,[b,$APP.gs],null)),$APP.yr,function(d){return $APP.Z.u(a,
$APP.cp,new $APP.T(null,2,5,$APP.V,[b,$APP.gs],null),d.target.value)}],null)],null)],null)],null):null},wz=function(a){var b=$APP.fl(a,new $APP.T(null,3,5,$APP.V,[$APP.y(cz),$APP.bs,$APP.Th.g($APP.y(gz))],null));$APP.ci($APP.K([$APP.mh($APP.tf($APP.y(a),new $APP.T(null,2,5,$APP.V,[$APP.y(cz),$APP.bs],null)))]));return $APP.G.h($APP.eq.g($APP.y(b)),"spice")?new $APP.T(null,5,5,$APP.V,[$APP.Gp,new $APP.T(null,3,5,$APP.V,[$APP.cr,new $APP.n(null,1,[$APP.aq,"reftempl"],null),"Reference template"],null),
new $APP.T(null,2,5,$APP.V,[tz,new $APP.n(null,3,[$APP.Zk,"reftempl",$APP.vr,uz.h($APP.y(b),"X{name} {ports} {properties}"),$APP.yp,function(c){return $APP.Z.u(b,$APP.Y,uz,c.target.value)}],null)],null),new $APP.T(null,3,5,$APP.V,[$APP.cr,new $APP.n(null,1,[$APP.aq,"decltempl"],null),"Declaration template"],null),new $APP.T(null,2,5,$APP.V,[tz,new $APP.n(null,3,[$APP.Zk,"decltempl",$APP.vr,vz.g($APP.y(b)),$APP.yp,function(c){return $APP.Z.u(b,$APP.Y,vz,c.target.value)}],null)],null)],null):"TODO: preview"},
Fz=function(){var a=Wy(function(){var b=$APP.y($y);return $APP.p(b)?b:dz}());return new $APP.T(null,3,5,$APP.V,[$APP.Sp,new $APP.T(null,4,5,$APP.V,[xz,new $APP.T(null,3,5,$APP.V,[yz,new $APP.T(null,3,5,$APP.V,[$APP.Tq,new $APP.n(null,2,[$APP.Kp,function(){var b=$APP.y($y);b=$APP.p(b)?prompt("Enter the name of the new cell"):b;return $APP.p(b)?$APP.Z.u(a,$APP.Y,["models:",$APP.w.g(b)].join(""),new $APP.n(null,1,[$APP.fj,b],null)):null},zz,null==$APP.y($y)],null),"+ Add cell"],null),new $APP.T(null,
3,5,$APP.V,[Az,new $APP.T(null,3,5,$APP.V,[$APP.Tq,new $APP.n(null,2,[$APP.Kp,function(){var b=$APP.y($y);$APP.p(b)&&(b=$APP.y(cz),b=$APP.p(b)?prompt("Enter the name of the new schematic"):b);return $APP.p(b)?$APP.Z.u(a,$APP.cp,new $APP.T(null,3,5,$APP.V,[$APP.y(cz),$APP.bs,$APP.Th.g(b)],null),new $APP.n(null,2,[$APP.fj,b,$APP.eq,"schematic"],null)):null},zz,null==$APP.y($y)||null==$APP.y(cz)],null),"+ Add schematic"],null),new $APP.T(null,3,5,$APP.V,[$APP.zr,new $APP.T(null,1,5,$APP.V,[Bz],null),
new $APP.T(null,3,5,$APP.V,[$APP.Tq,new $APP.n(null,2,[$APP.Kp,function(){var b=$APP.y($y);$APP.p(b)&&(b=$APP.y(cz),b=$APP.p(b)?prompt("Enter the name of the new SPICE model"):b);return $APP.p(b)?$APP.Z.u(a,$APP.cp,new $APP.T(null,3,5,$APP.V,[$APP.y(cz),$APP.bs,$APP.Th.g(b)],null),new $APP.n(null,2,[$APP.fj,b,$APP.eq,"spice"],null)):null},zz,null==$APP.y($y)||null==$APP.y(cz)],null),"+ Add SPICE model"],null)],null)],null)],null),new $APP.T(null,3,5,$APP.V,[Cz,"Cell ",function(){var b=$APP.y(cz);
return $APP.p(b)?$APP.od(b.split(":")):null}()],null),new $APP.T(null,2,5,$APP.V,[hz,a],null)],null),new $APP.T(null,3,5,$APP.V,[Dz,new $APP.T(null,2,5,$APP.V,[Ez,new $APP.T(null,2,5,$APP.V,[wz,a],null)],null),new $APP.T(null,2,5,$APP.V,[sz,a],null)],null)],null)},Jz=function(){return new $APP.T(null,3,5,$APP.V,[$APP.Sp,new $APP.T(null,4,5,$APP.V,[Gz,new $APP.T(null,3,5,$APP.V,[Hz,new $APP.T(null,2,5,$APP.V,[$APP.Tr,"Library"],null),new $APP.T(null,3,5,$APP.V,[Iz,new $APP.n(null,1,[$APP.Kp,function(){var a=
prompt("Enter the name of the new database");return $APP.C(a)?$APP.Z.u(Sy,$APP.Y,["databases:",$APP.w.g(a)].join(""),new $APP.n(null,1,[$APP.fj,a],null)):null}],null),"+"],null)],null),new $APP.T(null,1,5,$APP.V,[ez],null),new $APP.T(null,1,5,$APP.V,[kz],null)],null),new $APP.T(null,1,5,$APP.V,[Fz],null)],null)},Kz=function(){window.name="libman";var a=new $APP.T(null,1,5,$APP.V,[Jz],null),b=document.getElementById("mosaic_libman");return $APP.ll(a,b)},lz=new $APP.R(null,"table","table",-564943036),
zz=new $APP.R(null,"disabled","disabled",-1529784218),Gz=new $APP.R(null,"div.libraries","div.libraries",704668226),Dz=new $APP.R(null,"div.proppane","div.proppane",-1332639267),iz=new $APP.R(null,"div.dbprops","div.dbprops",-1556934616),vz=new $APP.R(null,"decltempl","decltempl",-878889161),Hz=new $APP.R(null,"div.libhead","div.libhead",1365032704),nz=new $APP.R(null,"tr","tr",-1424774646),oz=new $APP.R(null,"td","td",1479933353),Bz=new $APP.R(null,"summary.button","summary.button",-1314921300),
Ez=new $APP.R(null,"div.preview","div.preview",-1882273840),Zy=new $APP.R(null,"open","open",-1763596448),uz=new $APP.R(null,"reftempl","reftempl",2007300758),Az=new $APP.R(null,"div.buttongroup.primary","div.buttongroup.primary",1038300842),Iz=new $APP.R(null,"button.plus","button.plus",40612401),xz=new $APP.R(null,"div.schsel","div.schsel",-1209283060),mz=new $APP.R(null,"tbody","tbody",-80678300),dz=new $APP.R(null,"schematics","schematics",912295165),Xy=new $APP.R(null,"div.cellsel","div.cellsel",
-277762551),fz=new $APP.R(null,"div.schematics","div.schematics",908542795),bz=new $APP.R(null,"div.detailbody","div.detailbody",-1679946259),az=new $APP.R(null,"on-toggle","on-toggle",-695538774),Vy=new $APP.R(null,"url","url",276297046),yz=new $APP.R(null,"div.addbuttons","div.addbuttons",-579122699),Cz=new $APP.R(null,"h2","h2",-372662728),jz=new $APP.R(null,"h3","h3",2067611163),tz=new $APP.R(null,"textarea","textarea",-650375824),Yy=new $APP.R(null,"details.tree","details.tree",-1643409151);var $y=$APP.el(null),cz=$APP.el(null),gz=$APP.el(null),Sy=$APP.qm(new $APP.rs("local"),"databases",$APP.el($APP.Re)),Uy=$APP.el($APP.Re),Ry=$APP.ef($APP.Re),Ty=new $APP.n(null,2,[$APP.fj,"schematics",Vy,"https://c6be5bcc-59a8-492d-91fd-59acc17fef02-bluemix.cloudantnosqldb.appdomain.cloud/schematics"],null);$APP.ja("nyancad.mosaic.libman.init",Kz);try{Kz()}catch(a){throw console.error("An error occurred when calling (nyancad.mosaic.libman/init)"),a;};
}).call(this);