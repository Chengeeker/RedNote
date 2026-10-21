package com.red.note.protocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/** Offline protocol regression checks; these do not prove server acceptance. */
public final class ProtocolSelfTest {
    static WebSigner.Context context() {
        WebSigner.Context c=new WebSigner.Context();
        c.a1="a1-test-only";c.appId="xhs-pc-web";c.platform="Android";c.sdk="4.3.7";c.webBuild="test-build";
        c.fingerprint="test-fingerprint";c.commonX0="4";c.commonX6="123";c.commonX7="0";
        c.mode="normal";c.dsllt="0";c.dsl="0";c.platformCode=1;c.mnsCount=17;c.commonCount=23;c.propertyCount=42;
        c.loadTime=1699999999000L;c.width=412;c.height=780;c.pageStart=1699999999000L;
        c.environment=new byte[16];for(int i=0;i<16;i++)c.environment[i]=(byte)i;
        return c;
    }
    public static void main(String[] args)throws Exception {
        Random r=new Random(123);
        for(int size=0;size<1024;size++) {
            byte[] bytes=new byte[size];r.nextBytes(bytes);
            for(String alphabet:new String[]{WebSigner.COMMON,WebSigner.MNS,WebSigner.STANDARD}) {
                String encoded=WebSigner.encode(bytes,alphabet);
                if(size!=0)assert Arrays.equals(bytes,WebSigner.decode(encoded,alphabet));
                if(alphabet.equals(WebSigner.STANDARD))assert encoded.equals(java.util.Base64.getEncoder().encodeToString(bytes));
            }
        }
        for(String invalid:new String[]{"x","====","A===","AA=A","!!!!"}) {
            boolean rejected=false;try{WebSigner.decode(invalid,WebSigner.COMMON);}catch(IllegalArgumentException e){rejected=true;}assert rejected:invalid;
        }
        String path="/api/sns/web/v1/feed",body="{\"source_note_id\":\"test\"}";
        Map<String,String> signed=WebSigner.headers(context(),path,path+body,false,true,1700000000123L,0x12345678,"42");
        String expected=Files.readString(Path.of("tool/mns-vector.txt")).trim();
        assert expected.equals(signed.get("x-s")):"MNS deterministic vector mismatch";
        assert !signed.get("x-s").equals(WebSigner.headers(context(),path,path+body+" ",false,true,1700000000123L,0x12345678,"42").get("x-s"));
        assert !signed.get("x-s").equals(WebSigner.headers(context(),path,path+body,false,true,1700000000124L,0x12345678,"42").get("x-s"));
        assert RapSigner.xxhash(new byte[0])==0x02cc5d05L;
        assert RapSigner.xxhash("a".getBytes(StandardCharsets.UTF_8))==0x550d7456L;
        assert WebSigner.queryEncode("中 a,b&").equals("%E4%B8%AD%20a,b%26");
        for(int i=0;i<100;i++) {
            byte[] bytes=new byte[i%17];r.nextBytes(bytes);
            assert Arrays.equals(RapOracle.d(bytes),RapSigner.block(bytes)):"RAP block oracle mismatch";
        }
        byte[] entropy=new byte[37];r.nextBytes(entropy);
        String rap=RapSigner.sign("https://edith.xiaohongshu.com"+path+body,1700000000123L,"42",context(),entropy);
        byte[] envelope=WebSigner.decode(rap,WebSigner.STANDARD);
        assert envelope[0]==7 && envelope[1]==36 && envelope[2]==1 && envelope[3]==5;
        assert java.nio.ByteBuffer.wrap(envelope,16,4).getInt()==(int)RapSigner.xxhash(Arrays.copyOfRange(envelope,36,envelope.length));
        String json=WebSigner.json(WebSigner.map("content","中\n\"\\\t"));
        assert json.equals("{\"content\":\"中\\n\\\"\\\\\\t\"}"):json;
        System.out.println("PASS: 1024 Base64 sizes, malformed encodings, independent MNS vector, request/time sensitivity, xxHash vectors, 100 RAP oracle blocks, envelope checksum and JSON escaping.");
    }
}
