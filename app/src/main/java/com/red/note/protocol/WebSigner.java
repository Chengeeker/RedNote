package com.red.note.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Web protocol version mns0301. Browser state must come from this device. */
public final class WebSigner {
    public static final String COMMON = "ZmserbBoHQtNP+wOcza/LpngG8yJq42KWYj0DSfdikx3VT16IlUAFM97hECvuRX5";
    public static final String MNS = "MfgqrsbcyzPQRStuvC7mn501HIJBo2DEFTKdeNOwxWXYZap89+/A4UVLhijkl63G";
    public static final String STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    static final byte[] MASK = hex("71a302257793271ddd273bcee3e4b98d9d7935e1da33f5765e2ea8afb6dc77a51a499d23b67c20660025860cbf13d4540d92497f58686c574e508f46e1956344f39139bf4faf22a3eef120b79258145b2feb5193b6478669961298e79bedca646e1a693a926154a5a7a1bd1cf0dedb742f917a747a1e388b234f2277516db7116035439730fa61e9822a0eca7bff72d8");

    public static final class Context {
        public String a1, platform, appId, sdk, webBuild, fingerprint;
        public String commonX0, commonX6, commonX7, mode, dsllt, dsl;
        public int platformCode, mnsCount, commonCount, propertyCount, width, height;
        public long loadTime, pageStart;
        public byte[] environment;
    }

    public static byte[] unpackMns(String payload) {
        if (!payload.startsWith("mns0301_")) throw new IllegalArgumentException("网页签名版本暂不支持，请更新应用");
        byte[] bytes = decode(payload.substring(8), MNS);
        if (bytes.length < 82 || bytes.length > MASK.length) throw new IllegalArgumentException("网页签名长度异常");
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= MASK[i];
        if (le(bytes, 0, 4) != 694184057L) throw new IllegalArgumentException("网页签名格式不匹配");
        return bytes;
    }

    public static Map<String, String> headers(Context c, String path, String content, boolean get,
            boolean commonMns, long time, int randomWord, String direction) {
        if (c.a1 == null || c.a1.isEmpty() || c.environment == null || c.environment.length != 16)
            throw new IllegalStateException("请先完成网页登录并初始化签名环境");
        byte[] a1 = utf8(c.a1), app = utf8(c.appId);
        if (a1.length > 255 || app.length > 255) throw new IllegalArgumentException("签名身份长度异常");
        byte[] digest = md5(content);
        int nonce = randomWord & 255;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        put(payload, new byte[] {121,104,96,41});
        little(payload, randomWord & 0xffffffffL, 4);
        little(payload, time, 8); little(payload, c.loadTime, 8);
        little(payload, c.mnsCount, 4); little(payload, c.propertyCount, 4);
        little(payload, utf8(content).length, 4);
        for (int i = 0; i < 8; i++) payload.write((digest[i] & 255) ^ nonce);
        payload.write(a1.length); put(payload, a1);
        payload.write(app.length); put(payload, app);
        put(payload, c.environment); put(payload, new byte[] {2,97,51,16});
        ByteArrayOutputStream hashInput = new ByteArrayOutputStream();
        little(hashInput, time, 8); put(hashInput, get ? digest : md5(path));
        byte[] hash = arx(hashInput.toByteArray());
        for (byte value : hash) payload.write((value & 255) ^ nonce);
        byte[] bytes = payload.toByteArray();
        if (bytes.length > MASK.length) throw new IllegalArgumentException("签名载荷超出支持版本");
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= MASK[i];
        Map<String, Object> envelope = map("x0", c.sdk, "x1", c.appId, "x2", c.platform,
                "x3", "mns0301_" + encode(bytes, MNS), "x4", get ? "" : "object");
        CRC32 crc = new CRC32(); crc.update(utf8(c.commonX6 + c.commonX7 + c.fingerprint));
        Map<String, Object> common = map("s0", c.platformCode, "s1", "", "x0", c.commonX0,
                "x1", c.sdk, "x2", c.platform, "x3", c.appId, "x4", c.webBuild,
                "x5", c.a1, "x6", c.commonX6, "x7", c.commonX7, "x8", c.fingerprint,
                "x9", (int)(crc.getValue() ^ 0xffffffffL ^ 306674911L), "x10", c.commonCount,
                "x11", c.mode, "x12", c.dsllt + ";" + c.dsl);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("x-s", "XYS_" + encode(utf8(json(envelope)), COMMON));
        result.put("x-s-common", encode(utf8(json(common)), COMMON));
        result.put("x-t", Long.toString(time));
        if (commonMns) { result.put("x-mns", "unload"); result.put("xy-direction", direction); }
        return result;
    }

