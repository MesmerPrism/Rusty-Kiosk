/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Adapted from Rusty Quest at c3d2df89b644189efcf08279783e01d97a83f819.
 * Only the Java package changed.
 */
package io.github.mesmerprism.queststatusaudioexample;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * Main-owner-confined authenticated phone pairing, separate from experiment commands.
 * Construct and invoke on the same actual app-owner thread. No callbacks run under
 * locks; callbacks must not reenter this owner. Host persistence must atomically
 * bind its current owner identity and throw before returning on failed storage.
 * No method logs credentials, ephemeral keys, proofs or the displayed code.
 */
public final class ExperimentPhonePairing {
    public interface Host {
        JSONObject identity();
        boolean canPair();
        /** Atomically persist one new independent member, or throw. Returns controller/key/device. */
        JSONObject addTrustedPhone(String phoneName) throws Exception;
        /** Compensate only that newly created member; throw unless removal is durable. */
        void discardTrustedPhone(String controller) throws Exception;
        long clockElapsedMs();
    }
    private static final long LIFETIME_MS=120000, RATE_WINDOW_MS=60000;
    private static final int MAX_BEGINS=4,MAX_EXCHANGES=180,MAX_RETAINED=8;
    private static final String PREFIX="RQ-PHONE-PAIR-2";
    // Exact named-P256, uncompressed-point SPKI header; alternate encodings are not canonical.
    private static final byte[] P256_SPKI_HEADER=unhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004");
    private static final BigInteger P=new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",16);
    private static final BigInteger B=new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",16);
    private final Host host;
    private final Thread thread=Thread.currentThread();
    private final SecureRandom random=new SecureRandom();
    private final Identity identity;
    private final ArrayDeque<Long> exchanges=new ArrayDeque<>(),begins=new ArrayDeque<>();
    private final LinkedHashMap<String,Attempt> attempts=new LinkedHashMap<>();
    private boolean entered,retired,storageIndeterminate;
    private long lastNow=-1;
    private Attempt pending;

    public ExperimentPhonePairing(Host host) {
        if(host==null)throw new IllegalArgumentException("pair-host-required");
        this.host=host;this.identity=Identity.read(host.identity());
    }
    public static boolean handlesAction(String action) {return "pair-begin".equals(action)||"pair-reveal".equals(action)||"pair-confirm".equals(action)||"pair-status".equals(action)||"pair-cancel".equals(action);}
    public JSONObject exchange(JSONObject request) {
        enter();try {
            requireLive();long now=now();rate(exchanges,MAX_EXCHANGES,now);
            if(request==null)throw denied("pair-request-invalid");
            String action=string(request,"action");
            if(!handlesAction(action))throw denied("pair-action-invalid");
            if("pair-begin".equals(action)){
                rate(begins,MAX_BEGINS,now);
                return begin(request,now);
            }
            if("pair-reveal".equals(action))return reveal(request,now);
            closed(request,"v","action","device","epoch","native_epoch","pairing_id","proof");
            version(request);identity.matchesRequest(request);
            String id=string(request,"pairing_id"),proof=string(request,"proof");
            if(!id.matches("[0-9a-f]{32}")||!proof.matches("[0-9a-f]{64}"))throw denied("pair-auth-invalid");
            Attempt attempt=attempts.get(id);
            if(attempt==null||attempt.proof==null||!MessageDigest.isEqual(unhex(proof),hmac(attempt.proof,utf8(attempt.transcript+"|"+action))))throw denied("pair-auth-invalid");
            expire(attempt,now);
            if("pending".equals(attempt.state)){
                if("pair-cancel".equals(action))finish(attempt,"cancelled");
                else if("pair-confirm".equals(action)){attempt.phone=true;approve(attempt);}
            }
            return response(attempt);
        } catch(SecurityException|IllegalArgumentException error){throw error;}
        catch(Exception error){throw denied("pair-request-failed");}
        finally{entered=false;}
    }

