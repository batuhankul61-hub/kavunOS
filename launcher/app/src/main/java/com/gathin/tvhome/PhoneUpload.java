package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * "Add from phone" (build with phoneUpload=true): starts {@link PhoneUploadServer} with the collection behind
 * it and makes the QR code that opens its page. The Wallpapers screen owns one while its panel is open.
 */
final class PhoneUpload {

    private final Context context;
    private final WallpaperLibrary library;
    private final Runnable changed;
    private PhoneUploadServer server;

    /** @param changed run on the server thread after every change the phone makes */
    PhoneUpload(Context context, Runnable changed) {
        this.context = context;
        this.library = new WallpaperLibrary(context);
        this.changed = changed;
    }

    /** The page URL, or null when the TV has no network address or the port cannot be opened. */
    String start() {
        InetAddress address = localAddress();
        File folder = library.folder();
        if (address == null || folder == null) return null;
        server = new PhoneUploadServer(folder, page(), new PhoneUploadServer.Library() {
            @Override
            public String list() {
                Set<String> hidden = library.hidden();
                StringBuilder json = new StringBuilder("[");
                for (File image : WallpaperRotation.images(library.folder())) {
                    if (json.length() > 1) json.append(',');
                    json.append("{\"n\":\"").append(image.getName()).append("\",\"h\":")
                            .append(hidden.contains(image.getName())).append('}');
                }
                return json.append(']').toString();
            }

            @Override
            public byte[] thumb(String name) {
                File image = WallpaperRotation.find(WallpaperRotation.images(library.folder()), name);
                return image == null ? null : jpeg(image, 360);
            }

            @Override
            public boolean setHidden(String name, boolean hidden) {
                return library.setHidden(name, hidden);
            }

            @Override
            public boolean delete(String name) {
                return WallpaperRotation.find(WallpaperRotation.images(library.folder()), name) != null
                        && library.delete(name);
            }

            @Override
            public void changed() {
                Config.wallpaperChanged();
                changed.run();
            }
        });
        try {
            return server.start(address);
        } catch (IOException e) {
            server = null;
            return null;
        }
    }

    void stop() {
        if (server != null) server.stop();
        server = null;
    }

