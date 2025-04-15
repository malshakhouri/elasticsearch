package org.elasticsearch.lz4;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Exception;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ESLZ4Compressor extends LZ4Compressor {

    private static final ThreadLocal<short[]> sixtyFourKBHashTable = ThreadLocal.withInitial(() -> new short[8192]);
    private static final ThreadLocal<int[]> biggerHashTable = ThreadLocal.withInitial(() -> new int[4096]);

    public static final LZ4Compressor INSTANCE = new ESLZ4Compressor();

    ESLZ4Compressor() {}

    private int writeLiterals(byte[] src, byte[] dest, int anchor, int sOff, int dOff, int destEnd, int tokenOff) {
        int runLen = sOff - anchor;
        if (dOff + runLen + 8 + (runLen >>> 8) > destEnd) {
            throw new LZ4Exception("maxDestLen is too small");
        }
        if (runLen >= 15) {
            SafeUtils.writeByte(dest, tokenOff, 240);
            dOff = LZ4SafeUtils.writeLen(runLen - 15, dest, dOff);
        } else {
            SafeUtils.writeByte(dest, tokenOff, runLen << 4);
        }
        LZ4SafeUtils.wildArraycopy(src, anchor, dest, dOff, runLen);
        return dOff + runLen;
    }

    private int writeMatchLengthAndOffset(byte[] src, byte[] dest, int sOff, int ref, int dOff, int tokenOff, int srcLimit, int destEnd) {
        int back = sOff - ref;
        SafeUtils.writeShortLE(dest, dOff, back);
        dOff += 2;
        sOff += 4;
        int matchLen = LZ4SafeUtils.commonBytes(src, ref + 4, sOff, srcLimit);
        if (dOff + 6 + (matchLen >>> 8) > destEnd) {
            throw new LZ4Exception("maxDestLen is too small");
        }
        sOff += matchLen;
        if (matchLen >= 15) {
            SafeUtils.writeByte(dest, tokenOff, SafeUtils.readByte(dest, tokenOff) | 15);
            dOff = LZ4SafeUtils.writeLen(matchLen - 15, dest, dOff);
        } else {
            SafeUtils.writeByte(dest, tokenOff, SafeUtils.readByte(dest, tokenOff) | matchLen);
        }
        return dOff;
    }

    private int writeLastLiterals(byte[] src, int anchor, int srcEnd, byte[] dest, int dOff, int destEnd) {
        return LZ4SafeUtils.lastLiterals(src, anchor, srcEnd - anchor, dest, dOff, destEnd);
    }

    @Override
    public int compress(byte[] src, int srcOff, int srcLen, byte[] dest, int destOff, int maxDestLen) {
        SafeUtils.checkRange(src, srcOff, srcLen);
        SafeUtils.checkRange(dest, destOff, maxDestLen);
        int destEnd = destOff + maxDestLen;
        if (srcLen < 65547) {
            return compress64k(src, srcOff, srcLen, dest, destOff, destEnd);
        }

        int srcEnd = srcOff + srcLen;
        int srcLimit = srcEnd - 5;
        int mflimit = srcEnd - 12;
        int dOff = destOff;
        int sOff = srcOff + 1;
        int anchor = srcOff;
        int[] hashTable = biggerHashTable.get();
        Arrays.fill(hashTable, srcOff);

        while (sOff <= mflimit) {
            int forwardOff = sOff;
            int step = 1;
            int searchMatchNb = 1 << LZ4Constants.SKIP_STRENGTH;

            do {
                sOff = forwardOff;
                forwardOff += step;
                step = searchMatchNb++ >>> LZ4Constants.SKIP_STRENGTH;
                if (forwardOff > mflimit) {
                    return writeLastLiterals(src, anchor, srcEnd, dest, dOff, destEnd) - destOff;
                }
                int h = LZ4Utils.hash(SafeUtils.readInt(src, sOff));
                int ref = SafeUtils.readInt(hashTable, h);
                int back = sOff - ref;
                SafeUtils.writeInt(hashTable, h, sOff);
            } while (back >= 65536 || !LZ4SafeUtils.readIntEquals(src, sOff, SafeUtils.readInt(hashTable, LZ4Utils.hash(SafeUtils.readInt(src, sOff)))));

            int excess = LZ4SafeUtils.commonBytesBackward(src, SafeUtils.readInt(hashTable, LZ4Utils.hash(SafeUtils.readInt(src, sOff))), sOff, srcOff, anchor);
            sOff -= excess;
            int ref = SafeUtils.readInt(hashTable, LZ4Utils.hash(SafeUtils.readInt(src, sOff)));
            ref -= excess;
            int tokenOff = dOff++;
            dOff = writeLiterals(src, dest, anchor, sOff, dOff, destEnd, tokenOff);
            dOff = writeMatchLengthAndOffset(src, dest, sOff, ref, dOff, tokenOff, srcLimit, destEnd);
            anchor = sOff;
        }

        return writeLastLiterals(src, anchor, srcEnd, dest, dOff, destEnd) - destOff;
    }

    @Override
    public int compress(ByteBuffer src, int srcOff, int srcLen, ByteBuffer dest, int destOff, int maxDestLen) {
        if (src.hasArray() && dest.hasArray()) {
            return this.compress(src.array(), srcOff + src.arrayOffset(), srcLen, dest.array(), destOff + dest.arrayOffset(), maxDestLen);
        } else {
            throw new AssertionError("Do not support compression on direct buffers");
        }
    }

    static int compress64k(byte[] src, int srcOff, int srcLen, byte[] dest, int destOff, int destEnd) {
        int srcEnd = srcOff + srcLen;
        int srcLimit = srcEnd - 5;
        int mflimit = srcEnd - 12;
        int dOff = destOff;
        int anchor = srcOff;

        if (srcLen >= 13) {
            short[] hashTable = sixtyFourKBHashTable.get();
            Arrays.fill(hashTable, (short) 0);
            int sOff = srcOff + 1;

            while (sOff <= mflimit) {
                int forwardOff = sOff;
                int step = 1;
                int searchMatchNb = 1 << LZ4Constants.SKIP_STRENGTH;
                int ref;
                int excess;

                do {
                    sOff = forwardOff;
                    forwardOff += step;
                    step = searchMatchNb++ >>> LZ4Constants.SKIP_STRENGTH;
                    if (forwardOff > mflimit) {
                        return LZ4SafeUtils.lastLiterals(src, anchor, srcEnd - anchor, dest, dOff, destEnd) - destOff;
                    }
                    excess = LZ4Utils.hash64k(SafeUtils.readInt(src, sOff));
                    ref = srcOff + SafeUtils.readShort(hashTable, excess);
                    SafeUtils.writeShort(hashTable, excess, sOff - srcOff);
                } while (!LZ4SafeUtils.readIntEquals(src, ref, sOff));

                excess = LZ4SafeUtils.commonBytesBackward(src, ref, sOff, srcOff, anchor);
                sOff -= excess;
                ref -= excess;
                int tokenOff = dOff++;
                dOff = INSTANCE.writeLiterals(src, dest, anchor, sOff, dOff, destEnd, tokenOff);
                dOff = INSTANCE.writeMatchLengthAndOffset(src, dest, sOff, ref, dOff, tokenOff, srcLimit, destEnd);
                anchor = sOff;
            }
        }
        return INSTANCE.writeLastLiterals(src, anchor, srcEnd, dest, dOff, destEnd) - destOff;
    }
} 