    /** For the private headset panel only. This is not public discovery/status metadata. */
    public JSONObject pendingPrompt() {
        enter();try {
            if(!live())return null;
            if(pending==null)return null;
            expire(pending,now());if(pending==null||!"pending".equals(pending.state))return null;
            return new JSONObject().put("pairing_id",pending.id).put("code",pending.code).put("phone_name",pending.name)
                .put("expires_in_ms",pending.expires-lastNow).put("phone_confirmed",pending.phone).put("headset_confirmed",pending.headset);
        }catch(Exception error){invalidate();return null;}
        finally{entered=false;}
    }

    /** Only an actual headset button may call this; no network request approves the headset. */
    public void decide(String pairingId,boolean allow) {
        enter();try {
            requireLive();long now=now();Attempt attempt=pending;
            if(attempt==null||!attempt.id.equals(pairingId))throw denied("pair-prompt-stale");
            expire(attempt,now);
            if(!"pending".equals(attempt.state))throw denied("pair-prompt-stale");
            if(!allow)finish(attempt,"rejected");else{attempt.headset=true;approve(attempt);}
        }catch(SecurityException|IllegalArgumentException error){throw error;}
        catch(Exception error){throw denied("pair-decision-failed");}
        finally{entered=false;}
    }

    public void retire() {enter();try{invalidate();}finally{entered=false;}}

    /** Private app-owner diagnostic, never discovery metadata or a credential result. */
    public boolean storageIndeterminate() {enter();try{return storageIndeterminate;}finally{entered=false;}}

    private JSONObject begin(JSONObject request,long now)throws Exception {
        closed(request,"v","action","device","serial","identity_kind","epoch","native_epoch","phone_name","client_commitment");
        version(request);identity.matchesRequest(request);
        if(!identity.serial.equals(string(request,"serial"))||!identity.kind.equals(string(request,"identity_kind")))throw denied("pair-identity-mismatch");
        String name=string(request,"phone_name"),commitment=string(request,"client_commitment");validName(name);
        if(!commitment.matches("[0-9a-f]{64}"))throw denied("pair-commitment-invalid");
        if(pending!=null)expire(pending,now);
        prune(now);
        if(pending!=null)throw denied("pair-pending");
        if(attempts.size()>=MAX_RETAINED)throw denied("pair-capacity");
        if(!host.canPair())throw denied("pair-unavailable");
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"),random);KeyPair key=generator.generateKeyPair();
        String serverKey=base64(key.getPublic().getEncoded()),serverNonce=base64(randomBytes(16)),id=hex(randomBytes(16));
        if(attempts.containsKey(id))throw denied("pair-random-collision");
        String context=identity.device+"|"+identity.serial+"|"+identity.kind+"|"+identity.epoch+"|"+identity.nativeEpoch+"|"+base64(utf8(name));
        Attempt attempt=new Attempt(id,name,context,commitment,serverKey,serverNonce,now+LIFETIME_MS,key);
        requireLive();if(!host.canPair())throw denied("pair-unavailable");
        attempts.put(id,attempt);pending=attempt;
        return new JSONObject().put("v",2).put("device",identity.device).put("epoch",identity.epoch).put("native_epoch",identity.nativeEpoch)
            .put("pairing_id",id).put("client_commitment",commitment).put("server_public_key",serverKey).put("server_nonce",serverNonce).put("expires_in_ms",LIFETIME_MS);
    }