    static Bitmap qr(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            return null;
        }
    }

    /** The TV's IPv4 address on its active network (Wi-Fi or Ethernet); null when offline. */
    private InetAddress localAddress() {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network network = cm == null ? null : cm.getActiveNetwork();
        LinkProperties link = network == null ? null : cm.getLinkProperties(network);
        if (link == null) return null;
        for (LinkAddress la : link.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) return la.getAddress();
        }
        return null;
    }

    private static byte[] jpeg(File image, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= width) opts.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(image.getPath(), opts);
        if (bitmap == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    /**
     * The phone page: a button that sends photos one by one (each shrunk in the browser first) with a
     * progress bar, then the TV's pictures as thumbnails, never their names; a tap leaves one out of the
     * rotation, puts it back or deletes it.
     */
    private String page() {
        List<String[]> strings = java.util.Arrays.asList(
                new String[]{"pick", context.getString(R.string.phone_upload_pick)},
                new String[]{"sending", context.getString(R.string.phone_upload_sending)},
                new String[]{"done", context.getString(R.string.phone_upload_done)},
                new String[]{"failed", context.getString(R.string.phone_upload_failed)},
                new String[]{"full", context.getString(R.string.phone_upload_full)},
                new String[]{"onTv", context.getString(R.string.phone_page_on_tv)},
                new String[]{"hide", context.getString(R.string.wallpaper_hide)},
                new String[]{"unhide", context.getString(R.string.wallpaper_unhide)},
                new String[]{"del", context.getString(R.string.wallpaper_delete)},
                new String[]{"delAsk", context.getString(R.string.wallpaper_delete_confirm)},
                new String[]{"cancel", context.getString(android.R.string.cancel)},
                new String[]{"hidden", context.getString(R.string.wallpaper_hidden_badge)});
        StringBuilder s = new StringBuilder("{");
        for (String[] pair : strings) {
            if (s.length() > 1) s.append(',');
            s.append(pair[0]).append(":'").append(js(pair[1])).append('\'');
        }
        s.append('}');
        String title = html(context.getString(R.string.phone_upload_page_title));
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<meta name=theme-color content='#0d0f12'><title>" + title + "</title><style>"
                + ":root{color-scheme:dark}*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
                + "body{margin:0;background:#0d0f12;color:#f2f2f2;font:16px/1.4 -apple-system,system-ui,sans-serif;"
                + "padding:28px 18px calc(28px + env(safe-area-inset-bottom))}"
                + "h1{font-size:26px;font-weight:700;letter-spacing:-.02em;margin:0 0 18px}"
                + "h2{font-size:15px;font-weight:600;color:#9aa0a6;margin:30px 0 12px;letter-spacing:.02em}"
                + ".pick{display:block;text-align:center;background:#fff;color:#000;padding:16px;border-radius:14px;"
                + "font-weight:600;font-size:17px}input{display:none}"
                + ".bar{height:6px;background:#23262b;border-radius:3px;margin-top:16px;overflow:hidden;opacity:0;transition:opacity .3s}"
                + ".bar i{display:block;height:100%;width:0;background:#4dae6d;transition:width .3s}"
                + "#s{min-height:1.4em;margin-top:10px;color:#c7c9cc;font-size:15px}"
                + ".grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}"
                + ".t{position:relative;aspect-ratio:16/9;border-radius:10px;overflow:hidden;background:#1b1e22}"
                + ".t img{width:100%;height:100%;object-fit:cover;display:block}.t.h img{opacity:.35}"
                + ".t span{position:absolute;left:6px;bottom:6px;background:rgba(0,0,0,.7);font-size:11px;padding:2px 7px;border-radius:8px}"
                + ".sheet{position:fixed;inset:0;background:rgba(0,0,0,.5);display:none;align-items:flex-end}"
                + ".sheet.o{display:flex}.card{width:100%;background:#1c1e22;border-radius:18px 18px 0 0;"
                + "padding:10px 12px calc(12px + env(safe-area-inset-bottom))}"
                + ".card button{display:block;width:100%;background:none;border:0;color:#fff;font:inherit;font-size:17px;"
                + "padding:16px;border-radius:12px;text-align:center}.card button:active{background:#2a2d32}"
                + ".card .red{color:#ff5b55}.card .dim{color:#9aa0a6}</style></head><body>"
                + "<h1>" + title + "</h1>"
                + "<label class=pick><span id=pl></span><input id=f type=file accept='image/*' multiple></label>"
                + "<div class=bar id=b><i id=bi></i></div><div id=s></div>"
                + "<h2 id=ot></h2><div class=grid id=g></div>"
                + "<div class=sheet id=sh><div class=card><button id=bh></button><button id=bd class=red></button>"
                + "<button id=bc class=dim></button></div></div><script>"
                + "const S=" + s + ",q=location.search,$=i=>document.getElementById(i);"
                + "const fmt=(t,a,b)=>t.replace('%1$d',a).replace('%2$d',b);"
                + "$('pl').textContent=S.pick;$('ot').textContent=S.onTv;$('bd').textContent=S.del;$('bc').textContent=S.cancel;"
                + "let cur=null;"
                + "async function load(){const r=await fetch('/list'+q);const l=await r.json();const g=$('g');g.innerHTML='';"
                + "for(const p of l){const d=document.createElement('div');d.className='t'+(p.h?' h':'');"
                + "const im=document.createElement('img');im.loading='lazy';im.src='/thumb'+q+'&n='+encodeURIComponent(p.n);"
                + "d.appendChild(im);if(p.h){const b=document.createElement('span');b.textContent=S.hidden;d.appendChild(b)}"
                + "d.onclick=()=>{cur=p;$('bh').textContent=p.h?S.unhide:S.hide;$('sh').classList.add('o')};g.appendChild(d)}}"
                + "$('sh').onclick=e=>{if(e.target.id=='sh')$('sh').classList.remove('o')};"
                + "$('bc').onclick=()=>$('sh').classList.remove('o');"
                + "$('bh').onclick=async()=>{$('sh').classList.remove('o');"
                + "await fetch('/hide'+q+'&n='+encodeURIComponent(cur.n)+'&v='+(cur.h?0:1),{method:'POST'});load()};"
                + "$('bd').onclick=async()=>{$('sh').classList.remove('o');if(!confirm(S.delAsk))return;"
                + "await fetch('/delete'+q+'&n='+encodeURIComponent(cur.n),{method:'POST'});load()};"
                + "async function shrink(f){try{const i=await createImageBitmap(f),k=Math.min(1,2560/Math.max(i.width,i.height)),"
                + "c=document.createElement('canvas');c.width=Math.round(i.width*k);c.height=Math.round(i.height*k);"
                + "c.getContext('2d').drawImage(i,0,0,c.width,c.height);"
                + "return await new Promise(r=>c.toBlob(b=>r(b||f),'image/jpeg',.9))}catch(e){return f}}"
                + "$('f').onchange=async e=>{const fs=[...e.target.files];if(!fs.length)return;let ok=0,bad=0;"
                + "$('b').style.opacity=1;"
                + "for(let n=0;n<fs.length;n++){$('s').textContent=fmt(S.sending,n+1,fs.length);"
                + "$('bi').style.width=(n/fs.length*100)+'%';"
                + "try{const r=await fetch('/upload'+q,{method:'POST',body:await shrink(fs[n])});"
                + "if(r.status==507){$('s').textContent=S.full;break}if(r.ok)ok++;else bad++}catch(x){bad++}}"
                + "$('bi').style.width='100%';setTimeout(()=>{$('b').style.opacity=0;$('bi').style.width=0},900);"
                + "if($('s').textContent!=S.full)$('s').textContent=fmt(S.done,ok,0)+(bad?' '+fmt(S.failed,bad,0):'');"
                + "e.target.value='';load()};load()</script></body></html>";
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static String js(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c").replace("\n", " ");
    }
}
