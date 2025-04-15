/* Refactored version of PanamaESVectorUtilSupport.java focusing on duplicated vector loop structures */

private static LongVector[] processByteVectorLoop256(byte[] q, byte[] d, int offset, VectorSpecies<Byte> species) {
    LongVector sum0 = LongVector.zero(species.reinterpretAsLongs());
    LongVector sum1 = LongVector.zero(species.reinterpretAsLongs());
    LongVector sum2 = LongVector.zero(species.reinterpretAsLongs());
    LongVector sum3 = LongVector.zero(species.reinterpretAsLongs());

    int limit = species.loopBound(d.length);
    for (int i = 0; i < limit; i += species.length()) {
        var vq0 = ByteVector.fromArray(species, q, i + offset).reinterpretAsLongs();
        var vq1 = ByteVector.fromArray(species, q, i + offset + d.length).reinterpretAsLongs();
        var vq2 = ByteVector.fromArray(species, q, i + offset + d.length * 2).reinterpretAsLongs();
        var vq3 = ByteVector.fromArray(species, q, i + offset + d.length * 3).reinterpretAsLongs();
        var vd = ByteVector.fromArray(species, d, i).reinterpretAsLongs();
        sum0 = sum0.add(vq0.and(vd).lanewise(VectorOperators.BIT_COUNT));
        sum1 = sum1.add(vq1.and(vd).lanewise(VectorOperators.BIT_COUNT));
        sum2 = sum2.add(vq2.and(vd).lanewise(VectorOperators.BIT_COUNT));
        sum3 = sum3.add(vq3.and(vd).lanewise(VectorOperators.BIT_COUNT));
    }
    return new LongVector[] { sum0, sum1, sum2, sum3 };
}

private static long reduceSums(LongVector[] sums) {
    return sums[0].reduceLanes(VectorOperators.ADD)
         + (sums[1].reduceLanes(VectorOperators.ADD) << 1)
         + (sums[2].reduceLanes(VectorOperators.ADD) << 2)
         + (sums[3].reduceLanes(VectorOperators.ADD) << 3);
}

private static long tailProcessingByteBit(byte[] q, byte[] d, int start) {
    long subRet0 = 0, subRet1 = 0, subRet2 = 0, subRet3 = 0;
    for (int i = start; i < d.length; i++) {
        subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
        subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
        subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
        subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
    }
    return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
}

// Sample usage of refactored methods within ipByteBin256
static long ipByteBin256(byte[] q, byte[] d) {
    long total = 0;
    int i = 0;
    if (d.length >= ByteVector.SPECIES_256.vectorByteSize() * 2) {
        LongVector[] sums = processByteVectorLoop256(q, d, 0, BYTE_SPECIES_256);
        total += reduceSums(sums);
        i = ByteVector.SPECIES_256.loopBound(d.length);
    }
    if (d.length - i >= ByteVector.SPECIES_128.vectorByteSize()) {
        LongVector[] sums = processByteVectorLoop256(q, d, i, BYTE_SPECIES_128);
        total += reduceSums(sums);
        i += ByteVector.SPECIES_128.loopBound(d.length - i);
    }
    total += tailProcessingByteBit(q, d, i);
    return total;
}
