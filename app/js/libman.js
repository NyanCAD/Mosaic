(function(){
'use strict';var az=function(a){for(var b=Array(arguments.length),c=0;;)if(c<b.length)b[c]=arguments[c],c+=1;else return b},bz=function(a){a=a.destroy;return null==a?null:a.m?a.m():a.call(null)},cz=function(a){$APP.ci(a,$APP.Ea());$APP.p($APP.Fa)&&$APP.di($APP.Ea())},dz=function(){var a=$APP.Dj;return null==a?[]:$APP.Oj(az,a,$APP.ws,null,bz)},ez=function(a,b,c){for(var d=0,e=$APP.F(a);;)if(d<e){var f=$APP.fe(d+e);var g=$APP.N.h(a,f);g=c.g?c.g(g):c.call(null,g);0>$APP.Md(g,b)?d=f+1:e=f}else return d},
fz=function(a,b,c){return $APP.Sf($APP.tf.l($APP.$f(a,0,b),new $APP.S(null,1,5,$APP.V,[c],null),$APP.K([$APP.$f(a,b,$APP.F(a))])))},gz=function(a,b){return $APP.Sf($APP.tf.h($APP.$f(a,0,b),$APP.$f(a,b+1,$APP.F(a))))},hz=function(a,b){function c(g){return $APP.$f(g,0,2)}a=$APP.Sf(a);var d=c(b),e=ez(a,d,c),f=$APP.N.h(a,e);return $APP.Dd(f)&&$APP.G.h(d,c(f))?$APP.X.i(a,e,b):fz(a,e,b)},iz=function(a,b){function c(f){return $APP.$f(f,0,2)}a=$APP.Sf(a);b=c(b);var d=ez(a,b,c),e=$APP.N.h(a,d);$APP.gi($APP.K([a,
b,d,e]));return $APP.Dd(e)&&$APP.G.h(b,c(e))?gz(a,d):a},jz=function(a,b){function c(e){return $APP.$f(e,0,2)}a=$APP.Sf(a);b=c(b);var d=ez(a,b,c);a=$APP.N.h(a,d);return $APP.Dd(a)&&$APP.G.h(b,c(a))},kz=function(a){var b=dz(),c=0===b.length,d=c||$APP.Ka(b.hasOwnProperty(0))?b[0]=$APP.kl(!1):b[0],e=c||$APP.Ka(b.hasOwnProperty(1))?b[1]=$APP.gf(null):b[1],f=c||$APP.Ka(b.hasOwnProperty(2))?b[2]=function(){return $APP.hf(d,!0)}:b[2];return $APP.p(function(){var g=$APP.y(d);return $APP.p(g)?g:$APP.wd($APP.y(a))}())?
new $APP.S(null,3,5,$APP.V,[$APP.Oq,new $APP.m(null,1,[$APP.Zq,function(g){console.log(g);g.preventDefault();$APP.hf(a,g.target.elements.namefield.value);return $APP.hf(d,!1)}],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,5,[$APP.mq,$APP.Xq,$APP.lj,"namefield",$APP.br,!0,$APP.fq,$APP.y(a),$APP.Jr,function(g){$APP.hf(a,g.target.value);return $APP.hf(d,!1)}],null)],null)],null):new $APP.S(null,3,5,$APP.V,[$APP.qq,new $APP.m(null,1,[$APP.Qp,function(g){return $APP.G.h(g.detail,1)?$APP.hf(e,
window.setTimeout(f,1E3)):window.clearTimeout($APP.y(e))}],null),$APP.y(a)],null)},nz=function(){return new $APP.S(null,3,5,$APP.V,[lz,new $APP.m(null,1,[$APP.cl,$APP.p($APP.y(mz))?"visible":"hidden"],null),$APP.y(mz)],null)},tz=function(){var a=$APP.y(oz),b=$APP.Oe(a);a=$APP.N.h(b,$APP.yr);var c=$APP.N.h(b,$APP.xr);b=$APP.N.h(b,pz);return new $APP.S(null,3,5,$APP.V,[qz,new $APP.m(null,2,[$APP.nq,new $APP.m(null,2,[rz,c,sz,a],null),$APP.cl,$APP.p(b)?"visible":"hidden"],null),b],null)},vz=function(a,
b){return $APP.hf(mz,new $APP.S(null,6,5,$APP.V,[$APP.Oq,new $APP.m(null,1,[$APP.Zq,function(c){console.log(c);c.preventDefault();c=c.target.elements.valuefield.value;$APP.C(c)&&(b.g?b.g(c):b.call(null,c));return $APP.hf(mz,null)}],null),new $APP.S(null,2,5,$APP.V,[uz,a],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.lj,"valuefield",$APP.mq,"text",$APP.br,!0],null)],null),new $APP.S(null,3,5,$APP.V,[$APP.cr,new $APP.m(null,1,[$APP.Qp,function(){return $APP.hf(mz,null)}],null),"Cancel"],
null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,2,[$APP.mq,"submit",$APP.Gr,"Ok"],null)],null)],null))},wz=function(a){return $APP.hf(mz,new $APP.S(null,3,5,$APP.V,[uz,new $APP.S(null,2,5,$APP.V,[$APP.Cr,a],null),new $APP.S(null,3,5,$APP.V,[$APP.cr,new $APP.m(null,1,[$APP.Qp,function(){return $APP.hf(mz,null)}],null),"Ok"],null)],null))},Az=function(a,b){a.preventDefault();return $APP.hf(oz,new $APP.m(null,3,[$APP.yr,a.clientX,$APP.xr,a.clientY,pz,new $APP.S(null,2,5,$APP.V,[xz,new $APP.S(null,
3,5,$APP.V,[yz,new $APP.m(null,1,[$APP.Qp,function(){return $APP.Z.i(zz,$APP.Hj,b)}],null),"delete"],null)],null)],null))},Bz=function(a,b){var c=$APP.G,d=c.h,e=$APP.F(a),f=$APP.F(b);return d.call(c,$APP.$f(a,0,e<f?e:f),b)},Dz=function(a){return $APP.nf.h(function(b){return $APP.ne.h($APP.fj(b,"/",0),$APP.sm.g(a))},$APP.ne.h($APP.uf(Cz,$APP.K([$APP.sh($APP.ns.g(a))])),"Everything"))},Ez=function(){var a=$APP.y(zz);return $APP.Sa(function(b,c){return $APP.ip(b,c,$APP.Te)},$APP.Te,$APP.uf(Dz,$APP.K([$APP.sh(a)])))},
Hz=function(){return new $APP.S(null,2,5,$APP.V,[Fz,new $APP.S(null,3,5,$APP.V,[Gz,$APP.Rf,Ez()],null)],null)},Kz=function(a,b){a=[$APP.w.g(a),"$",$APP.w.g(b)].join("");b=new URL("editor",window.location);b.searchParams.append("schem",a);b.searchParams.append("db",Iz);b.searchParams.append("sync",Jz);return b},Mz=function(a,b,c,d){a.preventDefault();return $APP.hf(oz,new $APP.m(null,3,[$APP.yr,a.clientX,$APP.xr,a.clientY,pz,new $APP.S(null,3,5,$APP.V,[xz,new $APP.S(null,3,5,$APP.V,[yz,new $APP.m(null,
1,[$APP.Qp,function(){return window.open(Kz($APP.nd(c.split(":")),$APP.Eh(d)),c)}],null),"edit"],null),new $APP.S(null,3,5,$APP.V,[yz,new $APP.m(null,1,[$APP.Qp,function(){return $APP.Z.l(b,Lz,new $APP.S(null,2,5,$APP.V,[c,$APP.ns],null),$APP.Hj,$APP.K([d]))}],null),"delete"],null)],null)],null))},Rz=function(a){var b=$APP.y(Nz),c=$APP.N.h($APP.y(a),b);return new $APP.S(null,2,5,$APP.V,[Oz,new $APP.S(null,5,5,$APP.V,[$APP.bu,Pz,function(){return function f(e){return new $APP.ue(null,function(){for(var g=
e;;)if(g=$APP.C(g)){if($APP.Ed(g)){var h=$APP.oc(g),l=$APP.F(h),n=$APP.xe(l);return function(){for(var u=0;;)if(u<l){var x=$APP.qd(h,u),v=$APP.M(x,0,null);x=$APP.M(x,1,null);var B=(B=$APP.G.h($APP.y(Qz),new $APP.S(null,1,5,$APP.V,["Everything"],null)))?B:$APP.Ze($APP.Wn.h($APP.G,$APP.Me.h($APP.w,$APP.sf("/",$APP.y(Qz)))),Cz.g(x));$APP.p(B)&&(B=$APP.G.h($APP.xf(c,new $APP.S(null,3,5,$APP.V,[$APP.ns,v,$APP.mq],null)),"schematic")?$APP.Wt:$APP.Vt,v=new $APP.S(null,4,5,$APP.V,[new $APP.S(null,4,5,$APP.V,
[$APP.qq,new $APP.S(null,1,5,$APP.V,[B],null)," ",$APP.N.i(x,$APP.lj,v)],null),new $APP.S(null,4,5,$APP.V,[$APP.qq,new $APP.S(null,1,5,$APP.V,[B],null)," ",new $APP.S(null,2,5,$APP.V,[kz,$APP.ll(a,new $APP.S(null,4,5,$APP.V,[b,$APP.ns,v,$APP.lj],null))],null)],null),v,v],null),n.add(v));u+=1}else return!0}()?$APP.Ae($APP.Ce(n),f($APP.pc(g))):$APP.Ae($APP.Ce(n),null)}var q=$APP.D(g),r=$APP.M(q,0,null),t=$APP.M(q,1,null);if($APP.p(function(){var u=$APP.G.h($APP.y(Qz),new $APP.S(null,1,5,$APP.V,["Everything"],
null));return u?u:$APP.Ze($APP.Wn.h($APP.G,$APP.Me.h($APP.w,$APP.sf("/",$APP.y(Qz)))),Cz.g(t))}()))return q=$APP.G.h($APP.xf(c,new $APP.S(null,3,5,$APP.V,[$APP.ns,r,$APP.mq],null)),"schematic")?$APP.Wt:$APP.Vt,$APP.P(new $APP.S(null,4,5,$APP.V,[new $APP.S(null,4,5,$APP.V,[$APP.qq,new $APP.S(null,1,5,$APP.V,[q],null)," ",$APP.N.i(t,$APP.lj,r)],null),new $APP.S(null,4,5,$APP.V,[$APP.qq,new $APP.S(null,1,5,$APP.V,[q],null)," ",new $APP.S(null,2,5,$APP.V,[kz,$APP.ll(a,new $APP.S(null,4,5,$APP.V,[b,$APP.ns,
r,$APP.lj],null))],null)],null),r,r],null),f($APP.Pc(g)));g=$APP.Pc(g)}else return null},null,null)}($APP.ns.g(c))}(),function(d){$APP.gi($APP.K([c,d]));return $APP.G.h($APP.xf(c,new $APP.S(null,3,5,$APP.V,[$APP.ns,d,$APP.mq],null)),"schematic")?function(){return window.open(Kz($APP.nd(b.split(":")),$APP.Eh(d)),b)}:null},function(d){$APP.gi($APP.K(["add ctxclk"]));return function(e){return Mz(e,a,b,d)}}],null)],null)},Vz=function(){return new $APP.S(null,2,5,$APP.V,[Sz,new $APP.S(null,3,5,$APP.V,
[$APP.Kr,new $APP.S(null,2,5,$APP.V,[$APP.Rq,"Workspace properties"],null),new $APP.S(null,6,5,$APP.V,[Tz,new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,1,[$APP.hq,"dbname"],null),"Name"],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.el,"dbname",$APP.lj,"db",$APP.fq,Iz],null)],null),new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,1,[$APP.hq,"dbsync"],null),"URL"],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.el,"dbsync",$APP.lj,"sync",$APP.fq,Jz],null)],
null),new $APP.S(null,4,5,$APP.V,[Uz,new $APP.m(null,1,[$APP.mq,"submit"],null),new $APP.S(null,1,5,$APP.V,[$APP.Xt],null),"Open"],null)],null)],null)],null)},$z=function(a){$APP.gi($APP.K([$APP.y(a)]));var b=$APP.Zr.g($APP.y(a)),c=$APP.M(b,0,null),d=$APP.M(b,1,null),e=2+c,f=2+d;$APP.gi($APP.K([e,f]));return new $APP.S(null,2,5,$APP.V,[Wz,new $APP.S(null,2,5,$APP.V,[Xz,$APP.Mh(function(){return function l(h){return new $APP.ue(null,function(){for(;;){var n=$APP.C(h);if(n){var q=n;if($APP.Ed(q)){var r=
$APP.oc(q),t=$APP.F(r),u=$APP.xe(t);return function(){for(var v=0;;)if(v<t){var B=$APP.qd(r,v);$APP.Be(u,new $APP.S(null,3,5,$APP.V,[Yz,new $APP.m(null,1,[$APP.oj,B],null),$APP.Mh(function(){return function(I,J,O,Q,T,U,W,na,Y,oa,qa,Oa){return function ib($a){return new $APP.ue(null,function(Lb,ab,Mb,dd,sc,sb,Ad,zc,yb,dc,Qc,tb){return function(){for(;;){var Eb=$APP.C($a);if(Eb){var ec=Eb;if($APP.Ed(ec)){var Od=$APP.oc(ec),jh=$APP.F(Od),If=$APP.xe(jh);return function(){for(var Jf=0;;)if(Jf<jh){var $e=
$APP.qd(Od,Jf),kh=function(lh,mh,Db,xt,yt,zt,Ml,Qg,kf){return function(Ie){return $APP.p(Ie.target.checked)?$APP.Z.l(a,$APP.jp,$APP.kq,hz,$APP.K([new $APP.S(null,3,5,$APP.V,[Db,kf,"#"],null)])):$APP.Z.l(a,$APP.jp,$APP.kq,iz,$APP.K([new $APP.S(null,3,5,$APP.V,[Db,kf,"#"],null)]))}}(Jf,Lb,$e,Od,jh,If,ec,Eb,ab,Mb,dd,sc,sb,Ad,zc,yb,dc,Qc,tb);$APP.Be(If,new $APP.S(null,3,5,$APP.V,[Zz,new $APP.m(null,1,[$APP.oj,$e],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.mq,"checkbox",$APP.mr,
jz($APP.kq.g($APP.y(a)),new $APP.S(null,2,5,$APP.V,[$e,ab],null)),$APP.Ep,kh],null)],null)],null));Jf+=1}else return!0}()?$APP.Ae($APP.Ce(If),ib($APP.pc(ec))):$APP.Ae($APP.Ce(If),null)}var Pd=$APP.D(ec),li=function(Jf,$e,kh,lh,mh){return function(Db){return $APP.p(Db.target.checked)?$APP.Z.l(a,$APP.jp,$APP.kq,hz,$APP.K([new $APP.S(null,3,5,$APP.V,[$e,mh,"#"],null)])):$APP.Z.l(a,$APP.jp,$APP.kq,iz,$APP.K([new $APP.S(null,3,5,$APP.V,[$e,mh,"#"],null)]))}}(Lb,Pd,ec,Eb,ab,Mb,dd,sc,sb,Ad,zc,yb,dc,Qc,tb);
return $APP.P(new $APP.S(null,3,5,$APP.V,[Zz,new $APP.m(null,1,[$APP.oj,Pd],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.mq,"checkbox",$APP.mr,jz($APP.kq.g($APP.y(a)),new $APP.S(null,2,5,$APP.V,[Pd,ab],null)),$APP.Ep,li],null)],null)],null),ib($APP.Pc(ec)))}return null}}}(I,J,O,Q,T,U,W,na,Y,oa,qa,Oa),null,null)}}(v,B,r,t,u,q,n,b,c,d,e,f)($APP.Kh(0,e))}())],null));v+=1}else return!0}()?$APP.Ae($APP.Ce(u),l($APP.pc(q))):$APP.Ae($APP.Ce(u),null)}var x=$APP.D(q);return $APP.P(new $APP.S(null,
3,5,$APP.V,[Yz,new $APP.m(null,1,[$APP.oj,x],null),$APP.Mh(function(){return function(v,B,I,J,O,Q,T,U){return function Y(na){return new $APP.ue(null,function(){for(;;){var oa=$APP.C(na);if(oa){var qa=oa;if($APP.Ed(qa)){var Oa=$APP.oc(qa),Ta=$APP.F(Oa),$a=$APP.xe(Ta);return function(){for(var ab=0;;)if(ab<Ta){var Mb=$APP.qd(Oa,ab),dd=function(sc,sb,Ad,zc,yb,dc,Qc,tb){return function(Eb){return $APP.p(Eb.target.checked)?$APP.Z.l(a,$APP.jp,$APP.kq,hz,$APP.K([new $APP.S(null,3,5,$APP.V,[sb,tb,"#"],null)])):
$APP.Z.l(a,$APP.jp,$APP.kq,iz,$APP.K([new $APP.S(null,3,5,$APP.V,[sb,tb,"#"],null)]))}}(ab,Mb,Oa,Ta,$a,qa,oa,v,B,I,J,O,Q,T,U);$APP.Be($a,new $APP.S(null,3,5,$APP.V,[Zz,new $APP.m(null,1,[$APP.oj,Mb],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.mq,"checkbox",$APP.mr,jz($APP.kq.g($APP.y(a)),new $APP.S(null,2,5,$APP.V,[Mb,v],null)),$APP.Ep,dd],null)],null)],null));ab+=1}else return!0}()?$APP.Ae($APP.Ce($a),Y($APP.pc(qa))):$APP.Ae($APP.Ce($a),null)}var ib=$APP.D(qa),Lb=function(ab,
Mb,dd,sc){return function(sb){return $APP.p(sb.target.checked)?$APP.Z.l(a,$APP.jp,$APP.kq,hz,$APP.K([new $APP.S(null,3,5,$APP.V,[ab,sc,"#"],null)])):$APP.Z.l(a,$APP.jp,$APP.kq,iz,$APP.K([new $APP.S(null,3,5,$APP.V,[ab,sc,"#"],null)]))}}(ib,qa,oa,v,B,I,J,O,Q,T,U);return $APP.P(new $APP.S(null,3,5,$APP.V,[Zz,new $APP.m(null,1,[$APP.oj,ib],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.mq,"checkbox",$APP.mr,jz($APP.kq.g($APP.y(a)),new $APP.S(null,2,5,$APP.V,[ib,v],null)),$APP.Ep,Lb],
null)],null)],null),Y($APP.Pc(qa)))}return null}},null,null)}}(x,q,n,b,c,d,e,f)($APP.Kh(0,e))}())],null),l($APP.Pc(q)))}return null}},null,null)}($APP.Kh(0,f))}())],null)],null)},aA=function(a){$APP.gi($APP.K([$APP.y(a)]));return new $APP.S(null,5,5,$APP.V,[$APP.Zp,new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,2,[$APP.hq,"bgwidth",$APP.Aq,"Width of the background tile"],null),"Background width"],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,4,[$APP.el,"bgwidth",$APP.mq,"number",
$APP.fq,$APP.N.i($APP.Zr.g($APP.y(a)),0,1),$APP.Ep,$APP.ka(function(b){return $APP.Z.l(a,$APP.jp,$APP.Zr,$APP.cf($APP.X,new $APP.S(null,2,5,$APP.V,[0,0],null)),$APP.K([0,parseInt(b.target.value)]))})],null)],null),new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,2,[$APP.hq,"bgheight",$APP.Aq,"Width of the background tile"],null),"Background height"],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,4,[$APP.el,"bgheigt",$APP.mq,"number",$APP.fq,$APP.N.i($APP.Zr.g($APP.y(a)),1,1),$APP.Ep,
$APP.ka(function(b){return $APP.Z.l(a,$APP.jp,$APP.Zr,$APP.cf($APP.X,new $APP.S(null,2,5,$APP.V,[0,0],null)),$APP.K([1,parseInt(b.target.value)]))})],null)],null)],null)},bA=function(a){return new $APP.S(null,2,5,$APP.V,[$APP.Zp,function(){return function d(c){return new $APP.ue(null,function(){for(;;){var e=$APP.C(c);if(e){var f=e;if($APP.Ed(f)){var g=$APP.oc(f),h=$APP.F(g),l=$APP.xe(h);return function(){for(var u=0;;)if(u<h){var x=$APP.qd(g,u),v=$APP.M(x,0,null),B=$APP.M(x,1,null),I=$APP.M(x,2,
null);x=$APP.ka(function(J,O,Q,T){return function(U){return $APP.Z.l(a,$APP.jp,$APP.kq,hz,$APP.K([new $APP.S(null,3,5,$APP.V,[Q,T,U.target.value],null)]))}}(u,x,v,B,I,g,h,l,f,e));$APP.Be(l,new $APP.S(null,4,5,$APP.V,[$APP.Zp,new $APP.m(null,1,[$APP.oj,new $APP.S(null,2,5,$APP.V,[v,B],null)],null),new $APP.S(null,5,5,$APP.V,[$APP.lr,new $APP.m(null,2,[$APP.hq,["port",$APP.w.g(v),":",$APP.w.g(B)].join(""),$APP.Aq,"Port name"],null),v,"/",B],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,
4,[$APP.el,["port",$APP.w.g(v),":",$APP.w.g(B)].join(""),$APP.mq,"text",$APP.fq,I,$APP.Ep,x],null)],null)],null));u+=1}else return!0}()?$APP.Ae($APP.Ce(l),d($APP.pc(f))):$APP.Ae($APP.Ce(l),null)}var n=$APP.D(f),q=$APP.M(n,0,null),r=$APP.M(n,1,null),t=$APP.M(n,2,null);n=$APP.ka(function(u,x,v){return function(B){return $APP.Z.l(a,$APP.jp,$APP.kq,hz,$APP.K([new $APP.S(null,3,5,$APP.V,[x,v,B.target.value],null)]))}}(n,q,r,t,f,e));return $APP.P(new $APP.S(null,4,5,$APP.V,[$APP.Zp,new $APP.m(null,1,[$APP.oj,
new $APP.S(null,2,5,$APP.V,[q,r],null)],null),new $APP.S(null,5,5,$APP.V,[$APP.lr,new $APP.m(null,2,[$APP.hq,["port",$APP.w.g(q),":",$APP.w.g(r)].join(""),$APP.Aq,"Port name"],null),q,"/",r],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,4,[$APP.el,["port",$APP.w.g(q),":",$APP.w.g(r)].join(""),$APP.mq,"text",$APP.fq,t,$APP.Ep,n],null)],null)],null),d($APP.Pc(f)))}return null}},null,null)}($APP.kq.g($APP.y(a)))}()],null)},cA=function(a){var b=$APP.y(Nz),c=$APP.ll(a,new $APP.S(null,1,5,$APP.V,
[b],null));return $APP.p(b)?new $APP.S(null,2,5,$APP.V,[$APP.Zp,new $APP.S(null,7,5,$APP.V,[$APP.Mp,new $APP.S(null,2,5,$APP.V,[aA,c],null),new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,2,[$APP.hq,"ports",$APP.Aq,"pattern for the device ports"],null),"ports"],null),new $APP.S(null,2,5,$APP.V,[$z,c],null),new $APP.S(null,2,5,$APP.V,[bA,c],null),new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,2,[$APP.hq,"symurl",$APP.Aq,"image url for this component"],null),"url"],null),new $APP.S(null,2,
5,$APP.V,[$APP.jr,new $APP.m(null,4,[$APP.el,"symurl",$APP.mq,"text",$APP.fq,$APP.xf($APP.y(a),new $APP.S(null,2,5,$APP.V,[b,$APP.ss],null)),$APP.Jr,function(d){return $APP.Z.u(a,$APP.ip,new $APP.S(null,2,5,$APP.V,[b,$APP.ss],null),d.target.value)}],null)],null)],null)],null):null},gA=function(a){var b=$APP.ll(a,new $APP.S(null,3,5,$APP.V,[$APP.y(Nz),$APP.ns,$APP.Xh.g($APP.y(Pz))],null));cz($APP.K([$APP.y(b)]));return new $APP.S(null,3,5,$APP.V,[$APP.Mp,$APP.G.h($APP.mq.g($APP.y(b)),"spice")?new $APP.S(null,
5,5,$APP.V,[$APP.Zp,new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,1,[$APP.hq,"reftempl"],null),"Reference template"],null),new $APP.S(null,2,5,$APP.V,[dA,new $APP.m(null,3,[$APP.el,"reftempl",$APP.Gr,eA.h($APP.y(b),"X{name} {ports} {properties}"),$APP.Ep,function(c){return $APP.Z.u(b,$APP.X,eA,c.target.value)}],null)],null),new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,1,[$APP.hq,"decltempl"],null),"Declaration template"],null),new $APP.S(null,2,5,$APP.V,[dA,new $APP.m(null,3,[$APP.el,
"decltempl",$APP.Gr,fA.g($APP.y(b)),$APP.Ep,function(c){return $APP.Z.u(b,$APP.X,fA,c.target.value)}],null)],null)],null):$APP.p(function(){var c=$APP.y(Nz);return $APP.p(c)?$APP.y(Pz):c}())?new $APP.S(null,2,5,$APP.V,[$APP.Zp,new $APP.S(null,3,5,$APP.V,[$APP.wr,new $APP.m(null,2,[$APP.lq,Kz($APP.nd($APP.y(Nz).split(":")),$APP.Eh($APP.y(Pz))),$APP.Sp,$APP.y(Nz)],null),"Edit"],null)],null):null,$APP.p(function(){var c=$APP.y(Nz);return $APP.p(c)?$APP.y(Pz):c}())?new $APP.S(null,3,5,$APP.V,[$APP.Zp,
new $APP.S(null,3,5,$APP.V,[$APP.lr,new $APP.m(null,1,[$APP.hq,"categories"],null),"Categories"],null),new $APP.S(null,2,5,$APP.V,[$APP.jr,new $APP.m(null,3,[$APP.el,"categories",$APP.Gr,$APP.Me.h($APP.w,$APP.sf(", ",Cz.g($APP.y(b)))),$APP.Ep,function(c){return $APP.Z.u(b,$APP.X,Cz,$APP.fj(c.target.value,/, /,-1))}],null)],null)],null):null],null)},pA=function(){return new $APP.S(null,3,5,$APP.V,[$APP.Zp,new $APP.S(null,4,5,$APP.V,[hA,new $APP.S(null,3,5,$APP.V,[iA,new $APP.S(null,4,5,$APP.V,[$APP.cr,
new $APP.m(null,2,[$APP.Qp,function(){return vz("Enter the name of the new interface",function(a){return $APP.Z.u(zz,$APP.X,["models:",$APP.w.g(a)].join(""),new $APP.m(null,1,[$APP.lj,a],null))})},jA,null==$APP.y(Qz)],null),new $APP.S(null,1,5,$APP.V,[$APP.Zt],null),"Add interface"],null),new $APP.S(null,3,5,$APP.V,[kA,new $APP.S(null,4,5,$APP.V,[$APP.cr,new $APP.m(null,2,[$APP.Qp,function(){return vz("Enter the name of the new schematic",function(a){return $APP.Z.u(zz,$APP.ip,new $APP.S(null,3,5,
$APP.V,[$APP.y(Nz),$APP.ns,$APP.Xh.g(a)],null),new $APP.m(null,2,[$APP.lj,a,$APP.mq,"schematic"],null))})},jA,null==$APP.y(Qz)||null==$APP.y(Nz)],null),new $APP.S(null,1,5,$APP.V,[$APP.Yt],null),"Add schematic"],null),new $APP.S(null,3,5,$APP.V,[$APP.Kr,new $APP.S(null,1,5,$APP.V,[lA],null),new $APP.S(null,4,5,$APP.V,[$APP.cr,new $APP.m(null,2,[$APP.Qp,function(){return vz("Enter the name of the new SPICE model",function(a){return $APP.Z.u(zz,$APP.ip,new $APP.S(null,3,5,$APP.V,[$APP.y(Nz),$APP.ns,
$APP.Xh.g(a)],null),new $APP.m(null,2,[$APP.lj,a,$APP.mq,"spice"],null))})},jA,null==$APP.y(Qz)||null==$APP.y(Nz)],null),new $APP.S(null,1,5,$APP.V,[$APP.Yt],null),"Add SPICE model"],null)],null)],null)],null),new $APP.S(null,3,5,$APP.V,[mA,"Interface ",function(){var a=$APP.y(Nz);return $APP.p(a)?$APP.nd(a.split(":")):null}()],null),new $APP.S(null,2,5,$APP.V,[Rz,zz],null)],null),new $APP.S(null,3,5,$APP.V,[nA,new $APP.S(null,2,5,$APP.V,[oA,new $APP.S(null,2,5,$APP.V,[gA,zz],null)],null),new $APP.S(null,
2,5,$APP.V,[cA,zz],null)],null)],null)},tA=function(){return new $APP.S(null,5,5,$APP.V,[$APP.Zp,new $APP.S(null,4,5,$APP.V,[qA,new $APP.S(null,3,5,$APP.V,[rA,new $APP.S(null,2,5,$APP.V,[$APP.es,"Libraries"],null),$APP.p($APP.y(sA))?new $APP.S(null,3,5,$APP.V,[$APP.bs,new $APP.m(null,1,[$APP.Aq,"saving changes"],null),new $APP.S(null,1,5,$APP.V,[$APP.$t],null)],null):new $APP.S(null,3,5,$APP.V,[$APP.rq,new $APP.m(null,1,[$APP.Aq,"changes saved"],null),new $APP.S(null,1,5,$APP.V,[$APP.au],null)],null)],
null),new $APP.S(null,1,5,$APP.V,[Hz],null),new $APP.S(null,1,5,$APP.V,[Vz],null)],null),new $APP.S(null,1,5,$APP.V,[pA],null),new $APP.S(null,1,5,$APP.V,[tz],null),new $APP.S(null,1,5,$APP.V,[nz],null)],null)},vA=function(){if($APP.C(Jz)){var a=uA.sync(Jz,{live:!0});a.on("paused",function(){return $APP.hf(sA,!1)});a.on("active",function(){return $APP.hf(sA,!0)});a.on("denied",function(){return wz(["Permission denied synchronising to ",$APP.w.g(Jz),", changes are saved locally"].join(""))});a.on("error",
function(){return wz(["Error synchronising to ",$APP.w.g(Jz),", changes are saved locally"].join(""))})}},wA=function(){window.name="libman";document.onclick=function(){return $APP.Z.u(oz,$APP.X,pz,null)};localStorage.setItem("db",Iz);localStorage.setItem("sync",Jz);vA();return $APP.ql(new $APP.S(null,1,5,$APP.V,[tA],null),document.getElementById("mosaic_libman"))},Lz=function Lz(a){switch(arguments.length){case 3:return Lz.i(arguments[0],arguments[1],arguments[2]);case 4:return Lz.u(arguments[0],
arguments[1],arguments[2],arguments[3]);case 5:return Lz.F(arguments[0],arguments[1],arguments[2],arguments[3],arguments[4]);case 6:return Lz.aa(arguments[0],arguments[1],arguments[2],arguments[3],arguments[4],arguments[5]);default:for(var c=[],d=arguments.length,e=0;;)if(e<d)c.push(arguments[e]),e+=1;else break;c=new $APP.A(c.slice(6),0,null);return Lz.l(arguments[0],arguments[1],arguments[2],arguments[3],arguments[4],arguments[5],c)}};
Lz.i=function(a,b,c){var d=$APP.C(b);b=$APP.D(d);if(d=$APP.E(d))a=$APP.X.i(a,b,Lz.i($APP.N.h(a,b),d,c));else{d=$APP.X.i;var e=$APP.N.h(a,b);c=c.g?c.g(e):c.call(null,e);a=d.call($APP.X,a,b,c)}return a};Lz.u=function(a,b,c,d){var e=$APP.C(b);b=$APP.D(e);if(e=$APP.E(e))a=$APP.X.i(a,b,Lz.u($APP.N.h(a,b),e,c,d));else{e=$APP.X.i;var f=$APP.N.h(a,b);c=c.h?c.h(f,d):c.call(null,f,d);a=e.call($APP.X,a,b,c)}return a};
Lz.F=function(a,b,c,d,e){var f=$APP.C(b);b=$APP.D(f);if(f=$APP.E(f))a=$APP.X.i(a,b,Lz.F($APP.N.h(a,b),f,c,d,e));else{f=$APP.X.i;var g=$APP.N.h(a,b);c=c.i?c.i(g,d,e):c.call(null,g,d,e);a=f.call($APP.X,a,b,c)}return a};Lz.aa=function(a,b,c,d,e,f){var g=$APP.C(b);b=$APP.D(g);if(g=$APP.E(g))a=$APP.X.i(a,b,Lz.aa($APP.N.h(a,b),g,c,d,e,f));else{g=$APP.X.i;var h=$APP.N.h(a,b);c=c.u?c.u(h,d,e,f):c.call(null,h,d,e,f);a=g.call($APP.X,a,b,c)}return a};
Lz.l=function(a,b,c,d,e,f,g){var h=$APP.C(b);b=$APP.D(h);return(h=$APP.E(h))?$APP.X.i(a,b,$APP.Me.l(Lz,$APP.N.h(a,b),h,c,d,$APP.K([e,f,g]))):$APP.X.i(a,b,$APP.Me.l(c,$APP.N.h(a,b),d,e,f,$APP.K([g])))};Lz.D=function(a){var b=$APP.D(a),c=$APP.E(a);a=$APP.D(c);var d=$APP.E(c);c=$APP.D(d);var e=$APP.E(d);d=$APP.D(e);var f=$APP.E(e);e=$APP.D(f);var g=$APP.E(f);f=$APP.D(g);g=$APP.E(g);return this.l(b,a,c,d,e,f,g)};Lz.C=6;
var Wz=new $APP.R(null,"table","table",-564943036),sz=new $APP.R(null,"left","left",-399115937),jA=new $APP.R(null,"disabled","disabled",-1529784218),yz=new $APP.R(null,"li","li",723558921),qA=new $APP.R(null,"div.libraries","div.libraries",704668226),nA=new $APP.R(null,"div.proppane","div.proppane",-1332639267),Sz=new $APP.R(null,"div.dbprops","div.dbprops",-1556934616),xz=new $APP.R(null,"ul","ul",-1349521403),fA=new $APP.R(null,"decltempl","decltempl",-878889161),rA=new $APP.R(null,"div.libhead",
"div.libhead",1365032704),Yz=new $APP.R(null,"tr","tr",-1424774646),Zz=new $APP.R(null,"td","td",1479933353),uz=new $APP.R(null,"div","div",1057191632),lA=new $APP.R(null,"summary.button","summary.button",-1314921300),rz=new $APP.R(null,"top","top",-1856271961),Tz=new $APP.R(null,"form.properties","form.properties",-224476671),oA=new $APP.R(null,"div.preview","div.preview",-1882273840),xA=new $APP.R(null,"open","open",-1763596448),qz=new $APP.R(null,"div.contextmenu.window","div.contextmenu.window",
729411641),Cz=new $APP.R(null,"categories","categories",178386610),eA=new $APP.R(null,"reftempl","reftempl",2007300758),kA=new $APP.R(null,"div.buttongroup.primary","div.buttongroup.primary",1038300842),Uz=new $APP.R(null,"button.primary","button.primary",-486456892),hA=new $APP.R(null,"div.schsel","div.schsel",-1209283060),Xz=new $APP.R(null,"tbody","tbody",-80678300),lz=new $APP.R(null,"div.modal.window","div.modal.window",-1398751510),Fz=new $APP.R(null,"div.cellsel","div.cellsel",-277762551),
Oz=new $APP.R(null,"div.schematics","div.schematics",908542795),yA=new $APP.R(null,"div.detailbody","div.detailbody",-1679946259),zA=new $APP.R(null,"on-toggle","on-toggle",-695538774),pz=new $APP.R(null,"body","body",-2049205669),iA=new $APP.R(null,"div.addbuttons","div.addbuttons",-579122699),mA=new $APP.R(null,"h2","h2",-372662728),dA=new $APP.R(null,"textarea","textarea",-650375824),AA=new $APP.R(null,"details.tree","details.tree",-1643409151);var BA=new URLSearchParams(window.location.search),Iz;var CA=BA.get("db");if($APP.p(CA))Iz=CA;else{var DA=localStorage.getItem("db");Iz=$APP.p(DA)?DA:"schematics"}var EA=$APP.p(window.dburl)?(new URL(Iz,window.dburl)).href:Iz,Jz;var FA=BA.get("sync");if($APP.p(FA))Jz=FA;else{var GA=localStorage.getItem("sync");Jz=$APP.p(GA)?GA:"https://c6be5bcc-59a8-492d-91fd-59acc17fef02-bluemix.cloudantnosqldb.appdomain.cloud/schematics"}
var uA=new $APP.Es(EA),zz=$APP.xm(uA,"models",$APP.kl($APP.Te)),HA=$APP.xm(uA,"snapshots",$APP.kl($APP.Te));$APP.vm(uA,$APP.K([zz,HA]));
var Qz=$APP.kl($APP.Rf),Nz=$APP.kl(null),Pz=$APP.kl(null),sA=$APP.kl(!1),mz=$APP.kl(null),oz=$APP.kl(new $APP.m(null,3,[$APP.yr,0,$APP.xr,0,pz,null],null)),Gz=function Gz(a,b){return new $APP.S(null,3,5,$APP.V,[$APP.Zp,$APP.Mh(function(){return function f(e){return new $APP.ue(null,function(){for(var g=e;;){var h=$APP.C(g);if(h){var l=h;if($APP.Ed(l)){var n=$APP.oc(l),q=$APP.F(n),r=$APP.xe(q);return function(){for(var B=0;;)if(B<q){var I=$APP.qd(n,B),J=$APP.M(I,0,null),O=$APP.M(I,1,null);if($APP.C(J)&&
$APP.C(O)){var Q=$APP.ne.h(a,J);$APP.Be(r,new $APP.S(null,4,5,$APP.V,[AA,new $APP.m(null,4,[$APP.oj,Q,$APP.cl,Q,xA,Bz($APP.y(Qz),Q),zA,function(T,U,W){return function(na){return $APP.p(na.target.open)?(na.stopPropagation(),$APP.hf(Nz,null),$APP.hf(Pz,null),$APP.hf(Qz,W)):null}}(B,g,Q,I,J,O,n,q,r,l,h)],null),new $APP.S(null,2,5,$APP.V,[$APP.Rq,J],null),new $APP.S(null,2,5,$APP.V,[yA,new $APP.S(null,3,5,$APP.V,[Gz,Q,O],null)],null)],null))}B+=1}else return!0}()?$APP.Ae($APP.Ce(r),f($APP.pc(l))):$APP.Ae($APP.Ce(r),
null)}var t=$APP.D(l),u=$APP.M(t,0,null),x=$APP.M(t,1,null);if($APP.C(u)&&$APP.C(x)){var v=$APP.ne.h(a,u);return $APP.P(new $APP.S(null,4,5,$APP.V,[AA,new $APP.m(null,4,[$APP.oj,v,$APP.cl,v,xA,Bz($APP.y(Qz),v),zA,function(B,I){return function(J){return $APP.p(J.target.open)?(J.stopPropagation(),$APP.hf(Nz,null),$APP.hf(Pz,null),$APP.hf(Qz,I)):null}}(g,v,t,u,x,l,h)],null),new $APP.S(null,2,5,$APP.V,[$APP.Rq,u],null),new $APP.S(null,2,5,$APP.V,[yA,new $APP.S(null,3,5,$APP.V,[Gz,v,x],null)],null)],null),
f($APP.Pc(l)))}g=$APP.Pc(l)}else return null}},null,null)}(b)}()),new $APP.S(null,5,5,$APP.V,[$APP.bu,Nz,$APP.Mh(function(){return function f(e){return new $APP.ue(null,function(){for(var g=e;;)if(g=$APP.C(g)){if($APP.Ed(g)){var h=$APP.oc(g),l=$APP.F(h),n=$APP.xe(l);a:for(var q=0;;)if(q<l){var r=$APP.qd(h,q),t=$APP.M(r,0,null);r=$APP.M(r,1,null);if($APP.wd(r)){r=$APP.N.h($APP.y(zz),t);var u=$APP.nd(t.split(":"));t=new $APP.S(null,4,5,$APP.V,[$APP.N.i(r,$APP.lj,u),new $APP.S(null,2,5,$APP.V,[kz,$APP.ll(zz,
new $APP.S(null,2,5,$APP.V,[t,$APP.lj],null))],null),t,u],null);n.add(t)}q+=1}else{h=!0;break a}return h?$APP.Ae($APP.Ce(n),f($APP.pc(g))):$APP.Ae($APP.Ce(n),null)}h=$APP.D(g);n=$APP.M(h,0,null);h=$APP.M(h,1,null);if($APP.wd(h))return h=$APP.N.h($APP.y(zz),n),l=$APP.nd(n.split(":")),$APP.P(new $APP.S(null,4,5,$APP.V,[$APP.N.i(h,$APP.lj,l),new $APP.S(null,2,5,$APP.V,[kz,$APP.ll(zz,new $APP.S(null,2,5,$APP.V,[n,$APP.lj],null))],null),n,l],null),f($APP.Pc(g)));g=$APP.Pc(g)}else return null},null,null)}(b)}()),
null,function(d){return function(e){return Az(e,d)}}],null)],null)};$APP.ja("nyancad.mosaic.libman.init",wA);try{wA()}catch(a){throw console.error("An error occurred when calling (nyancad.mosaic.libman/init)"),a;};
}).call(this);