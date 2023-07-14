/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.BitArray;
import org.elasticsearch.common.util.IntArray;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBigArrayVector;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.IntBigArrayVector;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntRangeVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.core.Releasables;

/**
 * Aggregator state for an array of ints.
 * This class is generated. Do not edit it.
 */
final class IntArrayState implements GroupingAggregatorState {
    private final BigArrays bigArrays;
    private final int init;
    private final DriverContext driverContext;

    private IntArray values;
    /**
     * Total number of groups {@code <=} values.length.
     */
    private int largestIndex;
    private BitArray nonNulls;

    IntArrayState(BigArrays bigArrays, int init, DriverContext driverContext) {
        this.bigArrays = bigArrays;
        this.values = bigArrays.newIntArray(1, false);
        this.values.set(0, init);
        this.init = init;
        this.driverContext = driverContext;
    }

    int get(int index) {
        return values.get(index);
    }

    int getOrDefault(int index) {
        return index <= largestIndex ? values.get(index) : init;
    }

    void set(int value, int index) {
        if (index > largestIndex) {
            ensureCapacity(index);
            largestIndex = index;
        }
        values.set(index, value);
        if (nonNulls != null) {
            nonNulls.set(index);
        }
    }

    void putNull(int index) {
        if (index > largestIndex) {
            ensureCapacity(index);
            largestIndex = index;
        }
        if (nonNulls == null) {
            nonNulls = new BitArray(index + 1, bigArrays);
            for (int i = 0; i < index; i++) {
                nonNulls.set(i);
            }
        } else {
            nonNulls.ensureCapacity(index + 1);
        }
    }

    boolean hasValue(int index) {
        return nonNulls == null || nonNulls.get(index);
    }

    Block toValuesBlock(org.elasticsearch.compute.data.IntVector selected) {
        if (nonNulls == null) {
            IntVector.Builder builder = IntVector.newVectorBuilder(selected.getPositionCount());
            for (int i = 0; i < selected.getPositionCount(); i++) {
                builder.appendInt(values.get(selected.getInt(i)));
            }
            return builder.build().asBlock();
        }
        IntBlock.Builder builder = IntBlock.newBlockBuilder(selected.getPositionCount());
        for (int i = 0; i < selected.getPositionCount(); i++) {
            int group = selected.getInt(i);
            if (hasValue(group)) {
                builder.appendInt(values.get(group));
            } else {
                builder.appendNull();
            }
        }
        return builder.build();
    }

    private void ensureCapacity(int position) {
        if (position >= values.size()) {
            long prevSize = values.size();
            values = bigArrays.grow(values, position + 1);
            values.fill(prevSize, values.size(), init);
        }
    }

    /** Extracts an intermediate view of the contents of this state.  */
    @Override
    public void toIntermediate(Block[] blocks, int offset, IntVector selected) {
        assert blocks.length >= offset + 2;
        blocks[offset + 0] = intermediateValues(selected);
        blocks[offset + 1] = intermediateNonNulls(selected);
    }

    Block intermediateValues(IntVector selected) {
        if (IntRangeVector.isRangeFromMToN(selected, 0, selected.getPositionCount())) {
            IntBigArrayVector vector = new IntBigArrayVector(values, selected.getPositionCount());
            values = null; // do not release
            driverContext.addReleasable(vector);
            return vector.asBlock();
        } else {
            var valuesBuilder = IntBlock.newBlockBuilder(selected.getPositionCount());
            for (int i = 0; i < selected.getPositionCount(); i++) {
                int group = selected.getInt(i);
                valuesBuilder.appendInt(values.get(group));
            }
            return valuesBuilder.build();
        }
    }

    Block intermediateNonNulls(IntVector selected) {
        if (nonNulls == null) {
            return BooleanBlock.newConstantBlockWith(true, selected.getPositionCount());
        }
        if (IntRangeVector.isRangeFromMToN(selected, 0, selected.getPositionCount())) {
            BooleanBigArrayVector vector = new BooleanBigArrayVector(nonNulls, selected.getPositionCount());
            nonNulls = null; // do not release
            driverContext.addReleasable(vector);
            return vector.asBlock();
        }
        var nullsBuilder = BooleanBlock.newBlockBuilder(selected.getPositionCount());
        for (int i = 0; i < selected.getPositionCount(); i++) {
            int group = selected.getInt(i);
            nullsBuilder.appendBoolean(hasValue(group));
        }
        return nullsBuilder.build();
    }

    @Override
    public void close() {
        Releasables.close(values, nonNulls);
    }
}