    /**
     * Ordered commitment opening, following RFC 6189 sections 4.4.1.1 and 7:
     * the initiator hides its DH key AND random nonce; only after the responder
     * freezes its own key/nonce can the initiator open. Neither endpoint can
     * adapt its choices after learning the missing peer contribution. This is
     * a SAS pairing protocol using that ordering, not a ZRTP implementation.
     */
    private JSONObject reveal(JSONObject request,long now)throws Exception {
        closed(request,"v","action","device","epoch","native_epoch","pairing_id","client_public_key","client_nonce","proof");
        version(request);identity.matchesRequest(request);
        String id=string(request,"pairing_id"),claimedProof=string(request,"proof");
        if(!id.matches("[0-9a-f]{32}")||!claimedProof.matches("[0-9a-f]{64}"))throw denied("pair-auth-invalid");
        Attempt attempt=attempts.get(id);if(attempt==null)throw denied("pair-auth-invalid");
        String clientKey=string(request,"client_public_key"),clientNonce=string(request,"client_nonce");
        expire(attempt,now);
        if(!"committed".equals(attempt.state)){
            if(attempt.proof==null||!clientKey.equals(attempt.clientKey)||!clientNonce.equals(attempt.clientNonce)
                ||!MessageDigest.isEqual(unhex(claimedProof),hmac(attempt.proof,utf8(attempt.transcript+"|pair-reveal"))))throw denied("pair-opening-invalid");
            return response(attempt);
        }
        byte[] sas=null,aead=null,proof=null,secret=null,prk=null;
        try {
            decode64(clientNonce,16,16);ECPublicKey peer=publicKey(clientKey);
            byte[] opening=MessageDigest.getInstance("SHA-256").digest(utf8(PREFIX+"/commit|"+attempt.context+"|"+clientNonce+"|"+clientKey));
            if(!MessageDigest.isEqual(unhex(attempt.commitment),opening))throw denied("pair-opening-invalid");
            String transcript=PREFIX+"|"+attempt.context+"|"+id+"|"+attempt.commitment+"|"+clientNonce+"|"+attempt.serverNonce+"|"+clientKey+"|"+attempt.serverPublic;
            KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(attempt.serverKey.getPrivate());agreement.doPhase(peer,true);secret=agreement.generateSecret();
            if(secret.length!=32)throw denied("pair-secret-invalid");
            byte[] salt=MessageDigest.getInstance("SHA-256").digest(utf8(transcript));prk=hmac(salt,secret);
            sas=expand(prk,"SAS");aead=expand(prk,"AEAD");proof=expand(prk,"PROOF");
            if(!MessageDigest.isEqual(unhex(claimedProof),hmac(proof,utf8(transcript+"|pair-reveal"))))throw denied("pair-auth-invalid");
            requireLive();expire(attempt,now());
            if(!"committed".equals(attempt.state)||!host.canPair())throw denied("pair-unavailable");
            long first=((sas[0]&255L)<<24)|((sas[1]&255L)<<16)|((sas[2]&255L)<<8)|(sas[3]&255L);
            attempt.transcript=transcript;attempt.code=String.format(Locale.ROOT,"%06d",first%1000000L);
            attempt.clientKey=clientKey;attempt.clientNonce=clientNonce;attempt.aead=aead;attempt.proof=proof;attempt.serverKey=null;attempt.state="pending";
            aead=null;proof=null;
            return response(attempt);
        }catch(Exception error){finish(attempt,"failed");throw denied("pair-opening-invalid");}
        finally{wipe(secret);wipe(prk);wipe(sas);wipe(aead);wipe(proof);}
    }

