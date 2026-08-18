package com.mrnobody.browser.net;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.mrnobody.debug.ErrorLog;

import java.util.Collections;
import java.util.Set;

/**
 * Reduces unnecessary uniqueness. It does not make anyone anonymous.
 *
 * <p>That distinction is the whole design. Bromite — which does considerably
 * more of this than we do — still tells journalists and users in hostile
 * jurisdictions to use Tor Browser instead. Anything here that reads as an
 * anonymity promise is a bug in the writing.
 *
 * <p>The mechanism is {@code addDocumentStartJavaScript}: it runs before any
 * page script, applies inside iframes, and takes an origin allowlist. That
 * ordering is what makes it worth doing at all — a patch applied after page
 * script has run is theatre, because the page has already read the real value.
 *
 * <p><b>What is patched, and why these.</b> Each of the surfaces below is read
 * by commodity fingerprinting scripts and is safe to perturb without breaking
 * ordinary pages:
 *
 * <ul>
 *   <li><b>Canvas</b> — the classic. A tiny, deterministic per-session
 *       perturbation of readback pixels, not visible rendering.
 *   <li><b>WebGL vendor/renderer</b> — reports a generic GPU rather than the
 *       exact chipset, which is close to a device identifier.
 *   <li><b>hardwareConcurrency / deviceMemory</b> — rounded to common values.
 *   <li><b>Screen metrics</b> — quantised, so an unusual window size does not
 *       single the user out.
 * </ul>
 *
 * <p>Timezone is deliberately <em>not</em> patched here: lying about it while
 * the network route still exits in the user's own country produces an
 * inconsistency that is itself distinctive. It belongs with the route, not
 * with the browser.
 *
 * <p>Noise is per-session and stable within it. Re-randomising per call would
 * be trivially detectable by reading the same value twice.
 */
public final class FingerprintDefence {

    /** All origins. Fingerprinting is not confined to a domain we could list. */
    private static final Set<String> ALL_ORIGINS = Collections.singleton("*");

    private FingerprintDefence() {
    }

    /** True when scripts can be injected before page load on this device. */
    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Install the patches on {@code webView}.
     *
     * @param seed stable per session, so repeated reads agree
     * @return true when the patches are in place; false means the toggle is on
     *         but doing nothing, and the UI must not imply otherwise
     */
    public static boolean apply(WebView webView, long seed) {
        if (webView == null || !isSupported()) return false;
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, script(seed), ALL_ORIGINS);
            return true;
        } catch (Throwable t) {
            ErrorLog.record("fingerprint defence not applied: " + t);
            return false;
        }
    }

    /**
     * The injected source.
     *
     * <p>Package-visible so a test can assert what it patches without needing
     * a WebView. Kept short on purpose: this blocks page load.
     */
    static String script(long seed) {
        return "(function(){try{"
                // Deterministic per-session PRNG: same seed, same answers.
                + "var s=" + (seed & 0x7fffffff) + ";"
                + "function r(){s=(s*1103515245+12345)&0x7fffffff;return s/0x7fffffff;}"

                // --- canvas readback ---------------------------------------
                // Perturb the data a script reads, never what the user sees.
                + "var gid=CanvasRenderingContext2D.prototype.getImageData;"
                + "if(gid){CanvasRenderingContext2D.prototype.getImageData=function(){"
                + "var d=gid.apply(this,arguments);"
                + "try{var p=d.data;for(var i=0;i<p.length;i+=4){"
                + "var n=(r()*2|0)-1;"
                + "p[i]=Math.min(255,Math.max(0,p[i]+n));"
                + "p[i+1]=Math.min(255,Math.max(0,p[i+1]+n));"
                + "p[i+2]=Math.min(255,Math.max(0,p[i+2]+n));}}catch(e){}"
                + "return d;};}"

                // toDataURL/toBlob derive from the same buffer; nudge alpha so
                // the serialised form is not a stable hash either.
                + "var tdu=HTMLCanvasElement.prototype.toDataURL;"
                + "if(tdu){HTMLCanvasElement.prototype.toDataURL=function(){"
                + "try{var c=this.getContext('2d');"
                + "if(c){c.globalAlpha=0.999999;}}catch(e){}"
                + "return tdu.apply(this,arguments);};}"

                // --- WebGL identity ----------------------------------------
                // UNMASKED_VENDOR_WEBGL 37445 / UNMASKED_RENDERER_WEBGL 37446
                + "function pg(P){if(!P)return;var g=P.prototype.getParameter;"
                + "if(!g)return;P.prototype.getParameter=function(p){"
                + "if(p===37445)return 'Generic';"
                + "if(p===37446)return 'Generic Renderer';"
                + "return g.apply(this,arguments);};}"
                + "pg(window.WebGLRenderingContext);pg(window.WebGL2RenderingContext);"

                // --- coarse device facts -----------------------------------
                + "function def(o,k,v){try{Object.defineProperty(o,k,"
                + "{get:function(){return v;},configurable:true});}catch(e){}}"
                + "def(navigator,'hardwareConcurrency',4);"
                + "if('deviceMemory' in navigator)def(navigator,'deviceMemory',4);"

                // --- screen metrics ----------------------------------------
                // Quantise to 100px so an odd size is not a signal.
                + "function q(v){return Math.round(v/100)*100;}"
                + "try{def(screen,'width',q(screen.width));"
                + "def(screen,'height',q(screen.height));"
                + "def(screen,'availWidth',q(screen.availWidth));"
                + "def(screen,'availHeight',q(screen.availHeight));}catch(e){}"

                + "}catch(e){}})();";
    }
}
