/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.LongDoubleTupleBlockSourceOperator;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.search.aggregations.metrics.CompensatedSum;

import java.util.List;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

public class AvgDoubleGroupingAggregatorFunctionTests extends GroupingAggregatorFunctionTestCase {
    @Override
    protected SourceOperator simpleInput(int size) {
        return new LongDoubleTupleBlockSourceOperator(
            LongStream.range(0, size).mapToObj(l -> Tuple.tuple(randomLongBetween(0, 4), randomDouble()))
        );
    }

    @Override
    protected AggregatorFunctionSupplier aggregatorFunction(BigArrays bigArrays, int inputChannel) {
        return new AvgDoubleAggregatorFunctionSupplier(bigArrays, inputChannel);
    }

    @Override
    protected String expectedDescriptionOfAggregator() {
        return "avg of doubles";
    }

    @Override
    protected void assertSimpleGroup(List<Page> input, Block result, int position, long group) {
        CompensatedSum sum = new CompensatedSum();
        input.stream().flatMapToDouble(p -> allDoubles(p, group)).forEach(sum::add);
        long count = input.stream().flatMapToDouble(p -> allDoubles(p, group)).count();
        if (count == 0) {
            // If all values are null we'll have a count of 0. So we'll be null.
            assertThat(result.isNull(position), equalTo(true));
            return;
        }
        assertThat(result.isNull(position), equalTo(false));
        assertThat(((DoubleBlock) result).getDouble(position), closeTo(sum.value() / count, 0.001));
    }
}