    private static byte[] arx(byte[] input) {
        int a = 1831565813 ^ 24, b = 461845907 ^ (24 << 8);
        int c = (int)2246822507L ^ (24 << 16), d = (int)3266489909L ^ (24 << 24);
        for (int i = 0; i < 24; i += 8) {
            int x = (int)le(input, i, 4), y = (int)le(input, i + 4, 4);
            a = Integer.rotateLeft((a + x) ^ c, 7);
            b = Integer.rotateLeft((x ^ b) + d, 11);
            c = Integer.rotateLeft((c + y) ^ a, 13);
            d = Integer.rotateLeft((d ^ y) + b, 17);
        }
        a ^= 24; b ^= a; c += b; d ^= c;
        a = Integer.rotateLeft(a, 9); b = Integer.rotateLeft(b, 13);
        c = Integer.rotateLeft(c, 17); d = Integer.rotateLeft(d, 19);
        a += c; b ^= d; c += a; d ^= b;
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(a).putInt(b).putInt(c).putInt(d).array();
    }

    public static String encode(byte[] input, String alphabet) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length; i += 3) {
            int v = (input[i] & 255) << 16;
            if (i + 1 < input.length) v |= (input[i+1] & 255) << 8;
            if (i + 2 < input.length) v |= input[i+2] & 255;
            out.append(alphabet.charAt(v >>> 18)).append(alphabet.charAt((v >>> 12) & 63));
            out.append(i+1 < input.length ? alphabet.charAt((v >>> 6)&63) : '=');
            out.append(i+2 < input.length ? alphabet.charAt(v&63) : '=');
        }
        return out.toString();
    }

    public static byte[] decode(String input, String alphabet) {
        if (input.isEmpty() || input.length() % 4 != 0 || input.length() > 65536)
            throw new IllegalArgumentException("签名编码异常");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < input.length(); i += 4) {
            int value = 0, pad = 0;
            for (int j = 0; j < 4; j++) {
                char ch = input.charAt(i+j);
                if (ch == '=') {
                    if (j < 2 || i+4 != input.length()) throw new IllegalArgumentException("签名编码异常");
                    pad++; value <<= 6;
                } else {
                    int index = alphabet.indexOf(ch);
                    if (index < 0 || pad != 0) throw new IllegalArgumentException("签名编码异常");
                    value = (value << 6) | index;
                }
            }
            if (pad > 2) throw new IllegalArgumentException("签名编码异常");
            out.write(value >>> 16);
            if (pad < 2) out.write(value >>> 8);
            if (pad < 1) out.write(value);
        }
        return out.toByteArray();
    }

    public static long le(byte[] input, int offset, int length) {
        if (offset < 0 || length > 8 || offset+length > input.length) throw new IllegalArgumentException("签名数据不完整");
        long n = 0; for (int i=0; i<length; i++) n |= (long)(input[offset+i]&255) << (8*i); return n;
    }
    public static String queryEncode(String value) {
        StringBuilder out=new StringBuilder();
        for(byte b:utf8(value)) {
            int v=b&255;
            if((v>='a'&&v<='z')||(v>='A'&&v<='Z')||(v>='0'&&v<='9')||"_.-~,".indexOf(v)>=0)out.append((char)v);
            else out.append('%').append("0123456789ABCDEF".charAt(v>>>4)).append("0123456789ABCDEF".charAt(v&15));
        }
        return out.toString();
    }
    public static String direction(String uid) {
        byte[] input=utf8(uid);int h=151488,p=0;
        while(p+4<=input.length) {
            int k=(int)le(input,p,4);k*=0xcc9e2d51;k=Integer.rotateLeft(k,15);k*=0x1b873593;
            h^=k;h=Integer.rotateLeft(h,13);h=h*5+0xe6546b64;p+=4;
        }
        if(p<input.length){int k=0;for(int j=0;p+j<input.length;j++)k|=(input[p+j]&255)<<(j*8);k*=0xcc9e2d51;k=Integer.rotateLeft(k,15);k*=0x1b873593;h^=k;}
        h^=input.length;h^=h>>>16;h*=0x85ebca6b;h^=h>>>13;h*=0xc2b2ae35;h^=h>>>16;
        return Long.toString((h&0xffffffffL)%100+1);
    }
    static void little(ByteArrayOutputStream out, long n, int bytes) { for(int i=0;i<bytes;i++) out.write((int)(n >>> (i*8))); }
    static void put(ByteArrayOutputStream out, byte[] bytes) { out.write(bytes, 0, bytes.length); }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] md5(String s) {
        try { return MessageDigest.getInstance("MD5").digest(utf8(s)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static byte[] hex(String s) {
        byte[] b = new byte[s.length()/2];
        for (int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return b;
    }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    public static String json(Map<String,Object> map) {
        StringBuilder out=new StringBuilder("{");
        for(Map.Entry<String,Object> item:map.entrySet()) {
            if(out.length()>1) out.append(',');
            out.append(quote(item.getKey())).append(':');
            Object value=item.getValue();
            out.append(value instanceof Number || value instanceof Boolean ? value.toString() : quote((String)value));
        }
        return out.append('}').toString();
    }
    private static String quote(String s) {
        if(s==null) return "null";
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            switch(ch) {
                case '\\':out.append("\\\\");break; case '"':out.append("\\\"");break;
                case '\n':out.append("\\n");break; case '\r':out.append("\\r");break; case '\t':out.append("\\t");break;
                case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;
                default:if(ch<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)ch));else out.append(ch);
            }
        }
        return out.append('"').toString();
    }
}