    private void approve(Attempt attempt)throws Exception {
        if(!attempt.phone||!attempt.headset)return;
        String controller=null;boolean stored=false;
        try {
            requireLive();expire(attempt,now());
            if(!"pending".equals(attempt.state))return;
            if(!host.canPair()){finish(attempt,"failed");return;}
            // The host is the atomic storage boundary and enforces the current owner again.
            JSONObject member=host.addTrustedPhone(attempt.name);
            if(member!=null&&member.opt("controller") instanceof String)controller=(String)member.opt("controller");
            stored=true;
            if(controller==null||!controller.matches("[0-9a-f]{32}"))throw denied("pair-member-invalid");
            String key=string(member,"key");
            if(!key.matches("[0-9a-f]{64}")||!identity.device.equals(string(member,"device")))throw denied("pair-member-invalid");
            requireLive();expire(attempt,now());
            if(!"pending".equals(attempt.state)||!host.canPair())throw denied("pair-owner-changed");
            JSONObject clear=new JSONObject().put("v",2).put("controller",controller).put("key",key).put("device",identity.device)
                .put("serial",identity.serial).put("identity_kind",identity.kind).put("epoch",identity.epoch).put("native_epoch",identity.nativeEpoch);
            byte[] iv=randomBytes(12),plaintext=utf8(clear.toString());
            try {
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(attempt.aead,"AES"),new GCMParameterSpec(128,iv));
                cipher.updateAAD(utf8(PREFIX+"/result|"+attempt.transcript));byte[] ciphertext=cipher.doFinal(plaintext);
                requireLive();expire(attempt,now());
                if(!"pending".equals(attempt.state)||!host.canPair())throw denied("pair-owner-changed");
                attempt.iv=base64(iv);attempt.data=base64(ciphertext);finish(attempt,"approved");
            }finally{wipe(plaintext);}
        }catch(Exception failure){
            // Even compensation failure never emits a ticket. The host must surface/reconcile
            // an indeterminate storage failure separately; this owner never retries the add.
            if(stored){
                if(controller==null)storageIndeterminate=true;
                else try{host.discardTrustedPhone(controller);}catch(Exception cleanup){storageIndeterminate=true;failure.addSuppressed(cleanup);}
            }
            attempt.iv=null;attempt.data=null;finish(attempt,"failed");
        }
    }

    private JSONObject response(Attempt attempt)throws Exception {
        JSONObject result=new JSONObject().put("v",2).put("pairing_id",attempt.id).put("state",attempt.state);
        if("approved".equals(attempt.state))result.put("iv",attempt.iv).put("data",attempt.data);
        return result;
    }
    private void expire(Attempt attempt,long now){if(now>=attempt.expires){attempt.iv=null;attempt.data=null;finish(attempt,"expired");}}
    private void finish(Attempt attempt,String state){attempt.state=state;if(pending==attempt)pending=null;wipe(attempt.aead);attempt.aead=null;attempt.serverKey=null;}
    private void prune(long now){Iterator<Map.Entry<String,Attempt>> it=attempts.entrySet().iterator();while(it.hasNext()){Attempt a=it.next().getValue();if(now>=a.expires){a.erase();it.remove();}}}
    private void enter(){if(Thread.currentThread()!=thread)throw new IllegalStateException("pair-main-owner-required");if(entered)throw denied("pair-reentry");entered=true;}
    private long now(){long value=host.clockElapsedMs();if(value<0||value<lastNow||value>Long.MAX_VALUE-LIFETIME_MS){invalidate();throw denied("pair-clock-invalid");}lastNow=value;return value;}
    private boolean live(){if(retired)return false;try{if(identity.equals(Identity.read(host.identity())))return true;}catch(Exception invalid){}invalidate();return false;}
    private void requireLive(){if(!live())throw denied("pair-owner-retired");}
    private void invalidate(){retired=true;pending=null;for(Attempt a:attempts.values())a.erase();attempts.clear();}
    private static void rate(ArrayDeque<Long> timestamps,int max,long now){while(!timestamps.isEmpty()&&now-timestamps.peekFirst()>=RATE_WINDOW_MS)timestamps.removeFirst();if(timestamps.size()>=max)throw denied("pair-rate-limited");timestamps.addLast(now);}
    private static SecurityException denied(String reason){return new SecurityException(reason);}
    private static void version(JSONObject value)throws Exception {Object v=value.get("v");if(!(v instanceof Integer)&&!(v instanceof Long)||((Number)v).longValue()!=2)throw denied("pair-version-invalid");}
    private static void closed(JSONObject value,String... fields){if(value.length()!=fields.length)throw denied("pair-fields-invalid");for(String field:fields)if(!value.has(field))throw denied("pair-fields-invalid");}
    private static String string(JSONObject value,String field)throws Exception {Object result=value.get(field);if(!(result instanceof String))throw denied("pair-field-invalid");return (String)result;}
    private static void validName(String name){
        if(name.isEmpty()||name.length()>64||!name.equals(name.trim())||utf8(name).length>128)throw denied("pair-name-invalid");
        for(int i=0;i<name.length();i++){char c=name.charAt(i);if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)throw denied("pair-name-invalid");
            if(Character.isHighSurrogate(c)){if(i+1==name.length()||!Character.isLowSurrogate(name.charAt(++i)))throw denied("pair-name-invalid");}else if(Character.isLowSurrogate(c))throw denied("pair-name-invalid");}
    }
    private static ECPublicKey publicKey(String encoded)throws Exception {
        byte[] der=decode64(encoded,91,91);
        for(int i=0;i<P256_SPKI_HEADER.length;i++)if(der[i]!=P256_SPKI_HEADER[i])throw denied("pair-key-invalid");
        ECPublicKey key=(ECPublicKey)KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        if(!Arrays.equals(der,key.getEncoded()))throw denied("pair-key-invalid");
        ECPoint point=key.getW();BigInteger x=point.getAffineX(),y=point.getAffineY();
        if(x==null||y==null||x.signum()<0||y.signum()<0||x.compareTo(P)>=0||y.compareTo(P)>=0||!y.multiply(y).mod(P).equals(x.multiply(x).multiply(x).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)))throw denied("pair-key-invalid");
        return key;
    }
    private static byte[] decode64(String value,int min,int max){
        if(value.length()>(max*4+2)/3||!value.matches("[A-Za-z0-9_-]+"))throw denied("pair-encoding-invalid");
        byte[] decoded;try{decoded=Base64.getUrlDecoder().decode(value);}catch(IllegalArgumentException error){throw denied("pair-encoding-invalid");}
        if(decoded.length<min||decoded.length>max||!base64(decoded).equals(value))throw denied("pair-encoding-invalid");return decoded;
    }
    private byte[] randomBytes(int length){byte[] value=new byte[length];random.nextBytes(value);return value;}
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String base64(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder(bytes.length*2);for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    private static byte[] unhex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
    private static byte[] hmac(byte[] key,byte[] value)throws Exception {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value);}
    /** RFC 5869 expand, exactly one SHA-256 block for a 32-byte output. Extract is HMAC(salt, ECDH). */
    private static byte[] expand(byte[] prk,String label)throws Exception {byte[] info=utf8(PREFIX+"/"+label),input=Arrays.copyOf(info,info.length+1);input[info.length]=1;return hmac(prk,input);}
    private static void wipe(byte[] value){if(value!=null)Arrays.fill(value,(byte)0);}
    private static final class Attempt {
        final String id,name,context,commitment,serverPublic,serverNonce;final long expires;KeyPair serverKey;
        byte[] aead,proof;String state="committed",transcript,code,clientKey,clientNonce,iv,data;boolean phone,headset;
        Attempt(String id,String name,String context,String commitment,String serverPublic,String serverNonce,long expires,KeyPair serverKey){this.id=id;this.name=name;this.context=context;this.commitment=commitment;this.serverPublic=serverPublic;this.serverNonce=serverNonce;this.expires=expires;this.serverKey=serverKey;}
        void erase(){wipe(aead);wipe(proof);aead=null;proof=null;serverKey=null;iv=null;data=null;state="expired";}
    }
    private static final class Identity {
        final String device,epoch,nativeEpoch,serial,kind;
        Identity(String device,String epoch,String nativeEpoch,String serial,String kind){this.device=device;this.epoch=epoch;this.nativeEpoch=nativeEpoch;this.serial=serial;this.kind=kind;}
        static Identity read(JSONObject json){try {
            String device=string(json,"device"),epoch=string(json,"epoch"),nativeEpoch=string(json,"native_epoch"),serial=string(json,"serial"),kind=string(json,"identity_kind");
            if(!device.matches("[0-9a-f]{32}")||!epoch.matches("[1-9][0-9]{0,18}")||!nativeEpoch.matches("[1-9][0-9]{0,18}")||Long.parseLong(epoch)<=0||Long.parseLong(nativeEpoch)<=0||!"app-installation".equals(kind)||!serial.matches("(?:[A-Za-z0-9][A-Za-z0-9._-]{0,63})?"))throw denied("pair-identity-invalid");
            return new Identity(device,epoch,nativeEpoch,serial,kind);
        }catch(Exception error){throw denied("pair-identity-invalid");}}
        void matchesRequest(JSONObject json)throws Exception {if(!device.equals(string(json,"device"))||!epoch.equals(string(json,"epoch"))||!nativeEpoch.equals(string(json,"native_epoch")))throw denied("pair-identity-mismatch");}
        public boolean equals(Object other){if(!(other instanceof Identity))return false;Identity i=(Identity)other;return device.equals(i.device)&&epoch.equals(i.epoch)&&nativeEpoch.equals(i.nativeEpoch)&&serial.equals(i.serial)&&kind.equals(i.kind);}
        public int hashCode(){return device.hashCode();}
    }
}
