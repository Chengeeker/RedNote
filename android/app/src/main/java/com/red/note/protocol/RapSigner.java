package com.red.note.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** RAP telemetry and envelope for the observed web protocol. */
public final class RapSigner {
    private static final int[][] T = new int[4][256];
    static {
        for(int i=0;i<256;i++) {
            int s=RapTables.S[i], x=s<<1;
            if((x&256)!=0)x^=283;
            int y=x^s;
            T[0][i]=(x<<24)|(s<<16)|(s<<8)|y;
            T[1][i]=(y<<24)|(x<<16)|(s<<8)|s;
            T[2][i]=(s<<24)|(y<<16)|(x<<8)|s;
            T[3][i]=(s<<24)|(s<<16)|(y<<8)|x;
        }
    }
    public static byte[] block(byte[] input) {
        byte[] bytes=Arrays.copyOf(input,16);
        int[] state=new int[4];
        for(int i=0;i<4;i++)state[i]=be(bytes,4*i)^(int)RapTables.KEYS[0][i];
        for(int round=1;round<10;round++) {
            int[] next=new int[4];
            for(int i=0;i<4;i++)next[i]=T[0][state[i]>>>24]^T[1][(state[(i+1)&3]>>>16)&255]
                    ^T[2][(state[(i+2)&3]>>>8)&255]^T[3][state[(i+3)&3]&255]^(int)RapTables.KEYS[round][i];
            state=next;
        }
        byte[] out=new byte[16];
        for(int i=0;i<4;i++)for(int j=0;j<4;j++)out[i*4+j]=(byte)(RapTables.S[(state[(i+j)&3]>>>(24-j*8))&255]
                ^((RapTables.LAST[i]>>>(24-j*8))&255));
        return out;
    }
    private static int be(byte[] b,int p) {return ((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255);}
    private static void n(ByteArrayOutputStream out,long value,int size) {for(int i=size-1;i>=0;i--)out.write((int)(value>>>(8*i)));}
    private static void field(ByteArrayOutputStream out,int tag,long value,int size) {n(out,tag,2);n(out,value,size);}
    private static byte[] word(long n) {ByteArrayOutputStream out=new ByteArrayOutputStream();n(out,n,4);return out.toByteArray();}
    private static byte[] alpha(byte[] bytes) {
        byte[] result=bytes.clone();
        for(int i=0;i<result.length;i++)result[i]=(byte)"0123456789abcdefghijklmnopqrstuvwxyz".charAt((bytes[i]&255)%36);
        return result;
    }
    public static String sign(String request, long time, String direction, WebSigner.Context c, byte[] entropy) {
        if(entropy.length!=37 || direction.isEmpty())throw new IllegalArgumentException("RAP input");
        int key=direction.charAt(direction.length()-1);
        if(key>127)throw new IllegalArgumentException("RAP direction");
        byte[] prefix=alpha(Arrays.copyOfRange(entropy,0,5)), nonce=alpha(Arrays.copyOfRange(entropy,5,21)), salt=alpha(Arrays.copyOfRange(entropy,21,37));
        ByteArrayOutputStream telemetry=new ByteArrayOutputStream();
        for(int i=1051;i<=1065;i++)field(telemetry,i,0,1);
        field(telemetry,1070,0,1);
        for(int i=1066;i<=1069;i++)field(telemetry,i,0,1);
        field(telemetry,1100,0,4);
        for(int i:new int[]{1071,1072,1073})field(telemetry,i,0,1);
        for(int i:new int[]{1078,1082,1084,1088,1090})field(telemetry,i,0,4);
        field(telemetry,1097,0,4);
        field(telemetry,1092,c.width,4); field(telemetry,1094,c.height,4);
        field(telemetry,1095,c.pageStart>0?c.pageStart:c.loadTime,8);
        field(telemetry,1093,0,4);field(telemetry,1096,0,1);
        field(telemetry,1091,4,4);n(telemetry,65535,4);
        for(int i:new int[]{1156,1157,1152,1153,1154,1155})field(telemetry,i,0,i==1157?4:1);
        if(telemetry.size()!=173)throw new IllegalStateException("RAP telemetry length");
        ByteArrayOutputStream a=new ByteArrayOutputStream();
        field(a,1000,time,8);field(a,1001,xxhash(new byte[]{(byte)key}),4);
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        field(b,1002,salt.length,4);WebSigner.put(b,salt);
        field(b,1003,xxhash(WebSigner.utf8(request)),4);WebSigner.put(b,telemetry.toByteArray());
        byte[] xor=b.toByteArray();for(int i=0;i<xor.length;i++)xor[i]^=(byte)key;
        WebSigner.put(a,xor);byte[] plain=a.toByteArray();
        byte[] zipped=gzip(plain,time);
        byte[] coded=zipped.clone();for(int i=0;i<coded.length;i++)coded[i]^=nonce[i%16];
        ByteArrayOutputStream encrypted=new ByteArrayOutputStream();
        for(int i=0;i<coded.length;i+=16)WebSigner.put(encrypted,block(Arrays.copyOfRange(coded,i,Math.min(i+16,coded.length))));
        byte[] cipher=encrypted.toByteArray();
        ByteArrayOutputStream payload=new ByteArrayOutputStream();
        WebSigner.put(payload,prefix);WebSigner.put(payload,block(nonce));WebSigner.put(payload,word(16));
        WebSigner.put(payload,cipher);WebSigner.put(payload,word(zipped.length));
        byte[] body=payload.toByteArray();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        WebSigner.put(out,new byte[]{7,36,1,5});
        n(out,1,4);n(out,20,4);n(out,cipher.length+4,4);n(out,xxhash(body),4);n(out,10301,4);
        WebSigner.put(out,new byte[12]);WebSigner.put(out,body);
        return WebSigner.encode(out.toByteArray(),WebSigner.STANDARD);
    }
    private static byte[] gzip(byte[] plain,long time) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        WebSigner.put(out,new byte[]{31,(byte)139,8,0});WebSigner.little(out,time/1000,4);out.write(0);out.write(3);
        Deflater deflate=new Deflater(6,true);
        try {
            deflate.setInput(plain);deflate.finish();byte[] buffer=new byte[4096];
            while(!deflate.finished()) {int n=deflate.deflate(buffer);if(n==0)throw new IllegalStateException("RAP compression");out.write(buffer,0,n);}
        } finally {deflate.end();}
        CRC32 crc=new CRC32();crc.update(plain);WebSigner.little(out,crc.getValue(),4);WebSigner.little(out,plain.length,4);
        return out.toByteArray();
    }
    public static long xxhash(byte[] input) {
        int p1=(int)2654435761L,p2=(int)2246822519L,p3=(int)3266489917L,p4=668265263,p5=374761393;
        int i=0,h;
        if(input.length>=16) {
            int a=p1+p2,b=p2,c=0,d=-p1;
            while(i<=input.length-16) {
                a=Integer.rotateLeft(a+(int)WebSigner.le(input,i,4)*p2,13)*p1;
                b=Integer.rotateLeft(b+(int)WebSigner.le(input,i+4,4)*p2,13)*p1;
                c=Integer.rotateLeft(c+(int)WebSigner.le(input,i+8,4)*p2,13)*p1;
                d=Integer.rotateLeft(d+(int)WebSigner.le(input,i+12,4)*p2,13)*p1;i+=16;
            }
            h=Integer.rotateLeft(a,1)+Integer.rotateLeft(b,7)+Integer.rotateLeft(c,12)+Integer.rotateLeft(d,18);
        } else h=p5;
        h+=input.length;
        while(i+4<=input.length){h=Integer.rotateLeft(h+(int)WebSigner.le(input,i,4)*p3,17)*p4;i+=4;}
        while(i<input.length){h=Integer.rotateLeft(h+(input[i++]&255)*p5,11)*p1;}
        h^=h>>>15;h*=p2;h^=h>>>13;h*=p3;h^=h>>>16;
        return h&0xffffffffL;
    }
}
