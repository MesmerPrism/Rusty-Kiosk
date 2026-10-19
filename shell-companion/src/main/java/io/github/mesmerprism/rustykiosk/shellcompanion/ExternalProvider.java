// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

import android.content.AttributionSource;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import java.lang.reflect.Method;

/** Stock content-command external-provider route, restricted to one authority and user 0. */
final class ExternalProvider {
    static final String AUTHORITY=CompanionIdentity.PROVIDER_AUTHORITY;
    private final Object activityManager, provider;
    private final IBinder token=new Binder();
    private final AttributionSource attribution;
    private final Method providerCall, remove;
    private boolean released;
    ExternalProvider(AttributionSource source) throws Exception {
        if (Process.myUid()!=2000 || source.getUid()!=2000 || source.getPid()!=Process.myPid()
                || !"com.android.shell".equals(source.getPackageName())) throw new SecurityException("genuine-shell-attribution");
        attribution=source;
        Class<?> am=Class.forName("android.app.IActivityManager");
        activityManager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        remove=am.getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class);
        Object holder=am.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
            .invoke(activityManager,AUTHORITY,0,token,"rusty-kiosk-shell-companion");
        if (holder==null) throw new IllegalStateException("fixed-provider-unavailable");
        Object resolved;
        try {
            Class<?> holderType=Class.forName("android.app.ContentProviderHolder");
            ProviderInfo info=(ProviderInfo)holderType.getField("info").get(holder);
            if (info==null || !CompanionIdentity.KIOSK_PACKAGE.equals(info.packageName)
                    || !AUTHORITY.equals(info.authority)) throw new SecurityException("fixed-provider-identity");
            resolved=holderType.getField("provider").get(holder);
            if (!(resolved instanceof IInterface)) throw new IllegalStateException("external-provider-not-ready");
            providerCall=Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource.class,
                String.class,String.class,String.class,Bundle.class);
        } catch(Exception error) {
            remove.invoke(activityManager,AUTHORITY,token,0); throw error;
        }
        provider=resolved;
    }
    Bundle call(String method,Bundle extras) throws Exception {
        if (released || !((IInterface)provider).asBinder().isBinderAlive()) throw new IllegalStateException("fixed-provider-retired");
        if (!("register".equals(method)||"poll".equals(method)||"target-state".equals(method)
                ||"native-open".equals(method)||"native-closed".equals(method)||"claim-close".equals(method)
                ||"close-result".equals(method))) throw new IllegalArgumentException("closed-protocol-operation");
        Object response=providerCall.invoke(provider,attribution,AUTHORITY,method,null,extras);
        if (!(response instanceof Bundle)) throw new IllegalStateException("typed-provider-bundle");
        Bundle value=(Bundle)response; value.size(); return value;
    }
    void release() throws Exception {
        if (released) throw new IllegalStateException("external-reference-already-released");
        remove.invoke(activityManager,AUTHORITY,token,0); released=true;
    }
}
